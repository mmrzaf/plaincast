package com.plaincast.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceAudioCaptureController(
    private val scope: CoroutineScope,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(mediaProjection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onError("Device audio sharing requires Android 10 or newer.")
            return false
        }
        stop()
        val sampleRate = 48_000
        val channelMaskIn = AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(sampleRate, channelMaskIn, format)
        if (min <= 0) {
            onError("Could not initialize device-audio capture buffer.")
            return false
        }
        val bufferSize = maxOf(min * 2, sampleRate / 10 * 4)
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(format)
            .setChannelMask(channelMaskIn)
            .build()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("Could not initialize device-audio capture. The current app may block capture.")
            return false
        }
        audioRecord = record
        val buf = ByteArray(bufferSize)
        runCatching { record.startRecording() }
            .onFailure {
                record.release()
                audioRecord = null
                onError("Could not start device-audio recording: ${it.message ?: "recording failed"}")
                return false
            }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) onPcm(buf, read)
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
    }
}
