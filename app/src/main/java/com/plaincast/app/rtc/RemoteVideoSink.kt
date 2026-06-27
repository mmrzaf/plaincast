package com.plaincast.app.rtc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoTrack

class RemoteVideoSink {
    private val _track = MutableStateFlow<VideoTrack?>(null)
    val track: StateFlow<VideoTrack?> = _track

    fun set(track: VideoTrack?) { _track.value = track }
}
