package com.plaincast.app.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class QrPayloadTest {
    @Test
    fun metadataMatchesAuthenticatedProtocolV7() {
        val token = "0123456789abcdef0123456789abcdef"
        val payload = QrPayload(roomId = "ABCD", host = "10.0.0.7", port = 7412, token = token)

        assertEquals("PlainCast", payload.app)
        assertEquals(7, payload.version)
        assertEquals(token, payload.token)
        assertEquals("PlainCast Room", payload.name)
        assertEquals("ws://10.0.0.7:7412", payload.joinUrl)
    }
}
