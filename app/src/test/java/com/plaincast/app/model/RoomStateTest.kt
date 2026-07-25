package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStateTest {
    @Test fun joinUrlUsesHostAndPort() {
        assertEquals("ws://192.168.43.1:7412", RoomState(hostAddress = "192.168.43.1", port = 7412).joinUrl)
    }

    @Test fun startingAudioIsBusyAndBoundedProfileIsDefault() {
        val state = RoomState(audioShareState = MediaLifecycle.Starting)
        assertTrue(state.isBusy)
        assertEquals(100, state.qualityConfig.audioMaxBufferedMs)
    }

    @Test fun talkingParticipantsAreDetected() {
        val state = RoomState(participants = listOf(Participant("peer-b", "Alex", mic = true)))
        assertTrue(state.anyParticipantTalking)
    }

    @Test fun localPublisherFlagsRequireAuthorityAndLiveMedia() {
        val state = RoomState(
            selfPeerId = "peer-a",
            activeAudioPublisherId = "peer-a",
            audioShareState = MediaLifecycle.Live,
            activeScreenSharerId = "peer-a",
            screenState = MediaLifecycle.Live,
        )
        assertTrue(state.localIsAudioPublisher)
        assertTrue(state.localIsScreenSharer)
    }

    @Test fun defaultRoomStateIsIdleDisconnectedParticipant() {
        val state = RoomState()
        assertEquals("Idle", state.status)
        assertFalse(state.isConnected)
        assertFalse(state.isHost)
        assertFalse(state.micEnabled)
        assertTrue(state.participants.isEmpty())
    }
}
