package com.plaincast.app.rtc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import com.plaincast.app.diagnostics.DiagnosticsRepository
import com.plaincast.app.audio.SharedAudioTransportMeter
import com.plaincast.app.model.RoomQualityConfig
import com.plaincast.app.signaling.IcePayload
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class PeerConnectionManager(
    private val context: Context,
    private val selfPeerId: String,
    private val signalSender: (to: String, type: String, payload: kotlinx.serialization.json.JsonObject) -> Unit,
    private val onRemoteVideoTrack: (peerId: String, track: VideoTrack?) -> Unit,
    private val onScreenCaptureStopped: () -> Unit,
    private val onError: (String) -> Unit,
    private val onSharedAudioPacket: (peerId: String, bytes: ByteArray) -> Unit,
    private val sharedAudioTransportMeter: SharedAudioTransportMeter,
    private val diagnostics: DiagnosticsRepository,
) {
    private data class PeerSlot(
        val pc: PeerConnection,
        @Volatile var audioChannel: DataChannel? = null,
        @Volatile var initializing: Boolean = true,
        @Volatile var micSender: RtpSender? = null,
        @Volatile var voiceReceiver: RtpReceiver? = null,
        @Volatile var remoteVideoReceiver: RtpReceiver? = null,
        @Volatile var screenSender: RtpSender? = null,
        @Volatile var makingOffer: Boolean = false,
        @Volatile var ignoreOffer: Boolean = false,
        @Volatile var pendingRemoteOffer: SessionDescription? = null,
        @Volatile var settingRemoteDescription: Boolean = false,
        @Volatile var negotiationQueued: Boolean = false,
        @Volatile var offerSeq: Int = 0,
        @Volatile var signalingState: String = "NEW",
        @Volatile var iceState: String = "NEW",
        @Volatile var connectionState: String = "NEW",
        @Volatile var acceptedIceCandidates: Int = 0,
        @Volatile var rejectedIceCandidates: Int = 0,
        @Volatile var lastIceRestartAtMs: Long = 0,
        @Volatile var voiceStatsBaseline: RtcVoiceStatsBaseline = RtcVoiceStatsBaseline(),
    )

    private val factory: PeerConnectionFactory = RtcEngine.factory
    private val peers = ConcurrentHashMap<String, PeerSlot>()
    private val peerCreationLock = Any()
    private val pendingIce = ConcurrentHashMap<String, BoundedPendingQueue<IceCandidate>>()
    private val recoveryRunnables = ConcurrentHashMap<String, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var disposed = false
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (disposed) return
            collectStats()
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }
    private var qualityConfig: RoomQualityConfig = RoomQualityConfig()
    private var audioPriorityActive: Boolean = false
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var micEnabled: Boolean = false
    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        mainHandler.post(statsRunnable)
    }

    private fun prepareMicTrack(): AudioTrack {
        micTrack?.let { return it }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack("mic-$selfPeerId", source).apply { setEnabled(false) }
        micSource = source
        micTrack = track
        diagnostics.setMicrophoneEnabled(false)
        return track
    }

    fun setPushToTalk(active: Boolean) {
        val track = prepareMicTrack()
        peers.forEach { (peerId, slot) ->
            if (slot.micSender == null) {
                slot.micSender = slot.pc.addTrack(track, listOf("plaincast"))
                Log.d(TAG, "Prepared microphone sender for $peerId")
            } else if (slot.micSender?.track() !== track) {
                check(slot.micSender?.setTrack(track, false) == true) {
                    "Could not attach the microphone to peer $peerId."
                }
            }
        }
        track.setEnabled(active)
        micEnabled = active
        diagnostics.setMicrophoneEnabled(active)
    }

    fun isMicrophoneCapturing(): Boolean = micEnabled && micTrack != null

    fun restartIceAll() {
        peers.values.forEach { slot ->
            runCatching { slot.pc.restartIce() }
        }
        renegotiateAll()
    }

    fun hasPeer(peerId: String): Boolean = peers.containsKey(peerId)
    fun peerIds(): Set<String> = peers.keys.toSet()

    fun removePeer(peerId: String) {
        cancelConnectionRecovery(peerId)
        peers.remove(peerId)?.let { slot ->
            slot.audioChannel?.let { channel ->
                runCatching { channel.unregisterObserver() }
                runCatching { channel.close() }
                runCatching { channel.dispose() }
            }
            runCatching { slot.pc.close() }
            slot.pc.dispose()
        }
        pendingIce.remove(peerId)
        diagnostics.removePeer(peerId)
        onRemoteVideoTrack(peerId, null)
    }

    fun setQualityConfig(config: RoomQualityConfig) {
        qualityConfig = config
        screenCapturer?.let { capturer ->
            runCatching { capturer.changeCaptureFormat(config.screenWidth, config.screenHeight, config.screenFps) }
        }
        peers.values.forEach { slot -> slot.screenSender?.let { applyScreenBitrate(it) } }
    }

    fun setAudioPriorityActive(active: Boolean) {
        if (audioPriorityActive == active) return
        audioPriorityActive = active
        peers.values.forEach { slot -> slot.screenSender?.let { applyScreenBitrate(it) } }
    }

    fun createPeer(peerId: String): PeerConnection = synchronized(peerCreationLock) {
        peers[peerId]?.let { return@synchronized it.pc }
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                peers[peerId]?.signalingState = state.name
                diagnostics.updatePeerConnectionState(peerId, signalingState = state.name)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                peers[peerId]?.iceState = state.name
                diagnostics.updatePeerConnectionState(peerId, iceState = state.name)
                Log.d(TAG, "ICE $peerId: $state")
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                peers[peerId]?.connectionState = newState.name
                diagnostics.updatePeerConnectionState(peerId, connectionState = newState.name)
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED,
                    PeerConnection.PeerConnectionState.CLOSED -> cancelConnectionRecovery(peerId)
                    PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleConnectionRecovery(peerId, CONNECTION_RECOVERY_DELAY_MS)
                    PeerConnection.PeerConnectionState.FAILED -> scheduleConnectionRecovery(peerId, 0L)
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                signalSender(peerId, "ice", com.plaincast.app.signaling.SignalJson.payload(IcePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) {
                Log.w(TAG, "Ignoring unexpected in-band data channel from $peerId: ${channel.label()}")
                channel.close()
                channel.dispose()
            }
            override fun onRenegotiationNeeded() {
                if (peers[peerId]?.initializing == true) return
                requestNegotiation(peerId)
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                handleRemoteTrack(peerId, receiver)
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                handleRemoteTrack(peerId, transceiver.receiver)
            }
            override fun onRemoveTrack(receiver: RtpReceiver) {
                val current = peers[peerId] ?: return
                when (receiver.track()) {
                    is VideoTrack -> if (current.remoteVideoReceiver === receiver) {
                        current.remoteVideoReceiver = null
                        onRemoteVideoTrack(peerId, null)
                    }
                    is AudioTrack -> if (current.voiceReceiver === receiver) current.voiceReceiver = null
                }
            }
        }) ?: error("Failed to create peer connection")
        val slot = PeerSlot(pc = pc)
        peers[peerId] = slot
        try {
            val audioChannel = pc.createDataChannel(AUDIO_CHANNEL_LABEL, DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
                negotiated = true
                id = AUDIO_CHANNEL_ID
                protocol = AUDIO_CHANNEL_PROTOCOL
            }) ?: error("Failed to create shared-audio data channel")
            slot.audioChannel = audioChannel
            registerAudioChannel(peerId, audioChannel)
            addCurrentTracks(peerId, slot)
            diagnostics.updatePeerIceCandidates(
                peerId = peerId,
                pending = pendingIce[peerId]?.size() ?: 0,
                accepted = slot.acceptedIceCandidates,
                rejected = slot.rejectedIceCandidates,
            )
            slot.initializing = false
            pc
        } catch (error: Throwable) {
            peers.remove(peerId, slot)
            slot.audioChannel?.let { channel ->
                runCatching { channel.unregisterObserver() }
                runCatching { channel.close() }
                runCatching { channel.dispose() }
            }
            runCatching { pc.close() }
            runCatching { pc.dispose() }
            throw error
        }
    }


    data class AudioSendResult(
        val deliveries: Int,
        val inactiveDrops: Int,
        val backpressureDrops: Int,
    )

    fun broadcastSharedAudioPacket(bytes: ByteArray): AudioSendResult {
        sharedAudioTransportMeter.onSubmitted()
        var deliveries = 0
        var inactive = 0
        var backpressure = 0
        peers.forEach { (_, slot) ->
            val channel = slot.audioChannel
            when {
                channel == null || channel.state() != DataChannel.State.OPEN -> inactive++
                channel.bufferedAmount() > audioBackpressureLimitBytes() -> backpressure++
                runCatching { channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true)) }.getOrDefault(false) -> deliveries++
                else -> inactive++
            }
        }
        if (deliveries > 0) sharedAudioTransportMeter.onSent(deliveries)
        if (inactive > 0) sharedAudioTransportMeter.onInactiveDrop(inactive)
        if (backpressure > 0) sharedAudioTransportMeter.onBackpressureDrop(backpressure)
        return AudioSendResult(deliveries, inactive, backpressure)
    }


    private fun audioBackpressureLimitBytes(): Long {
        val bytesForConfiguredWindow = qualityConfig.audioBitrateKbps.toLong() * 1_000L / 8L *
            qualityConfig.audioMaxBufferedMs.toLong() / 1_000L
        return bytesForConfiguredWindow.coerceIn(AUDIO_MIN_BUFFERED_BYTES, AUDIO_MAX_BUFFERED_BYTES)
    }

    private fun registerAudioChannel(peerId: String, channel: DataChannel) {
        diagnostics.updatePeerAudioChannel(peerId, channel.state().name, channel.bufferedAmount())
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                diagnostics.updatePeerAudioChannel(peerId, channel.state().name, channel.bufferedAmount())
            }

            override fun onStateChange() {
                diagnostics.updatePeerAudioChannel(peerId, channel.state().name, channel.bufferedAmount())
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                val source = buffer.data
                val bytes = ByteArray(source.remaining())
                source.get(bytes)
                sharedAudioTransportMeter.onReceived()
                onSharedAudioPacket(peerId, bytes)
            }
        })
    }

    fun createOffer(peerId: String) = requestNegotiation(peerId)

    fun requestNegotiation(peerId: String) {
        val slot = peers[peerId] ?: run {
            createPeer(peerId)
            peers[peerId]
        } ?: return
        val pc = slot.pc
        if (
            slot.makingOffer || slot.settingRemoteDescription || slot.pendingRemoteOffer != null ||
            pc.signalingState() != PeerConnection.SignalingState.STABLE
        ) {
            slot.negotiationQueued = true
            return
        }
        slot.makingOffer = true
        val seq = ++slot.offerSeq
        pc.createOffer(SimpleSdpObserver(onCreateSuccessBlock = { sdp ->
            val current = peers[peerId] ?: return@SimpleSdpObserver
            if (current.offerSeq != seq || !current.makingOffer || pc.signalingState() != PeerConnection.SignalingState.STABLE) {
                if (current.offerSeq == seq) current.makingOffer = false
                Log.d(TAG, "Dropping stale local offer for $peerId")
                drainQueuedNegotiation(peerId)
                return@SimpleSdpObserver
            }
            pc.setLocalDescription(SimpleSdpObserver(onSetSuccessBlock = {
                current.makingOffer = false
                signalSender(peerId, "offer", com.plaincast.app.signaling.SignalJson.payload(sdpPayload(sdp)))
            }, onFailureBlock = { error ->
                current.makingOffer = false
                onError(error)
                drainQueuedNegotiation(peerId)
            }), sdp)
        }, onFailureBlock = { error ->
            val current = peers[peerId] ?: return@SimpleSdpObserver
            if (current.offerSeq == seq) current.makingOffer = false
            onError(error)
            drainQueuedNegotiation(peerId)
        }), offerAnswerConstraints())
    }

    fun handleOffer(peerId: String, sdp: String) {
        val pc = createPeer(peerId)
        val slot = peers[peerId] ?: return
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)

        // A second offer can arrive while the first remote description is being installed or
        // while its answer is being created. Keep only the newest one and process it after the
        // current transaction reaches stable; concurrent setRemoteDescription calls are unsafe.
        if (slot.settingRemoteDescription) {
            slot.pendingRemoteOffer = remote
            Log.d(TAG, "Queued remote offer from $peerId while a remote description is in progress")
            return
        }

        val signalingState = pc.signalingState()
        val offerCollision = slot.makingOffer || signalingState != PeerConnection.SignalingState.STABLE
        slot.ignoreOffer = offerCollision && !isPolite(peerId)
        if (slot.ignoreOffer) {
            // Candidates generated for the ignored offer must not be applied to the
            // still-active local offer. They can otherwise poison a healthy glare recovery.
            pendingIce.remove(peerId)
            updateIceDiagnostics(peerId, slot)
            Log.d(TAG, "Ignoring colliding offer and its ICE candidates from impolite peer $peerId")
            return
        }
        slot.ignoreOffer = false

        // Cancel any in-flight local offer. Without this, a screen-share renegotiation can
        // collide with the peer's offer and WebRTC rejects the remote offer while we are in
        // have-local-offer. That is the black-screen failure shown as:
        // "Called in wrong state: have-local-offer".
        slot.offerSeq++
        slot.makingOffer = false

        when (signalingState) {
            PeerConnection.SignalingState.STABLE -> acceptRemoteOffer(peerId, remote)
            PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> rollbackAndAcceptOffer(peerId, remote)
            else -> {
                // Keep only the newest remote offer. Rapid screen start/stop operations can
                // otherwise lose a renegotiation while an earlier answer is being installed.
                slot.pendingRemoteOffer = remote
                Log.d(TAG, "Queued remote offer from $peerId while $signalingState")
            }
        }
    }

    fun handleAnswer(peerId: String, sdp: String) {
        val pc = createPeer(peerId)
        val slot = peers[peerId] ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.d(TAG, "Ignoring stale answer from $peerId while ${pc.signalingState()}")
            return
        }
        slot.ignoreOffer = false
        slot.settingRemoteDescription = true
        pc.setRemoteDescription(
            SimpleSdpObserver(onSetSuccessBlock = {
                slot.settingRemoteDescription = false
                flushPendingIce(peerId)
                drainDeferredSignaling(peerId)
            }, onFailureBlock = { error ->
                slot.settingRemoteDescription = false
                onError(error)
            }),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleIce(peerId: String, payload: IcePayload) {
        val candidate = IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
        val slot = peers[peerId]
        if (slot?.ignoreOffer == true) {
            Log.d(TAG, "Ignoring ICE candidate associated with a colliding offer from $peerId")
            return
        }
        if (
            slot == null || slot.pc.remoteDescription == null ||
            slot.settingRemoteDescription || slot.pendingRemoteOffer != null
        ) {
            val queue = pendingIce.computeIfAbsent(peerId) { BoundedPendingQueue(MAX_PENDING_ICE_PER_PEER) }
            val evicted = queue.offer(candidate)
            if (evicted) {
                peers[peerId]?.let {
                    it.rejectedIceCandidates++
                    updateIceDiagnostics(peerId, it)
                }
                Log.w(TAG, "Evicted oldest pending ICE candidate for $peerId")
            } else {
                diagnostics.updatePeerIceCandidates(
                    peerId = peerId,
                    pending = queue.size(),
                    accepted = slot?.acceptedIceCandidates ?: 0,
                    rejected = slot?.rejectedIceCandidates ?: 0,
                )
            }
            return
        }
        addRemoteIceCandidate(peerId, slot, candidate)
    }

    private fun flushPendingIce(peerId: String) {
        val slot = peers[peerId] ?: return
        if (slot.pc.remoteDescription == null) return
        val queued = pendingIce.remove(peerId)?.drain().orEmpty()
        queued.forEach { candidate -> addRemoteIceCandidate(peerId, slot, candidate) }
        updateIceDiagnostics(peerId, slot)
    }

    private fun addRemoteIceCandidate(peerId: String, slot: PeerSlot, candidate: IceCandidate) {
        val accepted = runCatching { slot.pc.addIceCandidate(candidate) }.getOrDefault(false)
        if (accepted) {
            slot.acceptedIceCandidates++
        } else {
            slot.rejectedIceCandidates++
            Log.w(TAG, "WebRTC rejected remote ICE candidate for $peerId")
        }
        updateIceDiagnostics(peerId, slot)
    }

    private fun updateIceDiagnostics(peerId: String, slot: PeerSlot) {
        diagnostics.updatePeerIceCandidates(
            peerId = peerId,
            pending = pendingIce[peerId]?.size() ?: 0,
            accepted = slot.acceptedIceCandidates,
            rejected = slot.rejectedIceCandidates,
        )
    }

    fun startScreenShare(data: Intent) {
        stopScreenShare(renegotiate = false)
        val config = qualityConfig
        val capturer = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { onScreenCaptureStopped() }
        })
        val helper = SurfaceTextureHelper.create("PlainCastScreenCapture", RtcEngine.eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, context.applicationContext, source.capturerObserver)
        capturer.startCapture(config.screenWidth, config.screenHeight, config.screenFps)
        val track = factory.createVideoTrack("screen-$selfPeerId", source).apply { setEnabled(true) }
        screenCapturer = capturer
        surfaceTextureHelper = helper
        screenSource = source
        screenTrack = track
        peers.forEach { (peerId, slot) -> attachScreenTrack(peerId, slot) }
        renegotiateAll()
    }

    fun stopScreenShare() = stopScreenShare(renegotiate = true)

    private fun stopScreenShare(renegotiate: Boolean) {
        val hadScreen = screenTrack != null || screenCapturer != null
        peers.values.forEach { slot ->
            slot.screenSender?.let { sender -> runCatching { slot.pc.removeTrack(sender) } }
            slot.screenSender = null
        }
        screenCapturer?.let { capturer ->
            runCatching { capturer.stopCapture() }
            capturer.dispose()
        }
        screenTrack?.dispose()
        screenSource?.dispose()
        surfaceTextureHelper?.dispose()
        screenCapturer = null
        screenTrack = null
        screenSource = null
        surfaceTextureHelper = null
        if (hadScreen && renegotiate) renegotiateAll()
    }

    fun dispose() {
        disposed = true
        mainHandler.removeCallbacks(statsRunnable)
        recoveryRunnables.keys.toList().forEach(::cancelConnectionRecovery)
        pendingIce.clear()
        stopScreenShare(renegotiate = false)
        peers.values.forEach { slot ->
            slot.audioChannel?.let { channel ->
                runCatching { channel.unregisterObserver() }
                runCatching { channel.close() }
                runCatching { channel.dispose() }
            }
            runCatching { slot.pc.close() }
            slot.pc.dispose()
        }
        peers.keys.toList().forEach(diagnostics::removePeer)
        peers.clear()
        releaseMicCapture()
    }

    private fun releaseMicCapture() {
        micEnabled = false
        micTrack?.setEnabled(false)
        micTrack?.dispose()
        micSource?.dispose()
        micTrack = null
        micSource = null
    }


    private fun collectStats() {
        peers.forEach { (peerId, slot) ->
            slot.pc.getStats { report ->
                if (disposed || peers[peerId] !== slot) return@getStats
                val transport = RtcStatsParser.parseTransport(report)
                diagnostics.updatePeerTransport(
                    peerId = peerId,
                    roundTripTimeMs = transport.roundTripTimeMs,
                    availableOutgoingBitrateKbps = transport.availableOutgoingBitrateKbps,
                )
            }

            slot.micSender?.let { sender ->
                slot.pc.getStats(sender) { report ->
                    if (disposed || peers[peerId] !== slot) return@getStats
                    val parsed = RtcStatsParser.parseVoiceOutbound(report, slot.voiceStatsBaseline)
                    slot.voiceStatsBaseline = parsed.baseline
                    diagnostics.updatePeerVoiceOutbound(
                        peerId = peerId,
                        packets = parsed.stats.packets,
                        bytes = parsed.stats.bytes,
                        bitrateKbps = parsed.stats.bitrateKbps,
                        roundTripTimeMs = parsed.stats.roundTripTimeMs,
                    )
                }
            }

            slot.voiceReceiver?.let { receiver ->
                slot.pc.getStats(receiver) { report ->
                    if (disposed || peers[peerId] !== slot) return@getStats
                    val inbound = RtcStatsParser.parseVoiceInbound(report)
                    diagnostics.updatePeerVoiceInbound(
                        peerId = peerId,
                        packets = inbound.packets,
                        bytes = inbound.bytes,
                        packetsLost = inbound.packetsLost,
                        jitterMs = inbound.jitterMs,
                        audioLevel = inbound.audioLevel,
                        concealedSamples = inbound.concealedSamples,
                    )
                }
            }
        }
    }

    private fun handleRemoteTrack(peerId: String, receiver: RtpReceiver) {
        when (val track = receiver.track()) {
            is VideoTrack -> {
                val slot = peers[peerId] ?: return
                if (slot.remoteVideoReceiver === receiver) return
                track.setEnabled(true)
                slot.remoteVideoReceiver = receiver
                onRemoteVideoTrack(peerId, track)
            }
            is AudioTrack -> {
                val slot = peers[peerId] ?: return
                if (slot.voiceReceiver === receiver) return
                track.setEnabled(true)
                slot.voiceReceiver = receiver
            }
        }
    }

    private fun scheduleConnectionRecovery(peerId: String, delayMs: Long) {
        cancelConnectionRecovery(peerId)
        val task = Runnable {
            recoveryRunnables.remove(peerId)
            val slot = peers[peerId] ?: return@Runnable
            val stillBroken = slot.connectionState == PeerConnection.PeerConnectionState.FAILED.name ||
                slot.connectionState == PeerConnection.PeerConnectionState.DISCONNECTED.name
            if (!stillBroken) return@Runnable
            val now = System.currentTimeMillis()
            val remainingDelay = MIN_ICE_RESTART_INTERVAL_MS - (now - slot.lastIceRestartAtMs)
            if (remainingDelay > 0L) {
                scheduleConnectionRecovery(peerId, remainingDelay)
                return@Runnable
            }
            slot.lastIceRestartAtMs = now
            Log.w(TAG, "Restarting ICE for $peerId after ${slot.connectionState}")
            runCatching { slot.pc.restartIce() }
                .onFailure { error -> Log.w(TAG, "ICE restart failed for $peerId", error) }
            requestNegotiation(peerId)
        }
        recoveryRunnables[peerId] = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelConnectionRecovery(peerId: String) {
        recoveryRunnables.remove(peerId)?.let(mainHandler::removeCallbacks)
    }

    private fun addCurrentTracks(peerId: String, slot: PeerSlot) {
        val track = prepareMicTrack()
        if (slot.micSender == null) {
            slot.micSender = slot.pc.addTrack(track, listOf("plaincast"))
            Log.d(TAG, "Prepared muted microphone sender for $peerId")
        }
        attachScreenTrack(peerId, slot)
    }

    private fun attachScreenTrack(peerId: String, slot: PeerSlot) {
        val track = screenTrack ?: return
        if (slot.screenSender == null) {
            slot.screenSender = slot.pc.addTrack(track, listOf("plaincast")).also { applyScreenBitrate(it) }
            Log.d(TAG, "Attached screen track to $peerId")
        }
    }

    private fun applyScreenBitrate(sender: RtpSender) {
        runCatching {
            val params = sender.parameters
            val maxKbps = if (audioPriorityActive) {
                minOf(qualityConfig.screenMaxBitrateKbps, AUDIO_PRIORITY_SCREEN_BITRATE_KBPS)
            } else {
                qualityConfig.screenMaxBitrateKbps
            }
            params.encodings.firstOrNull()?.maxBitrateBps = maxKbps * 1_000
            sender.setParameters(params)
        }
    }

    private fun renegotiateAll() {
        peers.keys.forEach { requestNegotiation(it) }
    }

    private fun acceptRemoteOffer(peerId: String, remote: SessionDescription) {
        val slot = peers[peerId] ?: return
        val pc = slot.pc
        slot.settingRemoteDescription = true
        pc.setRemoteDescription(SimpleSdpObserver(onSetSuccessBlock = {
            flushPendingIce(peerId)
            pc.createAnswer(SimpleSdpObserver(onCreateSuccessBlock = { answer ->
                pc.setLocalDescription(SimpleSdpObserver(onSetSuccessBlock = {
                    slot.settingRemoteDescription = false
                    signalSender(peerId, "answer", com.plaincast.app.signaling.SignalJson.payload(sdpPayload(answer)))
                    drainDeferredSignaling(peerId)
                }, onFailureBlock = { error ->
                    slot.settingRemoteDescription = false
                    onError(error)
                    drainDeferredSignaling(peerId)
                }), answer)
            }, onFailureBlock = { error ->
                slot.settingRemoteDescription = false
                onError(error)
                drainDeferredSignaling(peerId)
            }), offerAnswerConstraints())
        }, onFailureBlock = { error ->
            slot.settingRemoteDescription = false
            onError(error)
            drainDeferredSignaling(peerId)
        }), remote)
    }

    private fun rollbackAndAcceptOffer(peerId: String, remote: SessionDescription) {
        val slot = peers[peerId] ?: return
        val pc = slot.pc
        val rollback = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        pc.setLocalDescription(
            SimpleSdpObserver(
                onSetSuccessBlock = { acceptRemoteOffer(peerId, remote) },
                onFailureBlock = { error -> onError("Failed to roll back local offer: $error") }
            ),
            rollback
        )
    }

    private fun drainDeferredSignaling(peerId: String) {
        val slot = peers[peerId] ?: return
        if (slot.pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        val pendingOffer = slot.pendingRemoteOffer
        if (pendingOffer != null) {
            slot.pendingRemoteOffer = null
            handleOffer(peerId, pendingOffer.description)
            return
        }
        drainQueuedNegotiation(peerId)
    }

    private fun drainQueuedNegotiation(peerId: String) {
        val slot = peers[peerId] ?: return
        if (!slot.negotiationQueued || slot.makingOffer || slot.pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        slot.negotiationQueued = false
        requestNegotiation(peerId)
    }

    private fun isPolite(peerId: String): Boolean = selfPeerId < peerId

    private fun offerAnswerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private fun sdpPayload(sdp: SessionDescription): SdpWire = SdpWire(sdp.description, if (sdp.type == SessionDescription.Type.OFFER) "offer" else "answer")

    companion object {
        private const val TAG = "PeerConnectionManager"
        private const val STATS_INTERVAL_MS = 1_000L
        private const val MAX_PENDING_ICE_PER_PEER = 64
        private const val CONNECTION_RECOVERY_DELAY_MS = 2_500L
        private const val MIN_ICE_RESTART_INTERVAL_MS = 5_000L
        private const val AUDIO_CHANNEL_ID = 42
        private const val AUDIO_CHANNEL_LABEL = "plaincast-audio"
        private const val AUDIO_CHANNEL_PROTOCOL = "plaincast.audio.opus.v2"
        private const val AUDIO_MIN_BUFFERED_BYTES = 2L * 1024L
        private const val AUDIO_MAX_BUFFERED_BYTES = 8L * 1024L
        private const val AUDIO_PRIORITY_SCREEN_BITRATE_KBPS = 400
    }
}

@kotlinx.serialization.Serializable
data class SdpWire(val sdp: String, val kind: String)
