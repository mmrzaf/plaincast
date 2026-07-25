package com.plaincast.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAnalyzerTest {
    @Test fun identifiesMicrophoneCaptureThatNeverStarted() {
        val findings = DiagnosticsAnalyzer.analyze(activeState(microphone = MicrophoneDiagnostics(enabled = true)), 10_000)
        assertTrue(findings.any { it.title == "Microphone capture is not running" })
    }

    @Test fun identifiesSignalThatIsNotBecomingRtp() {
        val findings = DiagnosticsAnalyzer.analyze(
            activeState(
                microphone = MicrophoneDiagnostics(enabled = true, recording = true, rmsDbfs = -18f, lastSampleAtMs = 9_900),
                peers = mapOf("peer-b" to PeerDiagnostics("peer-b", connectionState = "CONNECTED")),
            ), 10_000,
        )
        assertTrue(findings.any { it.title == "Microphone samples are not becoming RTP" })
    }

    @Test fun identifiesCapturedSharedAudioThatIsNotEncoded() {
        val findings = DiagnosticsAnalyzer.analyze(
            activeState(sharedAudioCapture = SharedAudioCaptureDiagnostics(active = true, startedAtMs = 2_000, totalFrames = 100, lastFrameAtMs = 9_900)), 10_000,
        )
        assertTrue(findings.any { it.title == "PCM is not becoming Opus" })
    }

    @Test fun identifiesEncodedSharedAudioThatCannotLeave() {
        val findings = DiagnosticsAnalyzer.analyze(
            activeState(
                sharedAudioEncoder = SharedAudioEncoderDiagnostics(active = true, encodedPackets = 10),
                peers = mapOf("peer-b" to PeerDiagnostics("peer-b", connectionState = "CONNECTED")),
            ), 10_000,
        )
        assertTrue(findings.any { it.title == "Opus packets are not leaving" })
    }

    @Test fun identifiesIncomingPacketsThatAreNotDecoded() {
        val findings = DiagnosticsAnalyzer.analyze(
            activeState(
                sharedAudioTransport = SharedAudioTransportDiagnostics(receivedPackets = 10),
                sharedAudioPlayback = SharedAudioPlaybackDiagnostics(receivedPackets = 10, lastQueuedAtMs = 7_000),
            ), 10_000,
        )
        assertTrue(findings.any { it.title == "Opus packets are not decoding" })
    }

    @Test fun reportsDisconnectedPeerAsInterruptedNotFailed() {
        val findings = DiagnosticsAnalyzer.analyze(
            activeState(peers = mapOf("peer-b" to PeerDiagnostics("peer-b", iceState = "DISCONNECTED", connectionState = "DISCONNECTED"))), 10_000,
        )
        assertTrue(findings.any { it.severity == DiagnosticSeverity.Warning && it.title == "Peer connection is interrupted" })
    }

    @Test fun reportsHealthyWhenObservedPathsAreHealthy() {
        val findings = DiagnosticsAnalyzer.analyze(
            activeState(
                microphone = MicrophoneDiagnostics(enabled = true, recording = true, playout = true, rmsDbfs = -18f, lastSampleAtMs = 9_900, packetsSent = 100, packetsReceived = 90),
                peers = mapOf("peer-b" to PeerDiagnostics("peer-b", signalingState = "STABLE", iceState = "CONNECTED", connectionState = "CONNECTED", audioChannelState = "OPEN", outboundVoicePackets = 100, inboundVoicePackets = 90, roundTripTimeMs = 30.0, jitterMs = 4.0)),
            ), 10_000,
        )
        assertEquals(1, findings.size)
        assertEquals(DiagnosticSeverity.Healthy, findings.single().severity)
    }

    private fun activeState(
        microphone: MicrophoneDiagnostics = MicrophoneDiagnostics(),
        sharedAudioCapture: SharedAudioCaptureDiagnostics = SharedAudioCaptureDiagnostics(),
        sharedAudioEncoder: SharedAudioEncoderDiagnostics = SharedAudioEncoderDiagnostics(),
        sharedAudioTransport: SharedAudioTransportDiagnostics = SharedAudioTransportDiagnostics(),
        sharedAudioPlayback: SharedAudioPlaybackDiagnostics = SharedAudioPlaybackDiagnostics(),
        peers: Map<String, PeerDiagnostics> = emptyMap(),
    ) = DiagnosticsState(
        sessionId = "session",
        sessionStartedAtMs = 1_000,
        selfPeerId = "peer-a",
        microphone = microphone,
        sharedAudioCapture = sharedAudioCapture,
        sharedAudioEncoder = sharedAudioEncoder,
        sharedAudioTransport = sharedAudioTransport,
        sharedAudioPlayback = sharedAudioPlayback,
        peers = peers,
    )
}
