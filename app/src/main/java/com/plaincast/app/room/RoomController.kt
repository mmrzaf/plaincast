package com.plaincast.app.room

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.plaincast.app.audio.AudioRouteManager
import com.plaincast.app.audio.SharedAudioConfig
import com.plaincast.app.audio.SharedAudioCaptureController
import com.plaincast.app.audio.SharedAudioPacketCodec
import com.plaincast.app.audio.SharedAudioPlaybackController
import com.plaincast.app.audio.SharedAudioTransportMeter
import com.plaincast.app.audio.OpusEncoderController
import com.plaincast.app.audio.SourceAudioDucker
import com.plaincast.app.diagnostics.DiagnosticsRepository
import com.plaincast.app.model.ConnectionHealth
import com.plaincast.app.model.DEFAULT_PORT
import com.plaincast.app.model.DEFAULT_WEB_PORT
import com.plaincast.app.model.MediaLifecycle
import com.plaincast.app.model.Participant
import com.plaincast.app.model.Role
import com.plaincast.app.model.RoomLifecycle
import com.plaincast.app.model.RoomQualityConfig
import com.plaincast.app.model.RoomState
import com.plaincast.app.model.randomId
import com.plaincast.app.model.randomJoinToken
import com.plaincast.app.model.randomRoomId
import com.plaincast.app.network.LocalIpResolver
import com.plaincast.app.network.NetworkMonitor
import com.plaincast.app.network.NearbyRoomAdvertiser
import com.plaincast.app.qr.QrPayload
import com.plaincast.app.rtc.PeerConnectionManager
import com.plaincast.app.rtc.RemoteVideoSink
import com.plaincast.app.rtc.SdpWire
import com.plaincast.app.signaling.ClientEvent
import com.plaincast.app.signaling.IcePayload
import com.plaincast.app.signaling.JoinAcceptedPayload
import com.plaincast.app.signaling.JoinPayload
import com.plaincast.app.signaling.JoinRejectedPayload
import com.plaincast.app.signaling.LocalRoomClient
import com.plaincast.app.signaling.LocalRoomServer
import com.plaincast.app.signaling.AudioPublishRejectedPayload
import com.plaincast.app.signaling.AudioPublishRequestPayload
import com.plaincast.app.signaling.AudioPublisherChangedPayload
import com.plaincast.app.signaling.ParticipantsPayload
import com.plaincast.app.signaling.RoomConfig
import com.plaincast.app.signaling.RoomConfigPayload
import com.plaincast.app.signaling.ServerEvent
import com.plaincast.app.signaling.ShareStatePayload
import com.plaincast.app.signaling.SignalJson
import com.plaincast.app.signaling.SignalingEnvelope
import com.plaincast.app.signaling.TrackStatePayload
import com.plaincast.app.web.BrowserHttpServer
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.webrtc.VideoTrack

data class ForegroundNeeds(
    val roomActive: Boolean,
    val microphone: Boolean,
    val projection: Boolean,
    val notificationText: String,
)

