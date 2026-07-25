package com.plaincast.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Requests transient ducking from the application currently producing local shared audio. */
class SourceAudioDucker(context: Context) : AutoCloseable {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(listener)
        .setWillPauseWhenDucked(false)
        .build()
    private var active = false

    @Synchronized
    fun setDucked(ducked: Boolean) {
        if (ducked == active) return
        if (ducked) {
            active = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            abandon()
        }
    }

    @Synchronized
    override fun close() = abandon()

    private fun abandon() {
        if (!active) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        active = false
    }
}
