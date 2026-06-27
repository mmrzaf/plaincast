package com.plaincast.app.signaling

import android.util.Log
import com.plaincast.app.model.Participant
import com.plaincast.app.model.Role
import kotlinx.serialization.json.decodeFromJsonElement
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class LocalRoomServer(
    private val roomId: String,
    private val token: String,
    port: Int,
    private val hostPeerId: String,
    private val hostName: String,
    private val onEvent: (ServerEvent) -> Unit,
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    private val peers = ConcurrentHashMap<WebSocket, Participant>()
    private val socketsByPeer = ConcurrentHashMap<String, WebSocket>()
    private val hostParticipant = Participant(hostPeerId, hostName, Role.HOST, mic = true)

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "socket opened ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val participant = peers.remove(conn) ?: return
        socketsByPeer.remove(participant.peerId)
        broadcastText(
            SignalJson.encode(
                SignalingEnvelope(
                    type = "participant_left",
                    roomId = roomId,
                    from = participant.peerId,
                    to = "*",
                    payload = SignalJson.payload(ParticipantLeftPayload(participant.peerId))
                )
            )
        )
        onEvent(ServerEvent.ParticipantLeft(participant.peerId))
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val env = runCatching { SignalJson.decode(message) }.getOrElse {
            Log.w(TAG, "invalid signal: $message", it)
            return
        }
        when (env.type) {
            "join" -> handleJoin(conn, env)
            "offer", "answer", "ice", "track_state" -> route(env)
            "leave" -> conn.close(1000, "left")
            else -> route(env)
        }
    }

    override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
        // Clients should not send device-audio binary in v1. Ignore to avoid accidental loops.
        Log.v(TAG, "ignored binary frame from ${peers[conn]?.peerId}")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "server error", ex)
        onEvent(ServerEvent.Error(ex.message ?: "Unknown server error"))
    }

    override fun onStart() {
        connectionLostTimeout = 10
        Log.i(TAG, "PlainCast server started on $address")
    }

    fun participants(): List<Participant> = listOf(hostParticipant) + peers.values.sortedBy { it.displayName }

    fun sendTo(peerId: String, env: SignalingEnvelope) {
        socketsByPeer[peerId]?.send(SignalJson.encode(env))
    }

    fun broadcastText(raw: String) {
        connections.forEach { socket ->
            if (socket.isOpen) socket.send(raw)
        }
    }

    fun broadcast(env: SignalingEnvelope) = broadcastText(SignalJson.encode(env))

    fun broadcastDeviceAudio(bytes: ByteArray, length: Int) {
        val copy = ByteArray(length)
        System.arraycopy(bytes, 0, copy, 0, length)
        connections.forEach { socket -> if (socket.isOpen) socket.send(copy) }
    }

    fun removeParticipant(peerId: String) {
        val socket = socketsByPeer[peerId] ?: return
        socket.send(
            SignalJson.encode(
                SignalingEnvelope(
                    type = "removed",
                    roomId = roomId,
                    from = hostPeerId,
                    to = peerId,
                    payload = SignalJson.payload(RemovedPayload("host_removed"))
                )
            )
        )
        socket.close(4001, "removed by host")
    }

    fun stopRoom() {
        broadcast(
            SignalingEnvelope(
                type = "room_ended",
                roomId = roomId,
                from = hostPeerId,
                to = "*",
                payload = SignalJson.payload(RoomEndedPayload("host_ended"))
            )
        )
        connections.forEach { it.close(1001, "room ended") }
        stop(1000)
    }

    private fun handleJoin(conn: WebSocket, env: SignalingEnvelope) {
        val payload = SignalJson.json.decodeFromJsonElement<JoinPayload>(env.payload)
        if (env.roomId != roomId || payload.token != token) {
            conn.send(
                SignalJson.encode(
                    SignalingEnvelope(
                        type = "join_rejected",
                        roomId = roomId,
                        from = hostPeerId,
                        to = env.from,
                        payload = SignalJson.payload(JoinRejectedPayload("invalid_room_or_token"))
                    )
                )
            )
            conn.close(1008, "invalid token")
            return
        }
        if (peers.size >= 3) {
            conn.send(
                SignalJson.encode(
                    SignalingEnvelope(
                        type = "join_rejected",
                        roomId = roomId,
                        from = hostPeerId,
                        to = env.from,
                        payload = SignalJson.payload(JoinRejectedPayload("room_full"))
                    )
                )
            )
            conn.close(1008, "room full")
            return
        }
        val participant = Participant(env.from, payload.displayName, Role.PARTICIPANT, mic = true)
        peers[conn] = participant
        socketsByPeer[participant.peerId] = conn
        conn.send(
            SignalJson.encode(
                SignalingEnvelope(
                    type = "join_accepted",
                    roomId = roomId,
                    from = hostPeerId,
                    to = participant.peerId,
                    payload = SignalJson.payload(JoinAcceptedPayload(participant.peerId, participants()))
                )
            )
        )
        broadcast(
            SignalingEnvelope(
                type = "participants",
                roomId = roomId,
                from = hostPeerId,
                to = "*",
                payload = SignalJson.payload(ParticipantsPayload(participants()))
            )
        )
        onEvent(ServerEvent.ParticipantJoined(participant))
    }

    private fun route(env: SignalingEnvelope) {
        if (env.to == hostPeerId) {
            onEvent(ServerEvent.SignalForHost(env))
        } else if (env.to == "*") {
            broadcast(env)
        } else {
            sendTo(env.to, env)
        }
    }

    companion object { private const val TAG = "LocalRoomServer" }
}

@kotlinx.serialization.Serializable
data class ParticipantsPayload(val participants: List<Participant>)

sealed interface ServerEvent {
    data class ParticipantJoined(val participant: Participant) : ServerEvent
    data class ParticipantLeft(val peerId: String) : ServerEvent
    data class SignalForHost(val envelope: SignalingEnvelope) : ServerEvent
    data class Error(val message: String) : ServerEvent
}
