package com.plaincast.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.plaincast.app.diagnostics.SharedAudioEncoderDiagnostics
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OpusEncoderController(
    private val settings: SharedAudioSettings,
    private val generation: Long,
    private val streamId: Long,
    private val onPacket: (SharedAudioPacket) -> Unit,
    private val onMetrics: (SharedAudioEncoderDiagnostics) -> Unit,
    private val onError: (String) -> Unit,
) {
    private data class InputFrame(val bytes: ByteArray, val captureTimestampUs: Long)

    private val queue = ArrayBlockingQueue<InputFrame>(MAX_PENDING_FRAMES)
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlainCastOpusEncoder")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val metricsLock = Any()
    @Volatile private var stopping = false
    private var job: Job? = null
    private var codec: MediaCodec? = null
    private var sequence = 0L
    private var inputFrames = 0L
    private var inputDrops = 0L
    private var encodedPackets = 0L
    private var encodedBytes = 0L
    private var lastPacketAtMs = 0L
    private var lastError: String? = null
    private var codecName: String? = null
    private var lastMetricsAtMs = 0L

    fun start(): Boolean {
        check(job == null) { "Opus encoder is already running." }
        val encoder = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }.getOrElse {
            return failStart("This device does not provide an Opus encoder: ${it.message ?: "codec unavailable"}")
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, settings.sampleRate, settings.channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateKbps * 1_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, settings.frameBytes * 2)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
        runCatching {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        }.onFailure { error ->
            runCatching { encoder.release() }
            return failStart("Could not start the Opus encoder: ${error.message ?: "configuration failed"}")
        }
        codec = encoder
        synchronized(metricsLock) { codecName = runCatching { encoder.name }.getOrNull() }
        stopping = false
        emitMetrics(active = true, force = true)
        job = scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val info = MediaCodec.BufferInfo()
            try {
                while (isActive && !stopping) {
                    val frame = queue.poll(10, TimeUnit.MILLISECONDS)
                    if (frame != null) queueInput(encoder, frame)
                    drainOutput(encoder, info)
                    emitMetrics(active = true)
                }
                drainOutput(encoder, info, drainAll = true)
            } catch (error: Throwable) {
                if (!stopping) fail("Opus encoding failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                codec = null
                emitMetrics(active = false, force = true)
            }
        }
        return true
    }

    fun submit(pcm: ByteArray, captureTimestampUs: Long): Boolean {
        if (stopping || job?.isActive != true || pcm.size != settings.frameBytes) return false
        synchronized(metricsLock) { inputFrames++ }
        val frame = InputFrame(pcm.copyOf(), captureTimestampUs)
        if (queue.offer(frame)) return true
        queue.poll()
        val accepted = queue.offer(frame)
        synchronized(metricsLock) { inputDrops++ }
        emitMetrics(active = true, force = true)
        return accepted
    }

    fun stop() {
        val active = job
        stopping = true
        queue.clear()
        active?.cancel()
        runCatching { runBlocking { active?.join() } }
        job = null
    }

    fun close() {
        stop()
        scope.cancel()
        dispatcher.close()
    }

    fun resetMetrics() {
        synchronized(metricsLock) {
            inputFrames = 0; inputDrops = 0; encodedPackets = 0; encodedBytes = 0; lastPacketAtMs = 0; lastError = null
        }
        emitMetrics(active = job?.isActive == true, force = true)
    }

    private fun queueInput(encoder: MediaCodec, frame: InputFrame) {
        val index = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            synchronized(metricsLock) { inputDrops++ }
            return
        }
        val input = encoder.getInputBuffer(index) ?: error("Opus encoder returned no input buffer.")
        input.clear()
        require(input.remaining() >= frame.bytes.size) { "Opus encoder input buffer is too small." }
        input.put(frame.bytes)
        encoder.queueInputBuffer(index, 0, frame.bytes.size, frame.captureTimestampUs, 0)
    }

    private fun drainOutput(encoder: MediaCodec, info: MediaCodec.BufferInfo, drainAll: Boolean = false) {
        var attempts = 0
        while (attempts++ < MAX_DRAIN_ATTEMPTS) {
            val index = encoder.dequeueOutputBuffer(info, if (drainAll) DRAIN_TIMEOUT_US else 0)
            when {
                index >= 0 -> {
                    val output = encoder.getOutputBuffer(index)
                    if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        output.get(bytes)
                        val packet = SharedAudioPacket(
                            generation = generation,
                            streamId = streamId,
                            sequence = sequence++,
                            captureTimestampUs = info.presentationTimeUs.coerceAtLeast(0),
                            sampleRate = settings.sampleRate,
                            channelCount = settings.channelCount,
                            frameMs = settings.frameMs,
                            payload = bytes,
                        )
                        onPacket(packet)
                        synchronized(metricsLock) {
                            encodedPackets++; encodedBytes += bytes.size; lastPacketAtMs = System.currentTimeMillis()
                        }
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    private fun failStart(message: String): Boolean {
        synchronized(metricsLock) { lastError = message }
        emitMetrics(active = false, force = true)
        onError(message)
        return false
    }

    private fun fail(message: String) {
        synchronized(metricsLock) { lastError = message }
        emitMetrics(active = false, force = true)
        onError(message)
    }

    private fun emitMetrics(active: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(metricsLock) {
            if (!force && now - lastMetricsAtMs < METRICS_INTERVAL_MS) return
            lastMetricsAtMs = now
            SharedAudioEncoderDiagnostics(
                active = active,
                codecName = codecName,
                bitrateKbps = settings.bitrateKbps,
                inputFrames = inputFrames,
                inputDrops = inputDrops,
                encodedPackets = encodedPackets,
                encodedBytes = encodedBytes,
                lastPacketAtMs = lastPacketAtMs,
                lastError = lastError,
            )
        }
        onMetrics(snapshot)
    }

    private companion object {
        const val MAX_PENDING_FRAMES = 2
        const val INPUT_TIMEOUT_US = 5_000L
        const val DRAIN_TIMEOUT_US = 2_000L
        const val MAX_DRAIN_ATTEMPTS = 16
        const val METRICS_INTERVAL_MS = 250L
    }
}
