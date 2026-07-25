package com.plaincast.app.diagnostics

object DiagnosticsAnalyzer {
    fun analyze(state: DiagnosticsState, nowMs: Long = System.currentTimeMillis()): List<DiagnosticFinding> {
        if (state.sessionId == null) return listOf(DiagnosticFinding(DiagnosticSeverity.Warning, "No active room", "Create or join a room before running media diagnostics."))
        val findings = mutableListOf<DiagnosticFinding>()
        val mic = state.microphone
        val sessionAge = (nowMs - state.sessionStartedAtMs).coerceAtLeast(0)
        val counterAge = (nowMs - maxOf(state.sessionStartedAtMs, state.countersResetAtMs)).coerceAtLeast(0)

        mic.captureError?.let { findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Microphone capture failed", it) }
        mic.playoutError?.let { findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Voice playout failed", it) }
        state.audioRoute.lastError?.let { findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Audio route failed", it) }
        if (mic.enabled) {
            when {
                !mic.recording && sessionAge >= STARTUP_GRACE_MS -> findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Microphone capture is not running", "The WebRTC microphone input did not start.")
                mic.recording && age(nowMs, mic.lastSampleAtMs) > SAMPLE_STALE_MS -> findings += DiagnosticFinding(DiagnosticSeverity.Failure, "No microphone samples", "AudioRecord is running, but no microphone samples reached PlainCast.")
                mic.recording && mic.rmsDbfs <= SILENCE_DBFS -> findings += DiagnosticFinding(DiagnosticSeverity.Warning, "Microphone input is silent", "Verify the selected input and speak close to it.")
            }
            if (mic.recording && mic.packetsSent == 0L && state.peers.isNotEmpty() && counterAge >= RTP_GRACE_MS) {
                findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Microphone samples are not becoming RTP", "Local microphone samples exist, but WebRTC reports no outbound voice packets.")
            }
        }
        if (mic.packetsReceived > 0 && !mic.playout) findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Inbound voice is not playing", "Voice RTP arrives, but WebRTC playout is stopped.")

        val capture = state.sharedAudioCapture
        val encoder = state.sharedAudioEncoder
        val transport = state.sharedAudioTransport
        val playback = state.sharedAudioPlayback
        capture.lastError?.let { findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Audio capture failed", it) }
        encoder.lastError?.let { findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Opus encoder failed", it) }
        playback.lastError?.let { findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Audio playback failed", it) }
        if (capture.active && age(nowMs, capture.lastFrameAtMs) > AUDIO_STALE_MS) {
            findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Audio capture stalled", "Playback capture is active, but PCM frames stopped arriving.")
        }
        if (capture.totalFrames > 0 && encoder.encodedPackets == 0L && sessionAge >= ENCODER_GRACE_MS) {
            findings += DiagnosticFinding(DiagnosticSeverity.Failure, "PCM is not becoming Opus", "Captured audio reaches PlainCast, but the Opus encoder emits no packets.")
        }
        if (encoder.encodedPackets > 0 && transport.sentDeliveries == 0L && state.peers.isNotEmpty()) {
            findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Opus packets are not leaving", "The encoder emits packets, but no WebRTC audio channel accepts them.")
        }
        if (transport.backpressureDrops > 0) findings += DiagnosticFinding(DiagnosticSeverity.Warning, "Audio congestion detected", "Late audio packets are being dropped to keep latency bounded.")
        if (transport.unauthorizedPackets > 0) findings += DiagnosticFinding(DiagnosticSeverity.Warning, "Rejected stale publisher packets", "Packets from an inactive publisher or old generation were discarded.")
        if (transport.receivedPackets > 0 && playback.receivedPackets == 0L) {
            findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Received audio is not entering playback", "WebRTC receives Opus packets, but the authorized playback buffer accepts none.")
        }
        if (playback.receivedPackets > 0 && playback.decodedFrames == 0L && age(nowMs, playback.lastQueuedAtMs) > PLAYBACK_GRACE_MS) {
            findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Opus packets are not decoding", "Authorized packets are buffered, but the decoder emits no PCM.")
        }
        if (playback.stalePackets > 0 || playback.skippedGaps > 0) findings += DiagnosticFinding(DiagnosticSeverity.Warning, "Shared-audio network is unstable", "Late or missing packets were discarded rather than replayed.")

        state.peers.values.forEach { peer ->
            when {
                peer.connectionState in FAILED_CONNECTION_STATES || peer.iceState in FAILED_ICE_STATES -> findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Peer connection failed", "Peer ${peer.peerId.takeLast(8)} is ${peer.connectionState}; ICE is ${peer.iceState}.")
                peer.connectionState in TRANSIENT_CONNECTION_STATES || peer.iceState in TRANSIENT_ICE_STATES -> findings += DiagnosticFinding(DiagnosticSeverity.Warning, "Peer connection is interrupted", "Peer ${peer.peerId.takeLast(8)} is reconnecting.")
                peer.rejectedIceCandidates > 0 && peer.acceptedIceCandidates == 0 -> findings += DiagnosticFinding(DiagnosticSeverity.Failure, "ICE candidates were rejected", "Peer ${peer.peerId.takeLast(8)} received candidates, but WebRTC accepted none.")
                peer.pendingIceCandidates > 0 && peer.signalingState == "STABLE" -> findings += DiagnosticFinding(DiagnosticSeverity.Warning, "ICE candidates are still pending", "Peer ${peer.peerId.takeLast(8)} has ${peer.pendingIceCandidates} candidates waiting for a remote description.")
                peer.audioChannelState == "CLOSED" -> findings += DiagnosticFinding(DiagnosticSeverity.Failure, "Audio channel closed", "Peer ${peer.peerId.takeLast(8)} cannot receive or send shared audio.")
                peer.roundTripTimeMs >= HIGH_RTT_MS || peer.jitterMs >= HIGH_JITTER_MS -> findings += DiagnosticFinding(DiagnosticSeverity.Warning, "Peer network is unstable", "Peer ${peer.peerId.takeLast(8)}: ${peer.roundTripTimeMs.toInt()} ms RTT, ${peer.jitterMs.toInt()} ms voice jitter.")
            }
        }
        if (findings.isEmpty()) findings += DiagnosticFinding(DiagnosticSeverity.Healthy, "Media paths are healthy", "Voice RTP and Opus shared-audio paths show no current fault.")
        return findings
    }

    private fun age(now: Long, timestamp: Long): Long = if (timestamp <= 0) Long.MAX_VALUE else (now - timestamp).coerceAtLeast(0)
    private const val STARTUP_GRACE_MS = 2_000L
    private const val RTP_GRACE_MS = 3_000L
    private const val ENCODER_GRACE_MS = 2_000L
    private const val PLAYBACK_GRACE_MS = 2_000L
    private const val SAMPLE_STALE_MS = 1_500L
    private const val AUDIO_STALE_MS = 1_500L
    private const val SILENCE_DBFS = -65f
    private const val HIGH_RTT_MS = 250.0
    private const val HIGH_JITTER_MS = 60.0
    private val FAILED_CONNECTION_STATES = setOf("FAILED", "CLOSED")
    private val FAILED_ICE_STATES = setOf("FAILED", "CLOSED")
    private val TRANSIENT_CONNECTION_STATES = setOf("DISCONNECTED")
    private val TRANSIENT_ICE_STATES = setOf("DISCONNECTED")
}
