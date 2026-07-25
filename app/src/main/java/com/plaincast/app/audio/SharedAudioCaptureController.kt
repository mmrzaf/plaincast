package com.plaincast.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.plaincast.app.diagnostics.SharedAudioCaptureDiagnostics
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SharedAudioCaptureController(
    context: Context,
    private val settings: SharedAudioSettings,
    private val onFrame: (ByteArray) -> Unit,
    private val onMetrics: (SharedAudioCaptureDiagnostics) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlainCastPlaybackCapture")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycleLock = Any()
    private val metricsLock = Any()

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var stopping = false
    private var job: Job? = null

    private var totalFrames = 0L
    private var totalBytes = 0L
    private var windowBytes = 0L
    private var captureStartedAtMs = 0L
    private var windowStartedAtMs = 0L
    private var lastMetricsAtMs = 0L
    private var lastFrameAtMs = 0L
    private var lastLevel = 0f
    private var lastRmsDbfs = -120f
    private var lastError: String? = null

    val isRunning: Boolean get() = job?.isActive == true && !stopping

    fun start(mediaProjection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("Shared audio requires Android 10 or newer.")
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return failStart("Microphone permission is required for audio sharing.")
        }
        synchronized(lifecycleLock) {
            check(job == null && audioRecord == null) { "Audio capture is already active." }
        }
        resetForNewCapture()
        stopping = false

        val min = AudioRecord.getMinBufferSize(
            settings.sampleRate,
            settings.inputChannelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return failStart("Could not initialize audio capture buffer.")

        val record = createPlaybackCaptureRecord(mediaProjection, min)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return failStart("Could not initialize audio capture. The source app may block capture.")
        }

        synchronized(lifecycleLock) { audioRecord = record }
        runCatching { record.startRecording() }.onFailure { error ->
            synchronized(lifecycleLock) { if (audioRecord === record) audioRecord = null }
            record.release()
            return failStart("Could not start audio capture: ${error.message ?: "recording failed"}")
        }

        val now = System.currentTimeMillis()
        synchronized(metricsLock) {
            captureStartedAtMs = now
            windowStartedAtMs = now
            lastMetricsAtMs = now
            lastError = null
        }
        emitMetrics(active = true, force = true)

        job = scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val frame = ByteArray(settings.frameBytes)
            try {
                while (isActive && !stopping) {
                    var filled = 0
                    while (filled < frame.size && isActive && !stopping) {
                        val read = record.read(
                            frame,
                            filled,
                            frame.size - filled,
                            AudioRecord.READ_BLOCKING,
                        )
                        when {
                            read > 0 -> filled += read
                            read == 0 -> Thread.sleep(1)
                            stopping || !isActive -> break
                            else -> {
                                fail(audioRecordErrorMessage(read))
                                break
                            }
                        }
                    }
                    if (filled == frame.size && !stopping && isActive) {
                        onFrame(frame)
                        recordFrame(frame)
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    if (audioRecord === record) audioRecord = null
                    if (job === coroutineContext[Job]) job = null
                }
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                }
                record.release()
                emitMetrics(active = false, force = true)
            }
        }
        return true
    }

    fun stop() {
        val activeJob: Job?
        val record: AudioRecord?
        synchronized(lifecycleLock) {
            stopping = true
            activeJob = job
            job = null
            record = audioRecord
            audioRecord = null
        }
        runCatching {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        activeJob?.cancel()
        runCatching { runBlocking { activeJob?.join() } }
        emitMetrics(active = false, force = true)
    }

    fun close() {
        stop()
        scope.cancel()
        dispatcher.close()
    }

    fun resetMetrics() {
        val now = System.currentTimeMillis()
        synchronized(metricsLock) {
            totalFrames = 0
            totalBytes = 0
            windowBytes = 0
            windowStartedAtMs = now
            lastMetricsAtMs = now
            lastError = null
        }
        emitMetrics(active = isRunning, force = true)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPlaybackCaptureRecord(mediaProjection: MediaProjection, minBufferSize: Int): AudioRecord {
        check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for audio sharing."
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(settings.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(settings.inputChannelMask)
            .build()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBufferSize * 2, settings.frameBytes * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    private fun recordFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()
        var snapshot: SharedAudioCaptureDiagnostics? = null
        synchronized(metricsLock) {
            totalFrames++
            totalBytes += frame.size
            windowBytes += frame.size
            lastFrameAtMs = now
            if (now - lastMetricsAtMs >= METRICS_INTERVAL_MS) {
                val level = AudioLevelMeter.measurePcm16(frame)
                lastLevel = level.normalized
                lastRmsDbfs = level.rmsDbfs
                val elapsed = (now - windowStartedAtMs).coerceAtLeast(1L)
                snapshot = snapshotLocked(active = true, bytesPerSecond = windowBytes * 1_000L / elapsed)
                windowBytes = 0
                windowStartedAtMs = now
                lastMetricsAtMs = now
            }
        }
        snapshot?.let(onMetrics)
    }

    private fun failStart(message: String): Boolean {
        synchronized(metricsLock) { lastError = message }
        emitMetrics(active = false, force = true)
        onError(message)
        return false
    }

    private fun fail(message: String) {
        if (stopping) return
        stopping = true
        synchronized(metricsLock) { lastError = message }
        emitMetrics(active = false, force = true)
        onError(message)
    }

    private fun resetForNewCapture() {
        synchronized(metricsLock) {
            totalFrames = 0
            totalBytes = 0
            windowBytes = 0
            captureStartedAtMs = 0
            windowStartedAtMs = 0
            lastMetricsAtMs = 0
            lastFrameAtMs = 0
            lastLevel = 0f
            lastRmsDbfs = -120f
            lastError = null
        }
    }

    private fun emitMetrics(active: Boolean, force: Boolean) {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(metricsLock) {
            if (!force && now - lastMetricsAtMs < METRICS_INTERVAL_MS) return
            snapshotLocked(active = active, bytesPerSecond = 0)
        }
        onMetrics(snapshot)
    }

    private fun snapshotLocked(active: Boolean, bytesPerSecond: Long): SharedAudioCaptureDiagnostics =
        SharedAudioCaptureDiagnostics(
            active = active,
            startedAtMs = captureStartedAtMs,
            level = lastLevel,
            rmsDbfs = lastRmsDbfs,
            totalFrames = totalFrames,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            lastFrameAtMs = lastFrameAtMs,
            lastError = lastError,
        )

    private fun audioRecordErrorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_BAD_VALUE -> "Audio capture returned an invalid buffer error."
        AudioRecord.ERROR_INVALID_OPERATION -> "Audio capture is not in a valid recording state."
        AudioRecord.ERROR_DEAD_OBJECT -> "Audio capture device was disconnected."
        AudioRecord.ERROR -> "Audio capture failed."
        else -> "Audio capture failed with code $code."
    }

    private companion object {
        const val METRICS_INTERVAL_MS = 250L
    }
}
