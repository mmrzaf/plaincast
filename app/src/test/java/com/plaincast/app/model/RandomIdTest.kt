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

    @Test
    fun joinTokenHas128BitsEncodedAsLowercaseHex() {
        val token = randomJoinToken()
        assertEquals(32, token.length)
        assertTrue(token.all { it in "0123456789abcdef" })
    }
}