class RoomController(
    context: Context,
    private val diagnostics: DiagnosticsRepository,
    private val audioRouteManager: AudioRouteManager,
    private val onForegroundNeedsChanged: (ForegroundNeeds) -> Unit,
) {
    private val appContext = context.applicationContext
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlainCastRoomController")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val projectionHandler = Handler(Looper.getMainLooper())
    private val _room = MutableStateFlow(RoomState(displayName = defaultDeviceName()))
    val room: StateFlow<RoomState> = _room.asStateFlow()
    val remoteVideo = RemoteVideoSink()

    private val remoteVideoTracks = mutableMapOf<String, VideoTrack>()
    private val sharedAudioTransportMeter = SharedAudioTransportMeter(diagnostics::updateSharedAudioTransport)
    private val networkMonitor = NetworkMonitor(appContext) { available -> scope.launch { handleNetworkChanged(available) } }
    private val nearbyAdvertiser = NearbyRoomAdvertiser(appContext)
    private val sourceAudioDucker = SourceAudioDucker(appContext)

    private var server: LocalRoomServer? = null
    private var browserServer: BrowserHttpServer? = null
    private var client: LocalRoomClient? = null
    private var rtc: PeerConnectionManager? = null
    private var sharedAudioPlayback: SharedAudioPlaybackController? = null
    private var sharedAudioCapture: SharedAudioCaptureController? = null
    private var sharedAudioEncoder: OpusEncoderController? = null
    private var localAudioPipelineGeneration: Long? = null
    private var activeAudioProjection: MediaProjection? = null
    private var audioProjectionCallback: MediaProjection.Callback? = null
    private var wantsAudioPublishing = false
    private var connectionTimeoutJob: Job? = null
    private var leaving = false
    private var microphoneForegroundRequired = false
    @Volatile private var pushToTalkRequested = false
    private var communicationRouteReleaseJob: Job? = null
    private var audioTransportWatchdogJob: Job? = null
    private var signalingServerReady = false
    private var browserServerReady = false

    fun createRoom(displayName: String = defaultDeviceName()) = submit {
        prepareNewSession()
        transitionRoom(RoomLifecycle.Creating)
        val ip = LocalIpResolver.bestLocalIpv4(appContext) ?: return@submit failRoom("Could not find a local Wi-Fi or hotspot address.")
        val roomId = randomRoomId()
        val joinToken = randomJoinToken()
        val peerId = randomId("host")
        val cleanName = displayName.ifBlank { defaultDeviceName() }
        val initialQuality = RoomQualityConfig()
        val initialConfig = RoomConfig(qualityConfig = initialQuality)
        diagnostics.beginSession(roomId, peerId)
        diagnostics.setActiveAudioPublisher(null)
        sharedAudioTransportMeter.reset()
        networkMonitor.start()
        _room.value = RoomState(
            roomId = roomId,
            hostAddress = ip,
            port = DEFAULT_PORT,
            webPort = DEFAULT_WEB_PORT,
            joinToken = joinToken,
            selfPeerId = peerId,
            displayName = cleanName,
            isHost = true,
            lifecycle = RoomLifecycle.Creating,
            qualityConfig = initialQuality,
            connectionHealth = ConnectionHealth.Connecting,
            status = "Starting room on $ip:$DEFAULT_PORT",
            participants = listOf(Participant(peerId, cleanName, Role.HOST)),
        )
        rtc = newRtc(peerId).also { it.setQualityConfig(room.value.qualityConfig) }
        sharedAudioPlayback = newAudioPlayer(room.value.qualityConfig)
        val localServer = LocalRoomServer(
            roomId = roomId,
            port = DEFAULT_PORT,
            hostPeerId = peerId,
            hostName = cleanName,
            joinToken = joinToken,
            initialRoomConfig = initialConfig,
            onEvent = ::onServerEvent,
        )
        server = localServer
        signalingServerReady = false
        browserServerReady = false
        val localBrowserServer = BrowserHttpServer(
            context = appContext,
            port = DEFAULT_WEB_PORT,
            onStarted = { scope.launch { browserServerReady = true; finishHostStartupIfReady() } },
            onError = { message -> scope.launch { failRoom(message) } },
        )
        browserServer = localBrowserServer
        runCatching {
            localBrowserServer.start()
            localServer.start()
        }.onFailure {
            failRoom("Could not start local room: ${it.message ?: "server failed"}")
            return@submit
        }
        connectionTimeoutJob = scope.launch {
            delay(SERVER_START_TIMEOUT_MS)
            if (room.value.lifecycle == RoomLifecycle.Creating) {
                failRoom("Could not start PlainCast on ports $DEFAULT_PORT and $DEFAULT_WEB_PORT.")
            }
        }
        publishForegroundNeeds()
    }

    fun joinRoom(payload: QrPayload, displayName: String = defaultDeviceName()) = submit {
        prepareNewSession()
        val peerId = randomId("peer")
        val cleanName = displayName.ifBlank { defaultDeviceName() }
        diagnostics.beginSession(payload.roomId, peerId)
        diagnostics.setActiveAudioPublisher(null)
        sharedAudioTransportMeter.reset()
        networkMonitor.start()
        _room.value = RoomState(
            roomId = payload.roomId,
            hostAddress = payload.host,
            port = payload.port,
            joinToken = payload.token,
            selfPeerId = peerId,
            displayName = cleanName,
            isHost = false,
            lifecycle = RoomLifecycle.Joining,
            connectionHealth = ConnectionHealth.Connecting,
            status = "Connecting to ${payload.host}:${payload.port}",
        )
        rtc = newRtc(peerId).also { it.setQualityConfig(room.value.qualityConfig) }
        sharedAudioPlayback = newAudioPlayer(room.value.qualityConfig)
        client = LocalRoomClient(URI(payload.joinUrl), scope, ::onClientEvent).also { it.start() }
        connectionTimeoutJob = scope.launch {
            delay(JOIN_TIMEOUT_MS)
            if (room.value.lifecycle in setOf(RoomLifecycle.Joining, RoomLifecycle.Reconnecting)) {
                failRoom("Could not join. Check that both phones use the same Wi-Fi or hotspot.")
            }
        }
        publishForegroundNeeds()
    }

    fun joinManual(host: String, port: Int, roomId: String, token: String, displayName: String = defaultDeviceName()) {
        val payload = runCatching { QrPayload(roomId = roomId.trim().uppercase(), host = host.trim(), port = port, token = token.trim().lowercase()) }
            .getOrElse { showStatus(it.message ?: "Invalid room details."); return }
        joinRoom(payload, displayName)
    }

    fun setPushToTalk(active: Boolean) {
        pushToTalkRequested = active
        submit {
            if (active && !pushToTalkRequested) return@submit
            if (active && !room.value.isConnected) return@submit
            if (active && room.value.microphoneState in setOf(MediaLifecycle.Starting, MediaLifecycle.Live)) return@submit
            if (!active && room.value.microphoneState == MediaLifecycle.Stopped) return@submit
            if (active) {
                val manager = rtc ?: return@submit setStatus("Voice is not ready.")
                setMicrophoneState(MediaLifecycle.Starting)
                microphoneForegroundRequired = true
                keepCommunicationRouteActive()
                publishForegroundNeeds()
                runCatching { manager.setPushToTalk(true) }
                    .onSuccess {
                        if (!pushToTalkRequested) {
                            runCatching { manager.setPushToTalk(false) }
                            setMicrophoneState(MediaLifecycle.Stopped)
                            _room.update { current ->
                                current.copy(
                                    participants = current.participants.updateParticipant(current.selfPeerId) { it.copy(mic = false) },
                                    status = connectedStatus(current),
                                )
                            }
                            scheduleCommunicationRouteRelease()
                            publishForegroundNeeds()
                            broadcastTrackState()
                            return@onSuccess
                        }
                        setMicrophoneState(MediaLifecycle.Live)
                        _room.update { current ->
                            current.copy(
                                participants = current.participants.updateParticipant(current.selfPeerId) { it.copy(mic = true) },
                                status = "Transmitting voice",
                            )
                        }
                        syncCommunicationMode()
                        broadcastTrackState()
                    }
                    .onFailure { error ->
                        runCatching { manager.setPushToTalk(false) }
                        microphoneForegroundRequired = false
                        setMicrophoneState(MediaLifecycle.Failed)
                        _room.update { current ->
                            current.copy(
                                participants = current.participants.updateParticipant(current.selfPeerId) { it.copy(mic = false) },
                                status = "Could not start microphone: ${error.message ?: "capture failed"}",
                            )
                        }
                        scheduleCommunicationRouteRelease()
                        publishForegroundNeeds()
                        broadcastTrackState()
                    }
            } else {
                pushToTalkRequested = false
                runCatching { rtc?.setPushToTalk(false) }
                if (room.value.microphoneState != MediaLifecycle.Stopped) setMicrophoneState(MediaLifecycle.Stopped)
                _room.update { current ->
                    current.copy(
                        participants = current.participants.updateParticipant(current.selfPeerId) { it.copy(mic = false) },
                        status = connectedStatus(current),
                    )
                }
                scheduleCommunicationRouteRelease()
                publishForegroundNeeds()
                broadcastTrackState()
            }
        }
    }

    fun startScreenShare(data: Intent) = submit {
        if (!room.value.isConnected) return@submit setStatus("Join or create a room first.")
        setScreenState(MediaLifecycle.Starting)
        setStatus("Starting video sharing…")
        runCatching { rtc?.startScreenShare(data) ?: error("WebRTC is not ready") }
            .onSuccess {
                setScreenState(MediaLifecycle.Live)
                _room.update { state ->
                    state.copy(
                        activeScreenSharerId = state.selfPeerId,
                        status = if (state.audioSharingEnabled) "Sharing audio and video" else "Sharing video",
                        participants = state.participants.updateParticipant(state.selfPeerId) { it.copy(screen = true) },
                    )
                }
                server?.setActiveScreenPeer(room.value.selfPeerId)
                announceScreenShare("screen_share_started", true)
                publishForegroundNeeds()
            }
            .onFailure { error ->
                setScreenState(MediaLifecycle.Failed)
                setStatus("Could not start video sharing: ${error.message ?: "capture failed"}")
                publishForegroundNeeds()
            }
    }

    fun startAudioShare(projection: MediaProjection) = submit {
        if (!room.value.isConnected) {
            runCatching { projection.stop() }
            setStatus("Join or create a room first.")
            publishForegroundNeeds()
            return@submit
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching { projection.stop() }
            setStatus("Shared audio requires Android 10 or newer.")
            publishForegroundNeeds()
            return@submit
        }
        val currentPublisher = room.value.activeAudioPublisherId
        if (currentPublisher != null && currentPublisher != room.value.selfPeerId) {
            runCatching { projection.stop() }
            val name = room.value.participantName(currentPublisher) ?: "Another participant"
            setStatus("$name is already sharing audio. They must stop before another device can share.")
            publishForegroundNeeds()
            return@submit
        }

        stopLocalAudioPipeline(releaseProjection = true)
        setAudioShareState(MediaLifecycle.Starting)
        setStatus("Starting audio sharing…")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch {
                    if (activeAudioProjection === projection) {
                        activeAudioProjection = null
                        audioProjectionCallback = null
                        wantsAudioPublishing = false
                        stopLocalAudioPipeline(releaseProjection = false)
                        requestAudioPublisher(active = false)
                        setStatus("Audio capture was stopped by Android.")
                    }
                }
            }
        }
        projection.registerCallback(callback, projectionHandler)
        activeAudioProjection = projection
        audioProjectionCallback = callback
        wantsAudioPublishing = true
        val state = room.value
        if (state.activeAudioPublisherId == state.selfPeerId && state.audioGeneration > 0) {
            startLocalAudioPipeline(state.audioGeneration)
        } else {
            requestAudioPublisher(active = true)
        }
        publishForegroundNeeds()
    }

    fun audioShareStartFailed(message: String) = submit {
        wantsAudioPublishing = false
        if (room.value.audioShareState != MediaLifecycle.Failed) setAudioShareState(MediaLifecycle.Failed)
        setStatus("Could not start audio sharing: $message")
        publishForegroundNeeds()
    }

    fun stopSharing() = submit {
        stopLocalScreen(announce = true, updateStatus = false)
        if (room.value.audioShareState != MediaLifecycle.Stopped || wantsAudioPublishing) {
            wantsAudioPublishing = false
            stopLocalAudioPipeline(releaseProjection = true)
            requestAudioPublisher(active = false)
        }
        setStatus(connectedStatus(room.value))
        publishForegroundNeeds()
    }

    fun removeParticipant(peerId: String) = submit {
        if (!room.value.isHost) return@submit
        server?.removeParticipant(peerId)
    }





    fun stopActiveAudioPublisher() = submit {
        if (!room.value.isHost) return@submit
        if (room.value.activeAudioPublisherId == room.value.selfPeerId) {
            wantsAudioPublishing = false
            stopLocalAudioPipeline(releaseProjection = true)
        }
        server?.stopActiveAudioPublisher()
    }



    fun stopAudioSharing() = submit {
        if (room.value.audioShareState != MediaLifecycle.Stopped || wantsAudioPublishing) {
            wantsAudioPublishing = false
            stopLocalAudioPipeline(releaseProjection = true)
            requestAudioPublisher(active = false)
            setStatus(connectedStatus(room.value))
        }
    }

    fun stopScreenSharing() = submit {
        stopLocalScreen(announce = true, updateStatus = true)
    }

    fun selectCommunicationRoute(deviceId: Int) = submit {
        audioRouteManager.selectCommunicationDevice(deviceId)
            .onFailure { setStatus(it.message ?: "Could not select that audio route.") }
    }
    fun clearCommunicationRoute() = submit {
        audioRouteManager.clearCommunicationDevice()
            .onSuccess { setStatus("Using automatic audio routing.") }
            .onFailure { setStatus(it.message ?: "Could not restore automatic audio routing.") }
    }
    fun refreshAudioRoutes() = submit { audioRouteManager.refresh() }
    fun resetDiagnostics() = submit {
        diagnostics.resetCounters()
        sharedAudioCapture?.resetMetrics()
        sharedAudioEncoder?.resetMetrics()
        sharedAudioPlayback?.resetMetrics()
        sharedAudioTransportMeter.reset()
    }
    fun showStatus(message: String) = submit { setStatus(message) }
    fun leaveRoom(status: String = "Idle") = submit { leaveInternal(status) }

    fun close() {
        runCatching { runBlocking(dispatcher) { leaveInternal("Idle") } }
        sourceAudioDucker.close()
        scope.cancel()
        dispatcher.close()
    }

    private fun prepareNewSession() {
        if (room.value.lifecycle != RoomLifecycle.Idle || server != null || browserServer != null || client != null || rtc != null) leaveInternal("Idle")
    }

    private fun leaveInternal(finalStatus: String) {
        if (leaving) return
        leaving = true
        val lifecycle = room.value.lifecycle
        if (lifecycle !in setOf(RoomLifecycle.Idle, RoomLifecycle.Leaving)) runCatching { transitionRoom(RoomLifecycle.Leaving) }
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        pushToTalkRequested = false
        runCatching { rtc?.setPushToTalk(false) }
        stopLocalScreen(announce = lifecycle == RoomLifecycle.Connected, updateStatus = false)
        wantsAudioPublishing = false
        stopLocalAudioPipeline(releaseProjection = true)
        if (lifecycle == RoomLifecycle.Connected) requestAudioPublisher(active = false)
        client?.close(); client = null
        server?.let { runCatching { it.stopRoom() } }; server = null
        browserServer?.close(); browserServer = null
        nearbyAdvertiser.close()
        signalingServerReady = false
        browserServerReady = false
        rtc?.dispose(); rtc = null
        sharedAudioPlayback?.stop(); sharedAudioPlayback = null
        networkMonitor.stop()
        releaseCommunicationRouteNow()
        sourceAudioDucker.setDucked(false)
        diagnostics.endSession()
        remoteVideoTracks.clear()
        remoteVideo.set(null)
        microphoneForegroundRequired = false
        val displayName = room.value.displayName.ifBlank { defaultDeviceName() }
        _room.value = RoomState(displayName = displayName, status = finalStatus)
        leaving = false
        publishForegroundNeeds()
    }

    private fun onServerEvent(event: ServerEvent) = scope.launch { handleServerEvent(event) }.let { Unit }
    private fun onClientEvent(event: ClientEvent) = scope.launch { handleClientEvent(event) }.let { Unit }

    private fun finishHostStartupIfReady() {
        if (room.value.lifecycle != RoomLifecycle.Creating || !signalingServerReady || !browserServerReady) return
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        transitionRoom(RoomLifecycle.Connected)
        _room.update {
            it.copy(
                connectionHealth = ConnectionHealth.Stable,
                status = "Room ready · browser link on ${it.hostAddress}:${it.webPort}",
            )
        }
        publishNearbyRoom()
        publishForegroundNeeds()
    }

    private fun handleServerEvent(event: ServerEvent) {
        when (event) {
            ServerEvent.Started -> if (room.value.lifecycle == RoomLifecycle.Creating) {
                signalingServerReady = true
                finishHostStartupIfReady()
            }
            is ServerEvent.ParticipantJoined -> {
                _room.update { it.copy(participants = server?.participants() ?: it.participants, status = "${event.participant.displayName} joined") }
                syncPeerConnections(); syncCommunicationMode()
            }
            is ServerEvent.ParticipantLeft -> {
                rtc?.removePeer(event.peerId)
                remoteVideoTracks.remove(event.peerId)
                _room.update { current ->
                    current.copy(
                        participants = current.participants.filterNot { it.peerId == event.peerId },
                        activeAudioPublisherId = current.activeAudioPublisherId.takeUnless { it == event.peerId },
                        activeScreenSharerId = current.activeScreenSharerId.takeUnless { it == event.peerId },
                        status = "Participant left",
                    )
                }
                refreshRemoteVideo()
                syncCommunicationMode()
            }
            is ServerEvent.AudioAuthorityChanged -> {
                _room.update {
                    it.copy(
                        activeAudioPublisherId = event.snapshot.activePeerId,
                        audioGeneration = event.snapshot.generation,
                    )
                }
            }
            is ServerEvent.SignalForHost -> handleSignal(event.envelope)
            is ServerEvent.Error -> if (room.value.lifecycle == RoomLifecycle.Creating) failRoom("Could not start local room: ${event.message}") else setStatus("Room server error: ${event.message}")
        }
    }

    private fun handleClientEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.Connecting -> if (event.attempt > 0) setStatus("Reconnecting… attempt ${event.attempt}")
            ClientEvent.Open -> sendJoin()
            is ClientEvent.Signal -> handleSignal(event.envelope)
            is ClientEvent.Reconnecting -> {
                if (room.value.lifecycle in setOf(RoomLifecycle.Connected, RoomLifecycle.Joining)) transitionRoom(RoomLifecycle.Reconnecting)
                _room.update { it.copy(connectionHealth = ConnectionHealth.Reconnecting, reconnectAttempt = event.attempt, status = "Reconnecting… attempt ${event.attempt}") }
            }
            is ClientEvent.Error -> if (room.value.lifecycle != RoomLifecycle.Failed) setStatus("Network: ${event.message}")
        }
    }

    private fun sendJoin() {
        val state = room.value
        client?.send(
            SignalingEnvelope(
                type = "join", roomId = state.roomId, from = state.selfPeerId, to = "host",
                payload = SignalJson.payload(JoinPayload(state.joinToken, state.displayName, defaultDeviceName())),
            )
        )
    }

    private fun handleSignal(envelope: SignalingEnvelope) {
        when (envelope.type) {
            "join_accepted" -> {
                val payload = SignalJson.json.decodeFromJsonElement<JoinAcceptedPayload>(envelope.payload)
                val reconnecting = room.value.lifecycle == RoomLifecycle.Reconnecting
                connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                if (room.value.lifecycle != RoomLifecycle.Connected) transitionRoom(RoomLifecycle.Connected)
                _room.update {
                    it.copy(
                        participants = payload.participants,
                        activeAudioPublisherId = payload.activeAudioPublisherId,
                        audioGeneration = payload.audioGeneration,
                        activeScreenSharerId = payload.activeScreenSharerId,
                        qualityConfig = payload.roomConfig.qualityConfig,
                        connectionHealth = ConnectionHealth.Stable,
                        reconnectAttempt = 0,
                        status = if (reconnecting) "Reconnected" else "Joined local room ${it.roomId}",
                    )
                }
                diagnostics.setActiveAudioPublisher(payload.activeAudioPublisherId)
                applyQualityConfig(payload.roomConfig.qualityConfig)
                sharedAudioPlayback?.setPublisherGeneration(payload.audioGeneration)
                syncPeerConnections(); syncCommunicationMode(); refreshRemoteVideo()
                if (reconnecting) rtc?.restartIceAll()
                if (wantsAudioPublishing && activeAudioProjection != null) {
                    if (
                        payload.activeAudioPublisherId == room.value.selfPeerId &&
                        localAudioPipelineGeneration != payload.audioGeneration
                    ) {
                        startLocalAudioPipeline(payload.audioGeneration)
                    } else if (payload.activeAudioPublisherId != room.value.selfPeerId) {
                        requestAudioPublisher(true)
                    }
                }
                broadcastTrackState()
            }
            "join_rejected" -> {
                val payload = runCatching { SignalJson.json.decodeFromJsonElement<JoinRejectedPayload>(envelope.payload) }.getOrNull()
                failRoom(when (payload?.reason) {
                    "room_full" -> "This room is full."
                    "rate_limited" -> "Too many join attempts. Try again shortly."
                    "unauthorized" -> "This invitation is invalid or expired."
                    else -> "Join rejected${payload?.reason?.let { ": $it" } ?: ""}"
                })
            }
            "participants" -> {
                val payload = SignalJson.json.decodeFromJsonElement<ParticipantsPayload>(envelope.payload)
                _room.update {
                    it.copy(
                        participants = payload.participants,
                        activeAudioPublisherId = payload.activeAudioPublisherId,
                        audioGeneration = payload.audioGeneration,
                        activeScreenSharerId = payload.activeScreenSharerId,
                        qualityConfig = payload.roomConfig.qualityConfig,
                    )
                }
                diagnostics.setActiveAudioPublisher(payload.activeAudioPublisherId)
                sharedAudioPlayback?.setPublisherGeneration(payload.audioGeneration)
                applyQualityConfig(payload.roomConfig.qualityConfig)
                syncPeerConnections(); syncCommunicationMode(); refreshRemoteVideo()
                if (
                    wantsAudioPublishing && activeAudioProjection != null &&
                    payload.activeAudioPublisherId == room.value.selfPeerId &&
                    localAudioPipelineGeneration != payload.audioGeneration
                ) {
                    startLocalAudioPipeline(payload.audioGeneration)
                }
            }
            "audio_publish_rejected" -> handleAudioPublishRejected(envelope)
            "audio_publisher_changed" -> handleAudioPublisherChanged(envelope)
            "offer" -> rtc?.handleOffer(envelope.from, SignalJson.json.decodeFromJsonElement<SdpWire>(envelope.payload).sdp)
            "answer" -> rtc?.handleAnswer(envelope.from, SignalJson.json.decodeFromJsonElement<SdpWire>(envelope.payload).sdp)
            "ice" -> rtc?.handleIce(envelope.from, SignalJson.json.decodeFromJsonElement<IcePayload>(envelope.payload))
            "renegotiate_request" -> rtc?.createOffer(envelope.from)
            "room_config" -> handleRoomConfig(envelope)
            "track_state" -> handleTrackState(envelope)
            "screen_share_started", "screen_share_stopped" -> handleScreenShareState(envelope)
            "participant_left" -> {
                rtc?.removePeer(envelope.from)
                remoteVideoTracks.remove(envelope.from)
                _room.update { current ->
                    current.copy(
                        participants = current.participants.filterNot { it.peerId == envelope.from },
                        activeScreenSharerId = current.activeScreenSharerId.takeUnless { it == envelope.from },
                    )
                }
                refreshRemoteVideo()
                syncCommunicationMode()
            }
            "removed" -> leaveInternal("Removed by host")
            "room_ended" -> leaveInternal("Host ended the room")
            "pong" -> Unit
        }
    }



    private fun handleAudioPublishRejected(envelope: SignalingEnvelope) {
        val payload = runCatching {
            SignalJson.json.decodeFromJsonElement<AudioPublishRejectedPayload>(envelope.payload)
        }.getOrNull() ?: return
        if (!wantsAudioPublishing && room.value.audioShareState != MediaLifecycle.Starting) return
        wantsAudioPublishing = false
        stopLocalAudioPipeline(releaseProjection = true)
        if (room.value.audioShareState != MediaLifecycle.Failed) setAudioShareState(MediaLifecycle.Failed)
        val name = payload.displayName ?: payload.activePublisherPeerId?.let(room.value::participantName) ?: "Another participant"
        setStatus(if (payload.reason == "publisher_busy") "$name is already sharing audio. They must stop first." else "Could not start audio sharing.")
        publishForegroundNeeds()
    }

    private fun handleAudioPublisherChanged(envelope: SignalingEnvelope) {
        val payload = runCatching { SignalJson.json.decodeFromJsonElement<AudioPublisherChangedPayload>(envelope.payload) }.getOrNull() ?: return
        val self = room.value.selfPeerId
        val becameLocalPublisher = payload.publisherPeerId == self
        val lostLocalPublisher = payload.previousPublisherPeerId == self && payload.publisherPeerId != self
        _room.update { current ->
            current.copy(
                activeAudioPublisherId = payload.publisherPeerId,
                audioGeneration = payload.generation,
                participants = current.participants.map { it.copy(audio = it.peerId == payload.publisherPeerId) },
                status = when {
                    payload.publisherPeerId == self -> "Sharing audio"
                    payload.publisherPeerId != null -> "${payload.displayName ?: "Someone"} is sharing audio"
                    else -> "Audio sharing stopped"
                },
            )
        }
        diagnostics.setActiveAudioPublisher(payload.publisherPeerId)
        sharedAudioPlayback?.setPublisherGeneration(payload.generation)
        if (lostLocalPublisher) {
            wantsAudioPublishing = false
            stopLocalAudioPipeline(releaseProjection = true)
        }
        if (becameLocalPublisher && wantsAudioPublishing && localAudioPipelineGeneration != payload.generation) {
            startLocalAudioPipeline(payload.generation)
        }
        syncCommunicationMode()
        publishForegroundNeeds()
    }

    private fun startLocalAudioPipeline(generation: Long) {
        if (
            localAudioPipelineGeneration == generation &&
            sharedAudioCapture?.isRunning == true &&
            sharedAudioEncoder != null
        ) return
        val projection = activeAudioProjection ?: return run {
            wantsAudioPublishing = false
            setAudioShareState(MediaLifecycle.Failed)
            setStatus("Audio capture permission is no longer available.")
            requestAudioPublisher(false)
        }
        stopLocalAudioPipeline(releaseProjection = false)
        if (room.value.audioShareState != MediaLifecycle.Starting) setAudioShareState(MediaLifecycle.Starting)
        val settings = SharedAudioConfig.settingsFor(room.value.qualityConfig)
        val streamId = System.nanoTime().coerceAtLeast(1L)
        val firstDeliveryObserved = AtomicBoolean(false)
        val encoder = OpusEncoderController(
            settings = settings,
            generation = generation,
            streamId = streamId,
            onPacket = { packet ->
                val result = rtc?.broadcastSharedAudioPacket(SharedAudioPacketCodec.encode(packet))
                if ((result?.deliveries ?: 0) > 0 && firstDeliveryObserved.compareAndSet(false, true)) {
                    scope.launch { markLocalAudioTransportLive() }
                }
            },
            onMetrics = diagnostics::updateSharedAudioEncoder,
            onError = { message -> scope.launch { handleAudioPipelineFailure(message) } },
        )
        if (!encoder.start()) {
            encoder.close()
            wantsAudioPublishing = false
            setAudioShareState(MediaLifecycle.Failed)
            requestAudioPublisher(false)
            return
        }
        val capture = SharedAudioCaptureController(
            context = appContext,
            settings = settings,
            onFrame = { frame -> encoder.submit(frame, System.nanoTime() / 1_000L) },
            onMetrics = diagnostics::updateSharedAudioCapture,
            onError = { message -> scope.launch { handleAudioPipelineFailure(message) } },
        )
        if (!capture.start(projection)) {
            capture.close(); encoder.close()
            wantsAudioPublishing = false
            setAudioShareState(MediaLifecycle.Failed)
            requestAudioPublisher(false)
            return
        }
        sharedAudioEncoder = encoder
        sharedAudioCapture = capture
        localAudioPipelineGeneration = generation
        _room.update { current ->
            current.copy(
                participants = current.participants.map { it.copy(audio = it.peerId == current.selfPeerId) },
                status = if (current.participants.size <= 1) "Audio ready · waiting for participants" else "Audio captured · connecting media…",
            )
        }
        audioTransportWatchdogJob?.cancel()
        audioTransportWatchdogJob = scope.launch {
            delay(AUDIO_TRANSPORT_READY_TIMEOUT_MS)
            if (
                room.value.audioShareState == MediaLifecycle.Starting &&
                room.value.activeAudioPublisherId == room.value.selfPeerId
            ) {
                setStatus(
                    if (room.value.participants.size <= 1) "Audio ready · waiting for participants"
                    else "Audio captured, but the media connection is not ready. Open Diagnostics."
                )
            }
        }
        publishForegroundNeeds()
    }

    private fun markLocalAudioTransportLive() {
        if (room.value.audioShareState != MediaLifecycle.Starting) return
        audioTransportWatchdogJob?.cancel()
        audioTransportWatchdogJob = null
        setAudioShareState(MediaLifecycle.Live)
        _room.update { current ->
            current.copy(status = if (current.screenEnabled) "Sharing audio and video" else "Sharing audio")
        }
        publishForegroundNeeds()
    }

    private fun handleAudioPipelineFailure(message: String) {
        wantsAudioPublishing = false
        stopLocalAudioPipeline(releaseProjection = true)
        requestAudioPublisher(false)
        if (room.value.audioShareState != MediaLifecycle.Failed) setAudioShareState(MediaLifecycle.Failed)
        setStatus(message)
        publishForegroundNeeds()
    }

    private fun handleIncomingAudio(peerId: String, bytes: ByteArray) {
        val packet = SharedAudioPacketCodec.decode(bytes).getOrElse {
            sharedAudioTransportMeter.onMalformed(); return
        }
        val state = room.value
        if (peerId != state.activeAudioPublisherId || packet.generation != state.audioGeneration) {
            sharedAudioTransportMeter.onUnauthorized(); return
        }
        sharedAudioPlayback?.enqueue(packet)
    }

    private fun handleRoomConfig(envelope: SignalingEnvelope) {
        val payload = runCatching { SignalJson.json.decodeFromJsonElement<RoomConfigPayload>(envelope.payload) }.getOrNull() ?: return
        _room.update { it.copy(qualityConfig = payload.config.qualityConfig, status = "Room settings updated") }
        applyQualityConfig(payload.config.qualityConfig)
    }

    private fun handleTrackState(envelope: SignalingEnvelope) {
        val payload = runCatching { SignalJson.json.decodeFromJsonElement<TrackStatePayload>(envelope.payload) }.getOrNull() ?: return
        _room.update { current -> current.copy(participants = current.participants.updateParticipant(envelope.from) { it.copy(mic = payload.mic) }) }
        syncCommunicationMode()
    }

    private fun handleScreenShareState(envelope: SignalingEnvelope) {
        val payload = runCatching { SignalJson.json.decodeFromJsonElement<ShareStatePayload>(envelope.payload) }.getOrNull() ?: return
        val self = room.value.selfPeerId
        when (envelope.type) {
            "screen_share_started" -> {
                if (payload.peerId != self && room.value.screenEnabled) stopLocalScreen(false, false, false)
                _room.update { current ->
                    current.copy(
                        activeScreenSharerId = payload.peerId,
                        participants = current.participants.map { it.copy(screen = it.peerId == payload.peerId) },
                        status = if (payload.peerId == self) "Sharing video" else "${payload.displayName} is sharing video",
                    )
                }
                if (room.value.isHost) server?.setActiveScreenPeer(payload.peerId)
            }
            "screen_share_stopped" -> _room.update { current ->
                current.copy(
                    activeScreenSharerId = current.activeScreenSharerId.takeUnless { it == payload.peerId },
                    participants = current.participants.updateParticipant(payload.peerId) { it.copy(screen = false) },
                    status = "Video sharing stopped",
                )
            }
        }
        refreshRemoteVideo()
    }

    private fun updateRemoteVideoTrack(peerId: String, track: VideoTrack?) {
        if (track == null) remoteVideoTracks.remove(peerId) else remoteVideoTracks[peerId] = track
        refreshRemoteVideo()
    }

    private fun refreshRemoteVideo() {
        remoteVideo.set(room.value.activeScreenSharerId?.let(remoteVideoTracks::get))
    }

    private fun syncPeerConnections() {
        val state = room.value
        val manager = rtc ?: return
        val remoteParticipants = state.participants.filter { it.peerId.isNotBlank() && it.peerId != state.selfPeerId }
        val remoteIds = remoteParticipants.map { it.peerId }.toSet()
        manager.peerIds().filterNot { it in remoteIds }.forEach(manager::removePeer)
        remoteParticipants.forEach { participant ->
            val existed = manager.hasPeer(participant.peerId)
            manager.createPeer(participant.peerId)
            if (!existed && state.selfPeerId > participant.peerId) manager.createOffer(participant.peerId)
        }
    }

    private fun stopLocalScreen(announce: Boolean, updateStatus: Boolean, releaseServerAuthority: Boolean = true) {
        val wasSharing = room.value.screenState != MediaLifecycle.Stopped
        rtc?.stopScreenShare()
        if (wasSharing) {
            setScreenState(MediaLifecycle.Stopped)
            _room.update { state ->
                state.copy(
                    activeScreenSharerId = state.activeScreenSharerId.takeUnless { it == state.selfPeerId },
                    participants = state.participants.updateParticipant(state.selfPeerId) { it.copy(screen = false) },
                )
            }
            if (releaseServerAuthority) server?.setActiveScreenPeer(null)
            if (announce) announceScreenShare("screen_share_stopped", false)
            if (updateStatus) setStatus("Video sharing stopped")
        }
        publishForegroundNeeds()
    }

    private fun stopLocalAudioPipeline(releaseProjection: Boolean) {
        audioTransportWatchdogJob?.cancel()
        audioTransportWatchdogJob = null
        sharedAudioCapture?.close(); sharedAudioCapture = null
        sharedAudioEncoder?.close(); sharedAudioEncoder = null
        localAudioPipelineGeneration = null
        sharedAudioTransportMeter.flush()
        if (room.value.audioShareState != MediaLifecycle.Stopped) setAudioShareState(MediaLifecycle.Stopped)
        _room.update { state -> state.copy(participants = state.participants.updateParticipant(state.selfPeerId) { it.copy(audio = false) }) }
        if (releaseProjection) {
            val projection = activeAudioProjection
            val callback = audioProjectionCallback
            activeAudioProjection = null
            audioProjectionCallback = null
            if (projection != null && callback != null) runCatching { projection.unregisterCallback(callback) }
            runCatching { projection?.stop() }
        }
        publishForegroundNeeds()
    }

    private fun requestAudioPublisher(active: Boolean) {
        val state = room.value
        if (state.isHost) {
            server?.requestAudioPublisher(if (active) state.selfPeerId else null)
        } else {
            client?.send(
                SignalingEnvelope(
                    type = "audio_publish_request", roomId = state.roomId, from = state.selfPeerId, to = "host",
                    payload = SignalJson.payload(AudioPublishRequestPayload(active)),
                )
            )
        }
    }

    private fun announceScreenShare(type: String, active: Boolean) {
        val state = room.value
        val envelope = SignalingEnvelope(
            type = type, roomId = state.roomId, from = state.selfPeerId,
            payload = SignalJson.payload(ShareStatePayload(state.selfPeerId, state.displayName, active)),
        )
        if (state.isHost) server?.broadcast(envelope) else client?.send(envelope)
    }

    private fun sendSignal(to: String, type: String, payload: JsonObject) {
        val state = room.value
        val envelope = SignalingEnvelope(type = type, roomId = state.roomId, from = state.selfPeerId, to = to, payload = payload)
        if (state.isHost) {
            if (to == "*" || to == "host") server?.broadcast(envelope) else server?.sendTo(to, envelope)
        } else client?.send(envelope)
    }

    private fun broadcastTrackState() {
        val state = room.value
        server?.updateHostMicrophoneState(state.micEnabled)
        sendSignal("*", "track_state", SignalJson.payload(TrackStatePayload(state.micEnabled)))
    }

    private fun applyQualityConfig(config: RoomQualityConfig) {
        rtc?.setQualityConfig(config)
        sharedAudioPlayback?.configure(SharedAudioConfig.settingsFor(config))
    }

    private fun newRtc(peerId: String): PeerConnectionManager = PeerConnectionManager(
        context = appContext,
        selfPeerId = peerId,
        signalSender = { to, type, payload -> scope.launch { sendSignal(to, type, payload) } },
        onRemoteVideoTrack = { peerId, track: VideoTrack? ->
            scope.launch { updateRemoteVideoTrack(peerId, track) }
        },
        onScreenCaptureStopped = { scope.launch { stopLocalScreen(true, true) } },
        onError = { message -> scope.launch { setStatus(message) } },
        onSharedAudioPacket = ::handleIncomingAudio,
        sharedAudioTransportMeter = sharedAudioTransportMeter,
        diagnostics = diagnostics,
    )

    private fun newAudioPlayer(config: RoomQualityConfig): SharedAudioPlaybackController = SharedAudioPlaybackController(
        initialSettings = SharedAudioConfig.settingsFor(config),
        onMetrics = diagnostics::updateSharedAudioPlayback,
        onError = { message -> scope.launch { setStatus(message) } },
    )

    private fun handleNetworkChanged(available: Boolean) {
        if (room.value.isHost) {
            if (!available) {
                if (room.value.isConnected) _room.update { it.copy(connectionHealth = ConnectionHealth.Poor, status = "Local network unavailable") }
                return
            }
            val currentAddress = LocalIpResolver.bestLocalIpv4(appContext)
            if (currentAddress == null) _room.update { it.copy(connectionHealth = ConnectionHealth.Poor, status = "No reachable local address") }
            else _room.update { state ->
                state.copy(
                    hostAddress = currentAddress,
                    connectionHealth = ConnectionHealth.Stable,
                    status = if (state.hostAddress == currentAddress) connectedStatus(state) else "Network changed. New room address: $currentAddress:${state.port}",
                )
            }
            publishNearbyRoom()
            publishForegroundNeeds(); return
        }
        if (available) client?.reconnectNow()
        else if (room.value.lifecycle == RoomLifecycle.Connected) {
            transitionRoom(RoomLifecycle.Reconnecting)
            _room.update { it.copy(connectionHealth = ConnectionHealth.Reconnecting, status = "Network changed; reconnecting…") }
        }
    }

    private fun failRoom(message: String) {
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        pushToTalkRequested = false
        runCatching { rtc?.setPushToTalk(false) }
        stopLocalScreen(false, false)
        wantsAudioPublishing = false
        stopLocalAudioPipeline(releaseProjection = true)
        client?.close(); client = null
        server?.let { runCatching { it.stopRoom() } }; server = null
        browserServer?.close(); browserServer = null
        nearbyAdvertiser.close()
        signalingServerReady = false
        browserServerReady = false
        rtc?.dispose(); rtc = null
        sharedAudioPlayback?.stop(); sharedAudioPlayback = null
        networkMonitor.stop()
        releaseCommunicationRouteNow()
        sourceAudioDucker.setDucked(false)
        diagnostics.endSession()
        remoteVideoTracks.clear()
        remoteVideo.set(null)
        microphoneForegroundRequired = false
        _room.update {
            it.copy(
                lifecycle = RoomLifecycle.Failed,
                connectionHealth = ConnectionHealth.Disconnected,
                microphoneState = MediaLifecycle.Stopped,
                screenState = MediaLifecycle.Stopped,
                audioShareState = MediaLifecycle.Stopped,
                status = message,
            )
        }
        publishForegroundNeeds()
    }

    private fun transitionRoom(target: RoomLifecycle) {
        RoomStateMachine.requireRoomTransition(room.value.lifecycle, target)
        _room.update { it.copy(lifecycle = target) }
    }
    private fun setMicrophoneState(target: MediaLifecycle) {
        RoomStateMachine.requireMediaTransition(room.value.microphoneState, target)
        _room.update { it.copy(microphoneState = target) }
    }
    private fun setScreenState(target: MediaLifecycle) {
        RoomStateMachine.requireMediaTransition(room.value.screenState, target)
        _room.update { it.copy(screenState = target) }
    }
    private fun setAudioShareState(target: MediaLifecycle) {
        RoomStateMachine.requireMediaTransition(room.value.audioShareState, target)
        _room.update { it.copy(audioShareState = target) }
    }

    private fun syncCommunicationMode() {
        val state = room.value
        val talking = state.participants.any { it.mic }
        if (talking) keepCommunicationRouteActive() else scheduleCommunicationRouteRelease()
        rtc?.setAudioPriorityActive(talking || state.activeAudioPublisherId != null)
        sharedAudioPlayback?.setPlaybackGain(if (talking) VOICE_DUCK_GAIN else 1.0f)
        sourceAudioDucker.setDucked(talking && state.activeAudioPublisherId == state.selfPeerId)
    }

    private fun keepCommunicationRouteActive() {
        communicationRouteReleaseJob?.cancel()
        communicationRouteReleaseJob = null
        audioRouteManager.activateCommunicationMode()
    }

    private fun scheduleCommunicationRouteRelease() {
        communicationRouteReleaseJob?.cancel()
        communicationRouteReleaseJob = scope.launch {
            delay(COMMUNICATION_ROUTE_GRACE_MS)
            if (!room.value.participants.any { it.mic }) audioRouteManager.deactivateCommunicationMode()
        }
    }

    private fun releaseCommunicationRouteNow() {
        communicationRouteReleaseJob?.cancel()
        communicationRouteReleaseJob = null
        audioRouteManager.deactivateCommunicationMode()
    }

    private fun publishForegroundNeeds() {
        val state = room.value
        val active = state.lifecycle !in setOf(RoomLifecycle.Idle, RoomLifecycle.Failed)
        onForegroundNeedsChanged(
            ForegroundNeeds(
                roomActive = active,
                microphone = active && microphoneForegroundRequired,
                projection = active && (state.screenState in setOf(MediaLifecycle.Starting, MediaLifecycle.Live) || activeAudioProjection != null),
                notificationText = when {
                    state.audioSharingEnabled && state.screenEnabled -> "Sharing audio and video"
                    state.audioSharingEnabled -> "Sharing audio"
                    state.screenEnabled -> "Sharing video"
                    state.lifecycle == RoomLifecycle.Reconnecting -> "Reconnecting to room ${state.roomId}"
                    state.isHost -> "Hosting local room ${state.roomId}"
                    else -> "Joined local room ${state.roomId}"
                },
            )
        )
    }

    private fun connectedStatus(state: RoomState): String = when {
        state.activeAudioPublisherId != null -> "${state.participantName(state.activeAudioPublisherId) ?: "Someone"} is sharing audio"
        state.activeScreenSharerId != null -> "${state.participantName(state.activeScreenSharerId) ?: "Someone"} is sharing video"
        state.isHost -> "Room ready"
        else -> "Connected"
    }

    private fun publishNearbyRoom() {
        val state = room.value
        if (!state.isHost || !state.isConnected || state.hostAddress.isBlank()) {
            nearbyAdvertiser.close()
            return
        }
        nearbyAdvertiser.publish(
            NearbyRoomAdvertiser.Advertisement(
                roomId = state.roomId,
                port = state.port,
                webPort = state.webPort,
                token = state.joinToken,
                hostName = state.displayName,
            )
        )
    }

    private fun currentRoomConfig(): RoomConfig = RoomConfig(qualityConfig = room.value.qualityConfig)

    private fun setStatus(message: String) { _room.update { it.copy(status = message) } }
    private fun submit(block: () -> Unit) { scope.launch { runCatching(block).onFailure { setStatus(it.message ?: "PlainCast operation failed") } } }
    private fun defaultDeviceName(): String = Build.MODEL ?: "Android"
    private fun List<Participant>.updateParticipant(peerId: String, transform: (Participant) -> Participant): List<Participant> =
        map { if (it.peerId == peerId) transform(it) else it }

    private companion object {
        const val AUDIO_TRANSPORT_READY_TIMEOUT_MS = 4_000L
        const val JOIN_TIMEOUT_MS = 12_000L
        const val SERVER_START_TIMEOUT_MS = 5_000L
        const val VOICE_DUCK_GAIN = 0.45f
        const val COMMUNICATION_ROUTE_GRACE_MS = 1_500L
    }
}
