package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingStatusTest {
    @Test
    fun idleStateIsNotSharing() {
        val state = RoomState()

        assertFalse(state.isSharing)
        assertEquals("No screen share active", state.sharingLabel)
    }

    @Test
    fun screenOnlyStateHasScreenLabel() {
        val state = RoomState(screenEnabled = true)

        assertTrue(state.isSharing)
        assertEquals("Sharing screen", state.sharingLabel)
    }

    @Test
    fun audioOnlyStateHasAudioLabel() {
        val state = RoomState(deviceAudioEnabled = true)

        assertTrue(state.isSharing)
        assertEquals("Sharing device audio", state.sharingLabel)
    }

    @Test
    fun screenAndAudioStateHasCombinedLabel() {
        val state = RoomState(screenEnabled = true, deviceAudioEnabled = true)

        assertTrue(state.isSharing)
        assertEquals("Sharing screen and device audio", state.sharingLabel)
    }
}
