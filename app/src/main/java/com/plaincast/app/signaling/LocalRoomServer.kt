package com.plaincast.app.signaling

import android.util.Log
import com.plaincast.app.model.ClientType
import com.plaincast.app.model.Participant
import com.plaincast.app.model.Role
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.decodeFromJsonElement
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

class LocalRoomServer(
    private val roomId: String,
    port: Int,
    private val hostPeerId: String,
    private val hostName: String,
    private val joinToken: String,
    initialRoomConfig: RoomConfig = RoomConfig(),
    private val onEvent: (ServerEvent) -> Unit,
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    private val peers = ConcurrentHashMap<WebSocket, Participant>()
    private val socketsByPeer = ConcurrentHashMap<String, WebSocket>()
    private val capabilitiesByPeer = ConcurrentHashMap<String, Capabilities>()
    private val joinAttemptsByAddress = ConcurrentHashMap<String, ArrayDeque<Long>>()
    @Volatile private var hostParticipant = Participant(hostPeerId, hostName, Role.HOST)
    @Volatile private var activeScreenPeerId: String? = null
    @Volatile private var roomConfig = initialRoomConfig
    private val audioAuthority = AudioPublisherAuthority()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Socket opened ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val participant = peers.remove(conn) ?: return
        if (!socketsByPeer.remove(participant.peerId, conn)) return
        capabilitiesByPeer.remove(participant.peerId)
        mutateAudioAuthority { audioAuthority.disconnect(participant.peerId) }
        if (activeScreenPeerId == participant.peerId) {
            activeScreenPeerId = null
            updateScreenFlags(null)
            broadcastShareState("screen_share_stopped", participant.copy(screen = false), false)
        }
        broadcast(
            SignalingEnvelope(
                type = "participant_left",
                roomId = roomId,
                from = participant.peerId,
                payload = SignalJson.payload(ParticipantLeftPayload(participant.peerId)),
            )
        )
        broadcastParticipants()
        onEvent(ServerEvent.ParticipantLeft(participant.peerId))
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (message.length > MAX_SIGNAL_CHARS) return conn.close(1009, "signal too large")
        val envelope = runCatching { SignalJson.decode(message) }.getOrElse { error ->
            Log.w(TAG, "Invalid signal", error)
            conn.close(1008, error.message ?: "invalid protocol")
            return
        }
        if (envelope.type != "join") {
            val participant = peers[conn] ?: return conn.close(1008, "join required")
            if (participant.peerId != envelope.from) return conn.close(1008, "sender identity mismatch")
            if (envelope.roomId != roomId) return conn.close(1008, "room identity mismatch")
        }
        when (envelope.type) {
            "join" -> handleJoin(conn, envelope)
            "leave" -> conn.close(1000, "left")
            "audio_publish_request" -> requireCapability(conn, publishAudio = true) { handleAudioPublishRequest(conn, envelope) }
            "screen_share_started", "screen_share_stopped" -> requireCapability(conn, publishScreen = true) { handleScreenShareState(conn, envelope) }
            "track_state" -> requireCapability(conn, sendVoice = true) { route(conn, envelope) }
            "offer", "answer", "ice", "renegotiate_request" -> route(conn, envelope)
            "room_config" -> Unit
            "ping" -> runCatching { conn.send(SignalJson.simple("pong", roomId, hostPeerId, envelope.from)) }
            else -> conn.close(1008, "unsupported signal")
        }
    }

    override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
        conn.close(1003, "binary signaling is not supported")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "Server error", ex)
        onEvent(ServerEvent.Error(ex.message ?: "Unknown server error"))
    }

    override fun onStart() {
        connectionLostTimeout = HEARTBEAT_TIMEOUT_SECONDS
        Log.i(TAG, "PlainCast server started on $address")
        onEvent(ServerEvent.Started)
    }

    fun participants(): List<Participant> =
        listOf(hostParticipant) + peers.values.sortedBy { it.displayName.lowercase() }

    fun updateHostMicrophoneState(mic: Boolean) {
        hostParticipant = hostParticipant.copy(mic = mic)
        broadcastParticipants()
    }

    fun sendTo(peerId: String, envelope: SignalingEnvelope) {
        val socket = socketsByPeer[peerId]?.takeIf { it.isOpen } ?: return
        runCatching { socket.send(SignalJson.encode(envelope)) }
            .onFailure { error -> Log.w(TAG, "Could not send signal to $peerId", error) }
    }

    fun broadcast(envelope: SignalingEnvelope) {
        val raw = SignalJson.encode(envelope)
        peers.keys.forEach { socket ->
            if (socket.isOpen) runCatching { socket.send(raw) }
                .onFailure { error -> Log.w(TAG, "Could not broadcast signal", error) }
        }
    }

    fun broadcastParticipants() {
        val authority = audioAuthority.snapshot()
        broadcast(
            SignalingEnvelope(
                type = "participants",
                roomId = roomId,
                from = hostPeerId,
                payload = SignalJson.payload(
                    ParticipantsPayload(
                        participants = participants(),
                        activeAudioPublisherId = authority.activePeerId,
                        audioGeneration = authority.generation,
                        activeScreenSharerId = activeScreenPeerId,
                        roomConfig = roomConfig,
                    )
                ),
            )
        )
    }

    fun requestAudioPublisher(
        peerId: String?,
        reason: String = if (peerId == null) "publisher_stopped" else "publisher_requested",
    ) {
        if (peerId == null) {
            mutateAudioAuthority { audioAuthority.stop(hostPeerId, force = false, reason = reason) }
            return
        }
        require(participants().any { it.peerId == peerId }) { "Unknown audio publisher." }
        mutateAudioAuthority { audioAuthority.request(peerId, reason) }
    }

    fun stopActiveAudioPublisher() {
        mutateAudioAuthority { audioAuthority.stop(peerId = null, force = true, reason = "host_stopped") }
    }

    fun setActiveScreenPeer(peerId: String?) {
        if (peerId != null) require(participants().any { it.peerId == peerId }) { "Unknown screen publisher." }
        activeScreenPeerId = peerId
        updateScreenFlags(peerId)
        broadcastParticipants()
    }

    fun setRoomConfig(config: RoomConfig) {
        require(config.maxParticipants in 2..8) { "Participant limit must be between 2 and 8." }
        roomConfig = config
        broadcast(
            SignalingEnvelope(
                type = "room_config",
                roomId = roomId,
                from = hostPeerId,
                payload = SignalJson.payload(RoomConfigPayload(roomConfig)),
            )
        )
        broadcastParticipants()
    }

    fun removeParticipant(peerId: String) {
        val socket = socketsByPeer[peerId] ?: return
        val removed = SignalingEnvelope(
            type = "removed",
            roomId = roomId,
            from = hostPeerId,
            to = peerId,
            payload = SignalJson.payload(RemovedPayload("host_removed")),
        )
        runCatching { socket.send(SignalJson.encode(removed)) }
        runCatching { socket.close(4001, "removed by host") }
    }

    fun stopRoom() {
        broadcast(
            SignalingEnvelope(
                type = "room_ended",
                roomId = roomId,
                from = hostPeerId,
                payload = SignalJson.payload(RoomEndedPayload("host_ended")),
            )
        )
        connections.forEach { it.close(1001, "room ended") }
        stop(1_000)
    }

    private fun handleJoin(conn: WebSocket, envelope: SignalingEnvelope) {
        if (!registerJoinAttempt(conn)) return rejectJoin(conn, envelope.from, "rate_limited")
        val payload = runCatching {
            SignalJson.json.decodeFromJsonElement<JoinPayload>(envelope.payload)
        }.getOrNull()
        if (
            payload == null || envelope.roomId != roomId || envelope.from == hostPeerId ||
            envelope.from.length !in 3..64 || !tokenMatches(payload.token)
        ) return rejectJoin(conn, envelope.from, "unauthorized")

        val existing = socketsByPeer[envelope.from]
        if (existing == null && peers.size >= roomConfig.maxParticipants - 1) {
            return rejectJoin(conn, envelope.from, "room_full")
        }
        if (!payload.capabilities.isValidFor(payload.clientType)) {
            return rejectJoin(conn, envelope.from, "invalid_capabilities")
        }
        val authority = audioAuthority.snapshot()
        val fallbackName = if (payload.clientType == ClientType.Browser) "Web browser" else "Android"
        val displayName = cleanDisplayName(payload.displayName.ifBlank { payload.deviceName }, fallbackName)
        val previous = existing?.let { peers[it] }
        val participant = Participant(
            peerId = envelope.from,
            displayName = displayName,
            role = Role.PARTICIPANT,
            clientType = payload.clientType,
            mic = previous?.mic ?: false,
            screen = activeScreenPeerId == envelope.from,
            audio = authority.activePeerId == envelope.from,
        )
        val acceptedParticipants = listOf(hostParticipant) +
            (peers.values.filterNot { it.peerId == participant.peerId } + participant)
                .sortedBy { it.displayName.lowercase() }
        val accepted = SignalingEnvelope(
            type = "join_accepted",
            roomId = roomId,
            from = hostPeerId,
            to = participant.peerId,
            payload = SignalJson.payload(
                JoinAcceptedPayload(
                    peerId = participant.peerId,
                    participants = acceptedParticipants,
                    roomConfig = roomConfig,
                    activeAudioPublisherId = authority.activePeerId,
                    audioGeneration = authority.generation,
                    activeScreenSharerId = activeScreenPeerId,
                )
            ),
        )
        if (!runCatching { conn.send(SignalJson.encode(accepted)) }.isSuccess) {
            conn.close(1011, "join response failed")
            return
        }
        clearJoinAttempts(conn)
        if (existing != null && existing !== conn) peers.remove(existing)
        peers[conn] = participant
        socketsByPeer[participant.peerId] = conn
        capabilitiesByPeer[participant.peerId] = payload.capabilities
        existing?.takeIf { it !== conn }?.close(1012, "reconnected")
        broadcastParticipants()
        onEvent(ServerEvent.ParticipantJoined(participant))
    }

    private fun handleAudioPublishRequest(conn: WebSocket, envelope: SignalingEnvelope) {
        val participant = peers[conn] ?: return
        val request = runCatching {
            SignalJson.json.decodeFromJsonElement<AudioPublishRequestPayload>(envelope.payload)
        }.getOrNull() ?: return
        if (request.active) {
            mutateAudioAuthority { audioAuthority.request(participant.peerId, "publisher_requested") }
        } else {
            mutateAudioAuthority { audioAuthority.stop(participant.peerId, force = false, reason = "publisher_stopped") }
        }
    }

    @Synchronized
    private fun mutateAudioAuthority(mutation: () -> List<AudioPublisherAuthorityEvent>) {
        val before = audioAuthority.snapshot()
        mutation().forEach(::dispatchAudioAuthorityEvent)
        val after = audioAuthority.snapshot()
        if (after != before) {
            updateAudioFlags(after.activePeerId)
            broadcastParticipants()
            onEvent(ServerEvent.AudioAuthorityChanged(after))
        }
    }

    private fun dispatchAudioAuthorityEvent(event: AudioPublisherAuthorityEvent) {
        when (event) {
            is AudioPublisherAuthorityEvent.PublisherChanged -> {
                val transition = event.transition
                val participant = participants().firstOrNull { it.peerId == transition.currentPeerId }
                val envelope = SignalingEnvelope(
                    type = "audio_publisher_changed",
                    roomId = roomId,
                    from = hostPeerId,
                    payload = SignalJson.payload(
                        AudioPublisherChangedPayload(
                            publisherPeerId = transition.currentPeerId,
                            previousPublisherPeerId = transition.previousPeerId,
                            displayName = participant?.displayName,
                            generation = transition.generation,
                            reason = transition.reason,
                        )
                    ),
                )
                broadcast(envelope)
                onEvent(ServerEvent.SignalForHost(envelope))
            }
            is AudioPublisherAuthorityEvent.RequestRejected -> {
                val active = participants().firstOrNull { it.peerId == event.activePeerId }
                val envelope = SignalingEnvelope(
                    type = "audio_publish_rejected",
                    roomId = roomId,
                    from = hostPeerId,
                    to = event.peerId,
                    payload = SignalJson.payload(
                        AudioPublishRejectedPayload(
                            reason = event.reason,
                            activePublisherPeerId = event.activePeerId,
                            displayName = active?.displayName,
                        )
                    ),
                )
                if (event.peerId == hostPeerId) onEvent(ServerEvent.SignalForHost(envelope))
                else sendTo(event.peerId, envelope)
            }
        }
    }

    private fun handleScreenShareState(conn: WebSocket, envelope: SignalingEnvelope) {
        val participant = peers[conn] ?: return
        val active = envelope.type == "screen_share_started"
        if (active) {
            activeScreenPeerId = participant.peerId
            updateScreenFlags(participant.peerId)
        } else if (activeScreenPeerId == participant.peerId) {
            activeScreenPeerId = null
            updateScreenFlags(null)
        }
        val outbound = envelope.copy(
            from = participant.peerId,
            to = "*",
            payload = SignalJson.payload(ShareStatePayload(participant.peerId, participant.displayName, active)),
        )
        broadcast(outbound)
        onEvent(ServerEvent.SignalForHost(outbound))
        broadcastParticipants()
    }

    private fun route(conn: WebSocket, envelope: SignalingEnvelope) {
        val participant = peers[conn] ?: return
        if (participant.peerId != envelope.from || envelope.roomId != roomId) return
        if (envelope.type == "track_state") {
            val payload = runCatching {
                SignalJson.json.decodeFromJsonElement<TrackStatePayload>(envelope.payload)
            }.getOrNull()
            if (payload != null) {
                updateParticipant(envelope.from) { it.copy(mic = payload.mic) }
                broadcastParticipants()
            }
        }
        when (envelope.to) {
            hostPeerId -> onEvent(ServerEvent.SignalForHost(envelope))
            "*" -> {
                peers.entries.filterNot { it.key === conn }.forEach { (socket, _) ->
                    if (socket.isOpen) runCatching { socket.send(SignalJson.encode(envelope)) }
                }
                onEvent(ServerEvent.SignalForHost(envelope))
            }
            else -> sendTo(envelope.to, envelope)
        }
    }

    private fun updateAudioFlags(peerId: String?) {
        hostParticipant = hostParticipant.copy(audio = hostPeerId == peerId)
        peers.entries.forEach { (socket, participant) ->
            peers[socket] = participant.copy(audio = participant.peerId == peerId)
        }
    }

    private fun updateScreenFlags(peerId: String?) {
        hostParticipant = hostParticipant.copy(screen = hostPeerId == peerId)
        peers.entries.forEach { (socket, participant) ->
            peers[socket] = participant.copy(screen = participant.peerId == peerId)
        }
    }

    private fun updateParticipant(peerId: String, transform: (Participant) -> Participant) {
        if (peerId == hostPeerId) {
            hostParticipant = transform(hostParticipant)
            return
        }
        val socket = socketsByPeer[peerId] ?: return
        peers[socket]?.let { peers[socket] = transform(it) }
    }

    private fun broadcastShareState(type: String, participant: Participant, active: Boolean) {
        broadcast(
            SignalingEnvelope(
                type = type,
                roomId = roomId,
                from = participant.peerId,
                payload = SignalJson.payload(ShareStatePayload(participant.peerId, participant.displayName, active)),
            )
        )
    }

    private inline fun requireCapability(
        conn: WebSocket,
        publishAudio: Boolean = false,
        publishScreen: Boolean = false,
        sendVoice: Boolean = false,
        action: () -> Unit,
    ) {
        val participant = peers[conn] ?: return conn.close(1008, "join required")
        val capabilities = capabilitiesByPeer[participant.peerId] ?: return conn.close(1008, "capabilities missing")
        val allowed = (!publishAudio || capabilities.publishAudio) &&
            (!publishScreen || capabilities.publishScreen) &&
            (!sendVoice || capabilities.sendVoice)
        // Capture capabilities may change while a browser is in the room (for
        // example when it is opened through an HTTP invitation).  Treat an
        // unsupported publish request as a no-op so that browser can remain
        // connected and continue receiving room media.
        if (!allowed) {
            Log.d(TAG, "Ignoring unsupported capability request from ${participant.peerId}")
            return
        }
        action()
    }

    private fun registerJoinAttempt(conn: WebSocket): Boolean {
        val now = System.currentTimeMillis()
        val key = remoteAddressKey(conn)
        pruneJoinAttempts(now)
        if (!joinAttemptsByAddress.containsKey(key) && joinAttemptsByAddress.size >= MAX_TRACKED_JOIN_ADDRESSES) return false
        val attempts = joinAttemptsByAddress.computeIfAbsent(key) { ArrayDeque() }
        return synchronized(attempts) {
            while (attempts.isNotEmpty() && now - attempts.first() > JOIN_ATTEMPT_WINDOW_MS) attempts.removeFirst()
            if (attempts.size >= MAX_JOIN_ATTEMPTS_PER_WINDOW) false
            else {
                attempts.addLast(now)
                true
            }
        }
    }

    private fun clearJoinAttempts(conn: WebSocket) {
        joinAttemptsByAddress.remove(remoteAddressKey(conn))
    }

    private fun pruneJoinAttempts(now: Long) {
        if (joinAttemptsByAddress.size < MAX_TRACKED_JOIN_ADDRESSES) return
        joinAttemptsByAddress.forEach { (key, attempts) ->
            val stale = synchronized(attempts) { attempts.isEmpty() || now - attempts.last() > JOIN_ATTEMPT_WINDOW_MS }
            if (stale) joinAttemptsByAddress.remove(key, attempts)
        }
    }

    private fun cleanDisplayName(value: String, fallback: String): String {
        val clean = value.filterNot(Char::isISOControl).trim().take(MAX_DISPLAY_NAME_CHARS)
        return clean.ifBlank { fallback }
    }

    private fun remoteAddressKey(conn: WebSocket): String =
        conn.remoteSocketAddress?.address?.hostAddress ?: conn.remoteSocketAddress?.hostString ?: "unknown"

    private fun tokenMatches(candidate: String): Boolean = MessageDigest.isEqual(
        joinToken.toByteArray(StandardCharsets.UTF_8),
        candidate.toByteArray(StandardCharsets.UTF_8),
    )

    private fun rejectJoin(conn: WebSocket, peerId: String, reason: String) {
        val rejection = SignalingEnvelope(
            type = "join_rejected",
            roomId = roomId,
            from = hostPeerId,
            to = peerId,
            payload = SignalJson.payload(JoinRejectedPayload(reason)),
        )
        runCatching { conn.send(SignalJson.encode(rejection)) }
        conn.close(1008, reason)
    }

    companion object {
        private const val TAG = "LocalRoomServer"
        private const val HEARTBEAT_TIMEOUT_SECONDS = 6
        private const val MAX_JOIN_ATTEMPTS_PER_WINDOW = 12
        private const val MAX_TRACKED_JOIN_ADDRESSES = 128
        private const val MAX_SIGNAL_CHARS = 64 * 1024
        private const val MAX_DISPLAY_NAME_CHARS = 40
        private const val JOIN_ATTEMPT_WINDOW_MS = 60_000L
    }
}

sealed interface ServerEvent {
    data object Started : ServerEvent
    data class ParticipantJoined(val participant: Participant) : ServerEvent
    data class ParticipantLeft(val peerId: String) : ServerEvent
    data class AudioAuthorityChanged(val snapshot: AudioPublisherAuthoritySnapshot) : ServerEvent
    data class SignalForHost(val envelope: SignalingEnvelope) : ServerEvent
    data class Error(val message: String) : ServerEvent
}
