package com.plaincast.app

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plaincast.app.audio.DeviceAudioCaptureController
import com.plaincast.app.audio.DeviceAudioPlayer
import com.plaincast.app.model.DEFAULT_PORT
import com.plaincast.app.model.Participant
import com.plaincast.app.model.Role
import com.plaincast.app.model.RoomState
import com.plaincast.app.model.randomId
import com.plaincast.app.model.randomRoomId
import com.plaincast.app.model.randomToken
import com.plaincast.app.network.LocalIpResolver
import com.plaincast.app.qr.QrPayload
import com.plaincast.app.rtc.PeerConnectionManager
import com.plaincast.app.rtc.RemoteVideoSink
import com.plaincast.app.rtc.SdpWire
import com.plaincast.app.service.PlainCastActiveService
import com.plaincast.app.signaling.ClientEvent
import com.plaincast.app.signaling.IcePayload
import com.plaincast.app.signaling.JoinPayload
import com.plaincast.app.signaling.LocalRoomClient
import com.plaincast.app.signaling.LocalRoomServer
import com.plaincast.app.signaling.ParticipantsPayload
import com.plaincast.app.signaling.ServerEvent
import com.plaincast.app.signaling.SignalJson
import com.plaincast.app.signaling.SignalingEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import org.webrtc.VideoTrack
import java.net.URI

class PlainCastViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _room = MutableStateFlow(RoomState(displayName = defaultDeviceName()))
    val room: StateFlow<RoomState> = _room.asStateFlow()
    val remoteVideo = RemoteVideoSink()

    private var server: LocalRoomServer? = null
    private var client: LocalRoomClient? = null
    private var rtc: PeerConnectionManager? = null
    private var deviceAudioPlayer: DeviceAudioPlayer? = null
    private var deviceAudioCapture: DeviceAudioCaptureController? = null
    private var activeAudioProjection: MediaProjection? = null

    fun createRoom(displayName: String = defaultDeviceName()) {
        if (server != null) return
        val ip = LocalIpResolver.bestLocalIpv4(app) ?: run {
            setStatus("Could not find local IP. Connect to Wi‑Fi or hotspot.")
            return
        }
        val roomId = randomRoomId()
        val token = randomToken()
        val peerId = randomId("host")
        val state = RoomState(
            roomId = roomId,
            token = token,
            hostAddress = ip,
            port = DEFAULT_PORT,
            selfPeerId = peerId,
            displayName = displayName.ifBlank { defaultDeviceName() },
            isHost = true,
            isConnected = true,
            micEnabled = true,
            status = "Room ready on $ip:$DEFAULT_PORT",
            participants = listOf(Participant(peerId, displayName.ifBlank { defaultDeviceName() }, Role.HOST, mic = true))
        )
        _room.value = state
        rtc = newRtc(peerId)
        rtc?.ensureMicTrack(true)
        val localServer = LocalRoomServer(roomId, token, DEFAULT_PORT, peerId, state.displayName) { event ->
            viewModelScope.launch { handleServerEvent(event) }
        }
        server = localServer
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { localServer.start() }.onFailure { setStatus("Could not start local room: ${it.message}") }
        }
        PlainCastActiveService.start(app, "Hosting local room $roomId", mic = true, projection = false)
    }

    fun joinRoom(payload: QrPayload, displayName: String = defaultDeviceName()) {
        leaveRoom()
        val peerId = randomId("peer")
        _room.value = RoomState(
            roomId = payload.roomId,
            token = payload.token,
            hostAddress = payload.host,
            port = payload.port,
            selfPeerId = peerId,
            displayName = displayName.ifBlank { defaultDeviceName() },
            isHost = false,
            status = "Connecting to ${payload.host}:${payload.port}"
        )
        rtc = newRtc(peerId)
        rtc?.ensureMicTrack(true)
        deviceAudioPlayer = DeviceAudioPlayer(viewModelScope)
        val ws = LocalRoomClient(URI(payload.joinUrl)) { event -> viewModelScope.launch { handleClientEvent(event) } }
        client = ws
        ws.connect()
        PlainCastActiveService.start(app, "Joined local room ${payload.roomId}", mic = true, projection = false)
    }

    fun joinManual(host: String, port: Int, roomId: String, token: String, displayName: String = defaultDeviceName()) {
        joinRoom(QrPayload(roomId = roomId, host = host, port = port, token = token), displayName)
    }

    fun setMicEnabled(enabled: Boolean) {
        rtc?.setMicEnabled(enabled)
        _room.update { it.copy(micEnabled = enabled) }
        broadcastTrackState()
    }

    fun removeParticipant(peerId: String) {
        if (!room.value.isHost || peerId == room.value.selfPeerId) return
        server?.removeParticipant(peerId)
        rtc?.removePeer(peerId)
        _room.update { current ->
            current.copy(participants = current.participants.filterNot { it.peerId == peerId })
        }
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        val current = room.value
        if (!current.isHost) {
            setStatus("Only the host can share screen in v1.")
            return
        }
        runCatching {
            rtc?.startScreenShare(resultCode, data)
            _room.update { it.copy(screenEnabled = true, status = "Screen sharing") }
            PlainCastActiveService.start(app, "Sharing screen", mic = true, projection = true)
            broadcastTrackState()
        }.onFailure { setStatus("Could not start screen share: ${it.message}") }
    }

    fun startDeviceAudio(resultCode: Int, data: Intent, projectionManager: MediaProjectionManager) {
        val current = room.value
        if (!current.isHost) {
            setStatus("Only the host can share device audio in v1.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus("Device audio requires Android 10 or newer.")
            return
        }
        val projection = runCatching { projectionManager.getMediaProjection(resultCode, data) }
            .onFailure {
                setStatus("Could not start device audio: ${it.message ?: "projection was rejected"}")
                PlainCastActiveService.start(app, activeRoomNotificationText(), mic = room.value.micEnabled, projection = room.value.screenEnabled)
            }
            .getOrNull() ?: return

        runCatching {
            activeAudioProjection = projection
            val capture = DeviceAudioCaptureController(
                scope = viewModelScope,
                onPcm = { bytes, len -> server?.broadcastDeviceAudio(bytes, len) },
                onError = ::setStatus
            )
            deviceAudioCapture = capture
            if (!capture.start(projection)) {
                deviceAudioCapture = null
                activeAudioProjection?.stop()
                activeAudioProjection = null
                PlainCastActiveService.start(app, activeRoomNotificationText(), mic = room.value.micEnabled, projection = room.value.screenEnabled)
                return@runCatching
            }
            _room.update { state ->
                state.copy(
                    deviceAudioEnabled = true,
                    status = if (state.screenEnabled) "Sharing screen and device audio" else "Sharing device audio"
                )
            }
            PlainCastActiveService.start(app, "Sharing device audio", mic = room.value.micEnabled, projection = true)
            broadcastTrackState()
        }.onFailure {
            activeAudioProjection?.stop()
            activeAudioProjection = null
            deviceAudioCapture?.stop()
            deviceAudioCapture = null
            setStatus("Could not start device audio: ${it.message ?: "capture failed"}")
            PlainCastActiveService.start(app, activeRoomNotificationText(), mic = room.value.micEnabled, projection = room.value.screenEnabled)
        }
    }

    fun stopSharing() {
        rtc?.stopScreenShare()
        deviceAudioCapture?.stop()
        deviceAudioCapture = null
        activeAudioProjection?.stop()
        activeAudioProjection = null
        _room.update { state ->
            state.copy(
                screenEnabled = false,
                deviceAudioEnabled = false,
                status = if (state.isHost) "Room ready" else state.status
            )
        }
        if (room.value.isConnected) {
            PlainCastActiveService.start(app, activeRoomNotificationText(), mic = room.value.micEnabled, projection = false)
        }
        broadcastTrackState()
    }

    fun leaveRoom() {
        stopSharing()
        client?.close()
        client = null
        server?.let { runCatching { it.stopRoom() } }
        server = null
        rtc?.dispose()
        rtc = null
        deviceAudioPlayer?.stop()
        deviceAudioPlayer = null
        PlainCastActiveService.stop(app)
        remoteVideo.set(null)
        _room.value = RoomState(displayName = defaultDeviceName(), status = "Idle")
    }

    fun showStatus(message: String) = setStatus(message)

    override fun onCleared() {
        leaveRoom()
        super.onCleared()
    }

    fun qrPayload(): QrPayload? = room.value.takeIf { it.isHost && it.isConnected }?.let {
        QrPayload(roomId = it.roomId, host = it.hostAddress, port = it.port, token = it.token)
    }

    private fun newRtc(peerId: String): PeerConnectionManager = PeerConnectionManager(
        context = app,
        selfPeerId = peerId,
        signalSender = { to, type, payload -> sendSignal(to, type, payload) },
        onRemoteVideoTrack = { _, track: VideoTrack? -> remoteVideo.set(track) },
        onError = ::setStatus,
    )

    private suspend fun handleServerEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.ParticipantJoined -> {
                _room.update { it.copy(participants = server?.participants() ?: it.participants, status = "${event.participant.displayName} joined") }
                syncPeerConnections()
            }
            is ServerEvent.ParticipantLeft -> {
                rtc?.removePeer(event.peerId)
                _room.update { current ->
                    current.copy(participants = current.participants.filterNot { it.peerId == event.peerId })
                }
            }
            is ServerEvent.SignalForHost -> handleSignal(event.envelope)
            is ServerEvent.Error -> setStatus(event.message)
        }
    }

    private suspend fun handleClientEvent(event: ClientEvent) {
        when (event) {
            ClientEvent.Open -> {
                val state = room.value
                client?.send(
                    SignalingEnvelope(
                        type = "join",
                        roomId = state.roomId,
                        from = state.selfPeerId,
                        to = "host",
                        payload = SignalJson.payload(
                            JoinPayload(
                                displayName = state.displayName,
                                deviceName = defaultDeviceName(),
                                token = state.token
                            )
                        )
                    )
                )
            }
            is ClientEvent.Signal -> handleSignal(event.envelope)
            is ClientEvent.DeviceAudio -> deviceAudioPlayer?.play(event.bytes)
            is ClientEvent.Closed -> setStatus("Disconnected: ${event.reason}")
            is ClientEvent.Error -> setStatus(event.message)
        }
    }

    private fun handleSignal(env: SignalingEnvelope) {
        when (env.type) {
            "join_accepted" -> {
                val payload = SignalJson.json.decodeFromJsonElement<com.plaincast.app.signaling.JoinAcceptedPayload>(env.payload)
                _room.update { it.copy(isConnected = true, participants = payload.participants, status = "Joined room ${it.roomId}") }
                syncPeerConnections()
            }
            "join_rejected" -> setStatus("Join rejected")
            "participants" -> {
                val payload = SignalJson.json.decodeFromJsonElement<ParticipantsPayload>(env.payload)
                _room.update { it.copy(participants = payload.participants) }
                syncPeerConnections()
            }
            "offer" -> {
                val payload = SignalJson.json.decodeFromJsonElement<SdpWire>(env.payload)
                rtc?.handleOffer(env.from, payload.sdp)
            }
            "answer" -> {
                val payload = SignalJson.json.decodeFromJsonElement<SdpWire>(env.payload)
                rtc?.handleAnswer(env.from, payload.sdp)
            }
            "ice" -> {
                val payload = SignalJson.json.decodeFromJsonElement<IcePayload>(env.payload)
                rtc?.handleIce(env.from, payload)
            }
            "track_state" -> Unit
            "participant_left" -> {
                rtc?.removePeer(env.from)
                _room.update { current -> current.copy(participants = current.participants.filterNot { it.peerId == env.from }) }
            }
            "removed" -> leaveRoom()
            "room_ended" -> leaveRoom()
        }
    }

    private fun syncPeerConnections() {
        val state = room.value
        val self = state.selfPeerId
        val manager = rtc ?: return
        state.participants
            .asSequence()
            .map { it.peerId }
            .filter { it.isNotBlank() && it != self }
            .forEach { remotePeerId ->
                val existed = manager.hasPeer(remotePeerId)
                manager.createPeer(remotePeerId)
                if (!existed && shouldCreateOffer(self, remotePeerId)) {
                    manager.createOffer(remotePeerId)
                }
            }
    }

    private fun shouldCreateOffer(selfPeerId: String, remotePeerId: String): Boolean {
        // Deterministic full-mesh rule: exactly one side creates the initial offer.
        // String comparison is stable across all peers because peer IDs are generated once per room.
        return selfPeerId > remotePeerId
    }

    private fun sendSignal(to: String, type: String, payload: kotlinx.serialization.json.JsonObject) {
        val state = room.value
        val env = SignalingEnvelope(type = type, roomId = state.roomId, from = state.selfPeerId, to = to, payload = payload)
        if (state.isHost) {
            if (to == "*" || to == "host") server?.broadcast(env) else server?.sendTo(to, env)
        } else {
            client?.send(env)
        }
    }

    private fun broadcastTrackState() {
        val state = room.value
        sendSignal(
            "*",
            "track_state",
            SignalJson.payload(com.plaincast.app.signaling.TrackStatePayload(state.micEnabled, state.screenEnabled, state.deviceAudioEnabled))
        )
    }

    private fun setStatus(message: String) {
        _room.update { it.copy(status = message) }
    }

    private fun activeRoomNotificationText(): String {
        val current = room.value
        return when {
            current.isHost && current.roomId.isNotBlank() -> "Hosting local room ${current.roomId}"
            current.roomId.isNotBlank() -> "Joined local room ${current.roomId}"
            else -> "Local room active"
        }
    }

    private fun defaultDeviceName(): String = Build.MODEL ?: "Android"
}
