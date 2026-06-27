package com.plaincast.app.rtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.plaincast.app.signaling.IcePayload
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.Options
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class PeerConnectionManager(
    private val context: Context,
    private val selfPeerId: String,
    private val signalSender: (to: String, type: String, payload: kotlinx.serialization.json.JsonObject) -> Unit,
    private val onRemoteVideoTrack: (peerId: String, track: VideoTrack?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val factory: PeerConnectionFactory = RtcEngine.factory
    private val peers = mutableMapOf<String, PeerConnection>()
    private val pendingIce = mutableMapOf<String, MutableList<IceCandidate>>()
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun ensureMicTrack(enabled: Boolean = true): AudioTrack {
        micTrack?.let { it.setEnabled(enabled); return it }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack("mic-$selfPeerId", source).apply { setEnabled(enabled) }
        micSource = source
        micTrack = track
        return track
    }

    fun setMicEnabled(enabled: Boolean) {
        micTrack?.setEnabled(enabled)
    }

    fun hasPeer(peerId: String): Boolean = peers.containsKey(peerId)

    fun removePeer(peerId: String) {
        peers.remove(peerId)?.let { pc ->
            pc.close()
            pc.dispose()
        }
        pendingIce.remove(peerId)
        onRemoteVideoTrack(peerId, null)
    }

    fun createPeer(peerId: String, polite: Boolean = false): PeerConnection {
        peers[peerId]?.let { return it }
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE $peerId: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                signalSender(peerId, "ice", com.plaincast.app.signaling.SignalJson.payload(IcePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    onRemoteVideoTrack(peerId, track)
                }
            }
        }) ?: error("Failed to create peer connection")
        peers[peerId] = pc
        addCurrentTracks(pc)
        pendingIce.remove(peerId)?.forEach { pc.addIceCandidate(it) }
        return pc
    }

    fun createOffer(peerId: String) {
        val pc = createPeer(peerId)
        val constraints = offerAnswerConstraints()
        pc.createOffer(SimpleSdpObserver(onCreateSuccessBlock = { sdp ->
            pc.setLocalDescription(SimpleSdpObserver(onSetSuccessBlock = {
                signalSender(peerId, "offer", com.plaincast.app.signaling.SignalJson.payload(sdpPayload(sdp)))
            }, onFailureBlock = onError), sdp)
        }, onFailureBlock = onError), constraints)
    }

    fun handleOffer(peerId: String, sdp: String) {
        val pc = createPeer(peerId)
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(SimpleSdpObserver(onSetSuccessBlock = {
            pc.createAnswer(SimpleSdpObserver(onCreateSuccessBlock = { answer ->
                pc.setLocalDescription(SimpleSdpObserver(onSetSuccessBlock = {
                    signalSender(peerId, "answer", com.plaincast.app.signaling.SignalJson.payload(sdpPayload(answer)))
                }, onFailureBlock = onError), answer)
            }, onFailureBlock = onError), offerAnswerConstraints())
        }, onFailureBlock = onError), remote)
    }

    fun handleAnswer(peerId: String, sdp: String) {
        val pc = createPeer(peerId)
        pc.setRemoteDescription(
            SimpleSdpObserver(onFailureBlock = onError),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleIce(peerId: String, payload: IcePayload) {
        val candidate = IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
        val pc = peers[peerId]
        if (pc == null) pendingIce.getOrPut(peerId) { mutableListOf() }.add(candidate)
        else pc.addIceCandidate(candidate)
    }

    fun startScreenShare(resultCode: Int, data: Intent, width: Int = 720, height: Int = 1280, fps: Int = 20) {
        stopScreenShare()
        val capturer = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { stopScreenShare() }
        })
        val helper = SurfaceTextureHelper.create("PlainCastScreenCapture", RtcEngine.eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, context.applicationContext, source.capturerObserver)
        capturer.startCapture(width, height, fps)
        val track = factory.createVideoTrack("screen-$selfPeerId", source).apply { setEnabled(true) }
        screenCapturer = capturer
        surfaceTextureHelper = helper
        screenSource = source
        screenTrack = track
        peers.values.forEach { pc -> pc.addTrack(track, listOf("plaincast")) }
        renegotiateAll()
    }

    fun stopScreenShare() {
        val capturer = screenCapturer ?: return
        runCatching { capturer.stopCapture() }
        capturer.dispose()
        screenTrack?.dispose()
        screenSource?.dispose()
        surfaceTextureHelper?.dispose()
        screenCapturer = null
        screenTrack = null
        screenSource = null
        surfaceTextureHelper = null
        renegotiateAll()
    }

    fun dispose() {
        stopScreenShare()
        peers.values.forEach { it.close(); it.dispose() }
        peers.clear()
        micTrack?.dispose()
        micSource?.dispose()
        micTrack = null
        micSource = null
    }

    private fun addCurrentTracks(pc: PeerConnection) {
        ensureMicTrack(true).let { pc.addTrack(it, listOf("plaincast")) }
        screenTrack?.let { pc.addTrack(it, listOf("plaincast")) }
    }

    private fun renegotiateAll() {
        peers.keys.forEach { createOffer(it) }
    }

    private fun offerAnswerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private fun sdpPayload(sdp: SessionDescription): SdpWire = SdpWire(sdp.description, if (sdp.type == SessionDescription.Type.OFFER) "offer" else "answer")

    companion object { private const val TAG = "PeerConnectionManager" }
}

@kotlinx.serialization.Serializable
data class SdpWire(val sdp: String, val kind: String)
