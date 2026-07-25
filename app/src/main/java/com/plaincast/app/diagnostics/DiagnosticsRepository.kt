package com.plaincast.app.diagnostics

import com.plaincast.app.audio.AudioLevelMeter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DiagnosticsRepository {
    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    private val microphoneSamples = AtomicLong(0)
    private val lastMicrophoneEmissionMs = AtomicLong(0)
    private val voiceCounterLock = Any()
    private val latestRawVoiceCounters = mutableMapOf<String, RawVoiceCounters>()
    private val voiceCounterBaselines = mutableMapOf<String, RawVoiceCounters>()

    fun beginSession(sessionId: String, selfPeerId: String) {
        microphoneSamples.set(0)
        lastMicrophoneEmissionMs.set(0)
        synchronized(voiceCounterLock) {
            latestRawVoiceCounters.clear()
            voiceCounterBaselines.clear()
        }
        val now = System.currentTimeMillis()
        _state.update { current ->
            DiagnosticsState(
                sessionId = sessionId,
                sessionStartedAtMs = now,
                countersResetAtMs = now,
                selfPeerId = selfPeerId,
                audioRoute = current.audioRoute,
            )
        }
    }

    fun endSession() {
        microphoneSamples.set(0)
        lastMicrophoneEmissionMs.set(0)
        synchronized(voiceCounterLock) {
            latestRawVoiceCounters.clear()
            voiceCounterBaselines.clear()
        }
        _state.update { current -> DiagnosticsState(audioRoute = current.audioRoute) }
    }

    fun resetCounters() {
        microphoneSamples.set(0)
        val now = System.currentTimeMillis()
        synchronized(voiceCounterLock) {
            voiceCounterBaselines.clear()
            voiceCounterBaselines.putAll(latestRawVoiceCounters)
        }
        _state.update { current ->
            current.copy(
                countersResetAtMs = now,
                microphone = current.microphone.copy(
                    totalSamples = 0,
                    packetsSent = 0,
                    bytesSent = 0,
                    packetsReceived = 0,
                    bytesReceived = 0,
                    captureError = null,
                    playoutError = null,
                ),
                sharedAudioCapture = current.sharedAudioCapture.copy(
                    totalFrames = 0,
                    totalBytes = 0,
                    bytesPerSecond = 0,
                    lastError = null,
                ),
                sharedAudioPlayback = current.sharedAudioPlayback.copy(
                    maxQueueDepth = current.sharedAudioPlayback.queueDepth,
                    receivedPackets = 0,
                    duplicatePackets = 0,
                    outOfOrderPackets = 0,
                    stalePackets = 0,
                    skippedGaps = 0,
                    decodedFrames = 0,
                    decodedBytes = 0,
                    underruns = 0,
                    lastError = null,
                ),
                sharedAudioEncoder = current.sharedAudioEncoder.copy(inputFrames = 0, inputDrops = 0, encodedPackets = 0, encodedBytes = 0, lastError = null),
                sharedAudioTransport = SharedAudioTransportDiagnostics(),
                peers = current.peers.mapValues { (_, peer) ->
                    peer.copy(
                        outboundVoicePackets = 0,
                        outboundVoiceBytes = 0,
                        outboundVoiceBitrateKbps = 0.0,
                        inboundVoicePackets = 0,
                        inboundVoiceBytes = 0,
                        inboundVoicePacketsLost = 0,
                        remoteVoiceLevel = 0.0,
                        voiceConcealedSamples = 0,
                    )
                },
            )
        }
    }

    fun setActiveAudioPublisher(peerId: String?) {
        _state.update { current ->
            if (current.activeAudioPublisherId == peerId) current else current.copy(
                activeAudioPublisherId = peerId,
                activeAudioPublisherChangedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        _state.update { it.copy(microphone = it.microphone.copy(enabled = enabled)) }
    }

    fun onMicrophoneSamples(data: ByteArray, audioFormat: Int, channelCount: Int, sampleRate: Int) {
        val level = AudioLevelMeter.measure(data, audioFormat)
        val total = microphoneSamples.addAndGet(level.sampleCount.toLong())
        val now = System.currentTimeMillis()
        val previous = lastMicrophoneEmissionMs.get()
        if (now - previous < MICROPHONE_EMIT_INTERVAL_MS || !lastMicrophoneEmissionMs.compareAndSet(previous, now)) return

        _state.update {
            it.copy(
                microphone = it.microphone.copy(
                    level = level.normalized,
                    rmsDbfs = level.rmsDbfs,
                    peakDbfs = level.peakDbfs,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    totalSamples = total,
                    lastSampleAtMs = now,
                )
            )
        }
    }

    fun onMicrophoneRecordingState(recording: Boolean) {
        _state.update {
            it.copy(
                microphone = it.microphone.copy(
                    recording = recording,
                    captureError = if (recording) null else it.microphone.captureError,
                )
            )
        }
    }

    fun onAudioPlayoutState(playing: Boolean) {
        _state.update {
            it.copy(
                microphone = it.microphone.copy(
                    playout = playing,
                    playoutError = if (playing) null else it.microphone.playoutError,
                )
            )
        }
    }

    fun onMicrophoneCaptureError(message: String) {
        _state.update { it.copy(microphone = it.microphone.copy(captureError = message)) }
    }

    fun onVoicePlayoutError(message: String) {
        _state.update { it.copy(microphone = it.microphone.copy(playoutError = message)) }
    }

    fun updateSharedAudioCapture(metrics: SharedAudioCaptureDiagnostics) { _state.update { it.copy(sharedAudioCapture = metrics) } }

    fun updateSharedAudioEncoder(metrics: SharedAudioEncoderDiagnostics) { _state.update { it.copy(sharedAudioEncoder = metrics) } }

    fun updateSharedAudioTransport(metrics: SharedAudioTransportDiagnostics) { _state.update { it.copy(sharedAudioTransport = metrics) } }

    fun updateSharedAudioPlayback(metrics: SharedAudioPlaybackDiagnostics) { _state.update { it.copy(sharedAudioPlayback = metrics) } }

    fun updatePeerAudioChannel(peerId: String, state: String, bufferedBytes: Long) {
        updatePeer(peerId) { it.copy(audioChannelState = state, audioBufferedBytes = bufferedBytes, updatedAtMs = System.currentTimeMillis()) }
    }

    fun updateAudioRoute(route: AudioRouteDiagnostics) {
        _state.update { it.copy(audioRoute = route) }
    }

    fun updatePeerConnectionState(
        peerId: String,
        signalingState: String? = null,
        iceState: String? = null,
        connectionState: String? = null,
    ) {
        _state.update { current ->
            val existing = current.peers[peerId] ?: PeerDiagnostics(peerId = peerId)
            val updated = existing.copy(
                signalingState = signalingState ?: existing.signalingState,
                iceState = iceState ?: existing.iceState,
                connectionState = connectionState ?: existing.connectionState,
                updatedAtMs = System.currentTimeMillis(),
            )
            current.withPeer(updated)
        }
    }

    fun updatePeerIceCandidates(peerId: String, pending: Int, accepted: Int, rejected: Int) {
        updatePeer(peerId) {
            it.copy(
                pendingIceCandidates = pending.coerceAtLeast(0),
                acceptedIceCandidates = accepted.coerceAtLeast(0),
                rejectedIceCandidates = rejected.coerceAtLeast(0),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun updatePeerTransport(
        peerId: String,
        roundTripTimeMs: Double,
        availableOutgoingBitrateKbps: Double,
    ) {
        updatePeer(peerId) {
            it.copy(
                roundTripTimeMs = roundTripTimeMs,
                availableOutgoingBitrateKbps = availableOutgoingBitrateKbps,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun updatePeerVoiceOutbound(
        peerId: String,
        packets: Long,
        bytes: Long,
        bitrateKbps: Double,
        roundTripTimeMs: Double,
    ) {
        val (raw, baseline) = synchronized(voiceCounterLock) {
            val previous = latestRawVoiceCounters[peerId] ?: RawVoiceCounters()
            if (packets < previous.outboundPackets || bytes < previous.outboundBytes) {
                val currentBaseline = voiceCounterBaselines[peerId] ?: RawVoiceCounters()
                voiceCounterBaselines[peerId] = currentBaseline.copy(
                    outboundPackets = 0,
                    outboundBytes = 0,
                )
            }
            val updated = previous.copy(outboundPackets = packets, outboundBytes = bytes)
            latestRawVoiceCounters[peerId] = updated
            updated to (voiceCounterBaselines[peerId] ?: RawVoiceCounters())
        }
        updatePeer(peerId) {
            it.copy(
                outboundVoicePackets = (raw.outboundPackets - baseline.outboundPackets).coerceAtLeast(0),
                outboundVoiceBytes = (raw.outboundBytes - baseline.outboundBytes).coerceAtLeast(0),
                outboundVoiceBitrateKbps = bitrateKbps,
                roundTripTimeMs = maxOf(it.roundTripTimeMs, roundTripTimeMs),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun updatePeerVoiceInbound(
        peerId: String,
        packets: Long,
        bytes: Long,
        packetsLost: Long,
        jitterMs: Double,
        audioLevel: Double,
        concealedSamples: Long,
    ) {
        val (raw, baseline) = synchronized(voiceCounterLock) {
            val previous = latestRawVoiceCounters[peerId] ?: RawVoiceCounters()
            if (
                packets < previous.inboundPackets ||
                bytes < previous.inboundBytes ||
                packetsLost < previous.inboundPacketsLost ||
                concealedSamples < previous.concealedSamples
            ) {
                val currentBaseline = voiceCounterBaselines[peerId] ?: RawVoiceCounters()
                voiceCounterBaselines[peerId] = currentBaseline.copy(
                    inboundPackets = 0,
                    inboundBytes = 0,
                    inboundPacketsLost = 0,
                    concealedSamples = 0,
                )
            }
            val updated = previous.copy(
                inboundPackets = packets,
                inboundBytes = bytes,
                inboundPacketsLost = packetsLost,
                concealedSamples = concealedSamples,
            )
            latestRawVoiceCounters[peerId] = updated
            updated to (voiceCounterBaselines[peerId] ?: RawVoiceCounters())
        }
        updatePeer(peerId) {
            it.copy(
                inboundVoicePackets = (raw.inboundPackets - baseline.inboundPackets).coerceAtLeast(0),
                inboundVoiceBytes = (raw.inboundBytes - baseline.inboundBytes).coerceAtLeast(0),
                inboundVoicePacketsLost = (raw.inboundPacketsLost - baseline.inboundPacketsLost).coerceAtLeast(0),
                jitterMs = jitterMs,
                remoteVoiceLevel = audioLevel,
                voiceConcealedSamples = (raw.concealedSamples - baseline.concealedSamples).coerceAtLeast(0),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun removePeer(peerId: String) {
        synchronized(voiceCounterLock) {
            latestRawVoiceCounters.remove(peerId)
            voiceCounterBaselines.remove(peerId)
        }
        _state.update { current -> current.copy(peers = current.peers - peerId).withAggregatedVoiceCounters() }
    }

    private fun updatePeer(peerId: String, transform: (PeerDiagnostics) -> PeerDiagnostics) {
        _state.update { current ->
            val existing = current.peers[peerId] ?: PeerDiagnostics(peerId = peerId)
            current.withPeer(transform(existing)).withAggregatedVoiceCounters()
        }
    }

    private fun DiagnosticsState.withPeer(peer: PeerDiagnostics): DiagnosticsState =
        copy(peers = peers + (peer.peerId to peer))

    private fun DiagnosticsState.withAggregatedVoiceCounters(): DiagnosticsState {
        val sentPackets = peers.values.sumOf { it.outboundVoicePackets }
        val sentBytes = peers.values.sumOf { it.outboundVoiceBytes }
        val receivedPackets = peers.values.sumOf { it.inboundVoicePackets }
        val receivedBytes = peers.values.sumOf { it.inboundVoiceBytes }
        return copy(
            microphone = microphone.copy(
                packetsSent = sentPackets,
                bytesSent = sentBytes,
                packetsReceived = receivedPackets,
                bytesReceived = receivedBytes,
            )
        )
    }

    private data class RawVoiceCounters(
        val outboundPackets: Long = 0,
        val outboundBytes: Long = 0,
        val inboundPackets: Long = 0,
        val inboundBytes: Long = 0,
        val inboundPacketsLost: Long = 0,
        val concealedSamples: Long = 0,
    )

    private companion object {
        const val MICROPHONE_EMIT_INTERVAL_MS = 100L
    }
}
