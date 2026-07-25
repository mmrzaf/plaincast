package com.plaincast.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.plaincast.app.diagnostics.SharedAudioPlaybackDiagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SharedAudioPlaybackController(
    initialSettings: SharedAudioSettings,
    private val onMetrics: (SharedAudioPlaybackDiagnostics) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlainCastOpusPlayback")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val buffer = SharedAudioJitterBuffer(initialSettings)
    @Volatile private var settings = initialSettings
    @Volatile private var playbackGain = 1.0f
    @Volatile private var expectedGeneration = 0L
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var worker: Job? = null
    private var decodedFrames = 0L
    private var decodedBytes = 0L
    private var lastSequence = -1L
    @Volatile private var lastQueuedAtMs = 0L
    private var lastPlayedAtMs = 0L
    private var decoderName: String? = null
    @Volatile private var lastError: String? = null
    private var lastMetricsAtMs = 0L
    private var lastReportedError: String? = null
    private var lastReportedErrorAtMs = 0L

    init { startWorker() }

    fun configure(value: SharedAudioSettings) {
        if (settings == value) return
        settings = value
        buffer.configure(value)
        wake.trySend(Unit)
        scope.launch {
            releaseDecoderAndTrack()
            emitMetrics(force = true)
        }
    }


    fun setPlaybackGain(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        if (playbackGain == normalized) return
        playbackGain = normalized
        scope.launch {
            runCatching { audioTrack?.setVolume(normalized) }
        }
    }

    fun setPublisherGeneration(generation: Long) {
        if (expectedGeneration == generation) return
        expectedGeneration = generation
        buffer.setExpectedGeneration(generation)
        wake.trySend(Unit)
        scope.launch {
            releaseDecoderAndTrack()
            emitMetrics(force = true)
        }
    }

    fun enqueue(packet: SharedAudioPacket) {
        if (packet.generation != expectedGeneration || expectedGeneration <= 0) return
        when (buffer.offer(packet, nowUs())) {
            SharedAudioJitterBuffer.OfferResult.Accepted,
            SharedAudioJitterBuffer.OfferResult.NewStream,
            SharedAudioJitterBuffer.OfferResult.CapacityDrop -> {
                lastQueuedAtMs = System.currentTimeMillis()
                wake.trySend(Unit)
            }
            SharedAudioJitterBuffer.OfferResult.InvalidFormat -> {
                reportError("Incoming shared-audio format does not match the room configuration.")
                wake.trySend(Unit)
            }
            else -> Unit
        }
    }

    fun resetMetrics() = scope.launch {
        buffer.resetMetrics()
        decodedFrames = 0
        decodedBytes = 0
        lastSequence = -1
        lastQueuedAtMs = 0
        lastPlayedAtMs = 0
        lastError = null
        lastReportedError = null
        lastReportedErrorAtMs = 0L
        emitMetrics(force = true)
    }

    fun stop() {
        val active = worker
        worker = null
        active?.cancel()
        wake.close()
        runCatching { runBlocking { active?.join() } }
        runCatching { runBlocking { kotlinx.coroutines.withContext(dispatcher) { releaseDecoderAndTrack(); emitMetrics(force = true) } } }
        scope.cancel()
        dispatcher.close()
    }

    private fun startWorker() {
        worker = scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (isActive) {
                when (val action = buffer.next(nowUs())) {
                    SharedAudioJitterBuffer.NextAction.Idle -> {
                        wake.receiveCatching().getOrNull() ?: break
                        emitMetrics()
                    }
                    is SharedAudioJitterBuffer.NextAction.Wait -> {
                        withTimeoutOrNull((action.delayUs / 1_000L).coerceAtLeast(1L)) { wake.receiveCatching() }
                        emitMetrics()
                    }
                    is SharedAudioJitterBuffer.NextAction.SkipGap -> emitMetrics()
                    is SharedAudioJitterBuffer.NextAction.Decode -> decode(action.packet)
                }
            }
        }
    }

    private fun decode(packet: SharedAudioPacket) {
        runCatching {
            val codec = ensureDecoder(packet)
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) error("Opus decoder could not accept a shared-audio packet in time.")
            val input = codec.getInputBuffer(inputIndex) ?: error("Opus decoder returned no input buffer.")
            input.clear(); input.put(packet.payload)
            codec.queueInputBuffer(inputIndex, 0, packet.payload.size, packet.captureTimestampUs, 0)
            drainDecoder(codec)
            lastSequence = packet.sequence
        }.onFailure { error ->
            releaseDecoderAndTrack()
            reportError("Shared-audio decoding failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun ensureDecoder(packet: SharedAudioPacket): MediaCodec {
        decoder?.let { return it }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, packet.sampleRate, packet.channelCount).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(packet.channelCount, packet.sampleRate)))
                setByteBuffer("csd-1", littleEndianLong(0L))
                setByteBuffer("csd-2", littleEndianLong(OPUS_SEEK_PREROLL_NS))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SharedAudioPacket.MAX_PAYLOAD_BYTES)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            decoder = codec
            decoderName = runCatching { codec.name }.getOrNull()
            codec
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw error
        }
    }

    private fun drainDecoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var attempts = 0
        while (attempts++ < MAX_DRAIN_ATTEMPTS) {
            val index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            when {
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        writePcm(output)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureAudioTrack(codec.outputFormat)
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    private fun ensureAudioTrack(format: MediaFormat): AudioTrack {
        audioTrack?.let { return it }
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Could not calculate the shared-audio playback buffer." }
        val audioFormat = AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(min, settings.frameBytes * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Could not initialize shared-audio playback.")
        }
        return try {
            track.setVolume(playbackGain)
            track.play()
            audioTrack = track
            track
        } catch (error: Throwable) {
            runCatching { track.release() }
            throw error
        }
    }

    private fun writePcm(buffer: ByteBuffer) {
        val track = audioTrack ?: ensureAudioTrack(MediaFormat.createAudioFormat("audio/raw", settings.sampleRate, settings.channelCount))
        val totalBytes = buffer.remaining()
        while (buffer.hasRemaining()) {
            val written = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                releaseAudioTrack()
                return reportError("Shared-audio playback failed with AudioTrack code $written.")
            }
        }
        decodedFrames++
        decodedBytes += totalBytes
        lastPlayedAtMs = System.currentTimeMillis()
        emitMetrics()
    }

    private fun releaseDecoderAndTrack() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null; decoderName = null
        releaseAudioTrack()
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.stop() }; runCatching { it.release() } }
        audioTrack = null
    }

    private fun reportError(message: String) {
        lastError = message
        emitMetrics(force = true)
        val now = System.currentTimeMillis()
        if (message != lastReportedError || now - lastReportedErrorAtMs >= ERROR_REPORT_INTERVAL_MS) {
            lastReportedError = message
            lastReportedErrorAtMs = now
            onError(message)
        }
    }

    private fun emitMetrics(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMetricsAtMs < METRICS_INTERVAL_MS) return
        lastMetricsAtMs = now
        onMetrics(
            SharedAudioPlaybackDiagnostics(
                active = decoder != null || buffer.depth() > 0,
                streamId = buffer.currentStreamId(), generation = expectedGeneration,
                queueDepth = buffer.depth(), bufferedMs = buffer.bufferedMs(), targetDelayMs = settings.targetDelayMs,
                maxQueueDepth = buffer.maxDepth(), receivedPackets = buffer.receivedPackets,
                duplicatePackets = buffer.duplicatePackets, outOfOrderPackets = buffer.outOfOrderPackets,
                stalePackets = buffer.stalePackets, skippedGaps = buffer.skippedGaps,
                decodedFrames = decodedFrames, decodedBytes = decodedBytes, jitterMs = buffer.jitterMs(),
                underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) audioTrack?.underrunCount ?: 0 else 0,
                lastSequence = lastSequence, lastQueuedAtMs = lastQueuedAtMs, lastPlayedAtMs = lastPlayedAtMs,
                decoderName = decoderName, lastError = lastError,
            )
        )
    }

    private fun opusHead(channels: Int, sampleRate: Int): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(channels.toByte()).putShort(0)
        .putInt(sampleRate).putShort(0).put(0).array()
    private fun littleEndianLong(value: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }
    private fun nowUs(): Long = System.nanoTime() / 1_000L

    private companion object {
        const val INPUT_TIMEOUT_US = 5_000L
        const val OUTPUT_TIMEOUT_US = 0L
        const val MAX_DRAIN_ATTEMPTS = 16
        const val OPUS_SEEK_PREROLL_NS = 80_000_000L
        const val METRICS_INTERVAL_MS = 250L
        const val ERROR_REPORT_INTERVAL_MS = 2_000L
    }
}
