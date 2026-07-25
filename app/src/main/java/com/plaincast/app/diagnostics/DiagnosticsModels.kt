package com.plaincast.app.diagnostics

data class DiagnosticsState(
    val sessionId: String? = null,
    val sessionStartedAtMs: Long = 0,
    val countersResetAtMs: Long = 0,
    val selfPeerId: String? = null,
    val activeAudioPublisherId: String? = null,
    val activeAudioPublisherChangedAtMs: Long = 0,
    val microphone: MicrophoneDiagnostics = MicrophoneDiagnostics(),
    val sharedAudioCapture: SharedAudioCaptureDiagnostics = SharedAudioCaptureDiagnostics(),
    val sharedAudioEncoder: SharedAudioEncoderDiagnostics = SharedAudioEncoderDiagnostics(),
    val sharedAudioTransport: SharedAudioTransportDiagnostics = SharedAudioTransportDiagnostics(),
    val sharedAudioPlayback: SharedAudioPlaybackDiagnostics = SharedAudioPlaybackDiagnostics(),
    val audioRoute: AudioRouteDiagnostics = AudioRouteDiagnostics(),
    val peers: Map<String, PeerDiagnostics> = emptyMap(),
)

data class MicrophoneDiagnostics(
    val enabled: Boolean = false, val recording: Boolean = false, val playout: Boolean = false,
    val level: Float = 0f, val rmsDbfs: Float = -120f, val peakDbfs: Float = -120f,
    val sampleRate: Int = 0, val channelCount: Int = 0, val totalSamples: Long = 0,
    val packetsSent: Long = 0, val bytesSent: Long = 0, val packetsReceived: Long = 0, val bytesReceived: Long = 0,
    val lastSampleAtMs: Long = 0, val captureError: String? = null, val playoutError: String? = null,
)

data class SharedAudioCaptureDiagnostics(
    val active: Boolean = false, val startedAtMs: Long = 0, val level: Float = 0f, val rmsDbfs: Float = -120f,
    val totalFrames: Long = 0, val totalBytes: Long = 0, val bytesPerSecond: Long = 0,
    val lastFrameAtMs: Long = 0, val lastError: String? = null,
)

data class SharedAudioEncoderDiagnostics(
    val active: Boolean = false, val codecName: String? = null, val bitrateKbps: Int = 0,
    val inputFrames: Long = 0, val inputDrops: Long = 0, val encodedPackets: Long = 0,
    val encodedBytes: Long = 0, val lastPacketAtMs: Long = 0, val lastError: String? = null,
)

data class SharedAudioTransportDiagnostics(
    val submittedPackets: Long = 0, val sentDeliveries: Long = 0, val receivedPackets: Long = 0,
    val inactiveChannelDrops: Long = 0, val backpressureDrops: Long = 0,
    val malformedPackets: Long = 0, val unauthorizedPackets: Long = 0,
    val lastSentAtMs: Long = 0, val lastReceivedAtMs: Long = 0,
)

data class SharedAudioPlaybackDiagnostics(
    val active: Boolean = false, val streamId: Long? = null, val generation: Long = 0,
    val queueDepth: Int = 0, val bufferedMs: Int = 0, val targetDelayMs: Int = 0, val maxQueueDepth: Int = 0,
    val receivedPackets: Long = 0, val duplicatePackets: Long = 0, val outOfOrderPackets: Long = 0,
    val stalePackets: Long = 0, val skippedGaps: Long = 0, val decodedFrames: Long = 0,
    val decodedBytes: Long = 0, val jitterMs: Double = 0.0, val underruns: Int = 0,
    val lastSequence: Long = -1, val lastQueuedAtMs: Long = 0, val lastPlayedAtMs: Long = 0,
    val decoderName: String? = null, val lastError: String? = null,
)

data class AudioRouteDiagnostics(
    val mode: String = "NORMAL", val bluetoothPermissionGranted: Boolean = true,
    val routeSelectionSupported: Boolean = false, val selectionMode: String = "Android default",
    val selectedCommunicationDeviceId: Int? = null, val selectedCommunicationDeviceName: String? = null,
    val availableCommunicationDevices: List<AudioRouteDevice> = emptyList(),
    val inputDevices: List<AudioRouteDevice> = emptyList(), val outputDevices: List<AudioRouteDevice> = emptyList(),
    val lastError: String? = null,
)

data class AudioRouteDevice(val id: Int, val name: String, val type: String, val isInput: Boolean, val isOutput: Boolean)

data class PeerDiagnostics(
    val peerId: String, val signalingState: String = "NEW", val iceState: String = "NEW", val connectionState: String = "NEW",
    val pendingIceCandidates: Int = 0, val acceptedIceCandidates: Int = 0, val rejectedIceCandidates: Int = 0,
    val audioChannelState: String = "CONNECTING", val audioBufferedBytes: Long = 0,
    val outboundVoicePackets: Long = 0, val outboundVoiceBytes: Long = 0, val outboundVoiceBitrateKbps: Double = 0.0,
    val inboundVoicePackets: Long = 0, val inboundVoiceBytes: Long = 0, val inboundVoicePacketsLost: Long = 0,
    val jitterMs: Double = 0.0, val roundTripTimeMs: Double = 0.0, val availableOutgoingBitrateKbps: Double = 0.0,
    val remoteVoiceLevel: Double = 0.0, val voiceConcealedSamples: Long = 0, val updatedAtMs: Long = 0,
)

enum class DiagnosticSeverity { Healthy, Warning, Failure }
data class DiagnosticFinding(val severity: DiagnosticSeverity, val title: String, val detail: String)
