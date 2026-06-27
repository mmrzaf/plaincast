package com.plaincast.app.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class QrPayloadTest {
    @Test
    fun defaultMetadataMatchesPlainCastProtocolV1() {
        val payload = QrPayload(roomId = "ABCD", host = "10.0.0.7", port = 7412, token = "room-secret")

        assertEquals("PlainCast", payload.app)
        assertEquals(1, payload.version)
        assertEquals("PlainCast Room", payload.name)
        assertEquals("ws://10.0.0.7:7412", payload.joinUrl)
    }
}
