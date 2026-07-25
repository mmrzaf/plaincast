package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SharingStatusTest {
    @Test fun idleStateUsesAudioFirstEmptyLabel() {
        assertEquals("Ready to share audio", RoomState().sharingLabel)
    }

    @Test fun videoOnlyStateHasSimpleLabel() {
        assertEquals("Sharing video", RoomState(screenState = MediaLifecycle.Live).sharingLabel)
    }

    @Test fun audioOnlyStateHasSimpleLabel() {
        assertEquals("Sharing audio", RoomState(audioShareState = MediaLifecycle.Live).sharingLabel)
    }

    @Test fun combinedStateHasCombinedLabel() {
        assertEquals("Sharing audio and video", RoomState(screenState = MediaLifecycle.Live, audioShareState = MediaLifecycle.Live).sharingLabel)
    }

    @Test fun remoteAudioPublisherIsVisible() {
        assertEquals("Audio is live", RoomState(activeAudioPublisherId = "peer-1").sharingLabel)
    }
}
