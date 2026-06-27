package com.plaincast.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class DeviceAudioPlayer(scope: CoroutineScope) {
    private val channel = Channel<ByteArray>(capacity = Channel.BUFFERED)
    private var audioTrack: AudioTrack? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (data in channel) ensureTrack().write(data, 0, data.size)
        }
    }

    fun play(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    fun stop() {
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        audioTrack = null
    }

    private fun ensureTrack(): AudioTrack {
        audioTrack?.let { return it }
        val sampleRate = 48_000
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 4, sampleRate / 2 * 4))
            .build()
        track.play()
        audioTrack = track
        return track
    }
}
