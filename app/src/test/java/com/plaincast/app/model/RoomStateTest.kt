package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStateTest {
    @Test
    fun joinUrlUsesHostAndPort() {
        val state = RoomState(hostAddress = "192.168.43.1", port = 7412)

        assertEquals("ws://192.168.43.1:7412", state.joinUrl)
    }

    @Test
    fun defaultRoomStateIsIdleDisconnectedParticipant() {
        val state = RoomState()

        assertEquals("Idle", state.status)
        assertFalse(state.isConnected)
        assertFalse(state.isHost)
        assertFalse(state.micEnabled)
        assertTrue(state.participants.isEmpty())
    }
}
