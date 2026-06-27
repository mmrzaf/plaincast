package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomIdTest {
    @Test
    fun roomIdUsesExpectedLengthAndAlphabet() {
        val id = randomRoomId()
        assertEquals(ROOM_ID_LENGTH, id.length)
        assertTrue(id.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
    }

    @Test
    fun peerIdUsesPrefix() {
        assertTrue(randomId("host").startsWith("host-"))
    }
}
