package com.plaincast.app.audio

import android.media.AudioFormat
import com.plaincast.app.model.RoomQualityConfig

object SharedAudioConfig {
    const val SAMPLE_RATE = 48_000
    const val BYTES_PER_SAMPLE = 2

    fun settingsFor(config: RoomQualityConfig) = SharedAudioSettings(
        sampleRate = SAMPLE_RATE,
        channelCount = config.audioChannelCount,
        frameMs = config.audioFrameMs,
        bitrateKbps = config.audioBitrateKbps,
        targetDelayMs = config.audioTargetDelayMs,
        maxLateMs = config.audioMaxLateMs,
        maxBufferedMs = config.audioMaxBufferedMs,
    )
}

data class SharedAudioSettings(
    val sampleRate: Int,
    val channelCount: Int,
    val frameMs: Int,
    val bitrateKbps: Int,
    val targetDelayMs: Int,
    val maxLateMs: Int,
    val maxBufferedMs: Int,
) {
    init {
        require(sampleRate == 48_000)
        require(channelCount in 1..2)
        require(frameMs in 10..40)
        require(bitrateKbps in 48..256)
        require(targetDelayMs >= frameMs)
        require(maxLateMs >= frameMs)
        require(maxBufferedMs >= targetDelayMs)
    }
    val frameSamples: Int get() = sampleRate * frameMs / 1_000
    val frameBytes: Int get() = frameSamples * channelCount * SharedAudioConfig.BYTES_PER_SAMPLE
    val maxBufferedPackets: Int get() = (maxBufferedMs / frameMs).coerceAtLeast(2)
    val inputChannelMask: Int get() = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    val outputChannelMask: Int get() = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
}
