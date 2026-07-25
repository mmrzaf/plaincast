package com.plaincast.app.audio

import android.media.AudioFormat
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioLevel(
    val normalized: Float,
    val rmsDbfs: Float,
    val peakDbfs: Float,
    val sampleCount: Int,
)

object AudioLevelMeter {
    fun measurePcm16(data: ByteArray): AudioLevel = measure(
        data = data,
        audioFormat = AudioFormat.ENCODING_PCM_16BIT,
    )

    fun measure(data: ByteArray, audioFormat: Int): AudioLevel {
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT || data.size < 2) {
            return AudioLevel(0f, FLOOR_DBFS, FLOOR_DBFS, 0)
        }

        var sumSquares = 0.0
        var peak = 0
        var samples = 0
        var index = 0
        while (index + 1 < data.size) {
            val value = ((data[index + 1].toInt() shl 8) or (data[index].toInt() and 0xff)).toShort().toInt()
            val absolute = kotlin.math.abs(value)
            if (absolute > peak) peak = absolute
            sumSquares += value.toDouble() * value.toDouble()
            samples++
            index += 2
        }
        if (samples == 0) return AudioLevel(0f, FLOOR_DBFS, FLOOR_DBFS, 0)

        val rms = sqrt(sumSquares / samples)
        val rmsDbfs = amplitudeToDbfs(rms)
        val peakDbfs = amplitudeToDbfs(peak.toDouble())
        val normalized = ((rmsDbfs - FLOOR_DBFS) / -FLOOR_DBFS).coerceIn(0f, 1f)
        return AudioLevel(normalized, rmsDbfs, peakDbfs, samples)
    }

    private fun amplitudeToDbfs(amplitude: Double): Float {
        if (amplitude <= 0.0) return FLOOR_DBFS
        return (20.0 * log10(amplitude / Short.MAX_VALUE.toDouble()))
            .coerceAtLeast(FLOOR_DBFS.toDouble())
            .toFloat()
    }

    private const val FLOOR_DBFS = -120f
}
