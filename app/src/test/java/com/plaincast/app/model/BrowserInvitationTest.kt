package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserInvitationTest {
    @Test
    fun browserTokenLivesInFragmentAndSignalingPortIsExplicit() {
        val state = RoomState(
            roomId = "ABCD",
            hostAddress = "192.168.43.1",
            port = 7412,
            webPort = 7413,
            joinToken = "0123456789abcdef0123456789abcdef",
        )

        assertEquals(
            "http://192.168.43.1:7413/join/ABCD#token=0123456789abcdef0123456789abcdef&signalPort=7412",
            state.browserUrl,
        )
        assertFalse(state.browserUrl.substringBefore('#').contains(state.joinToken))
    }
}
