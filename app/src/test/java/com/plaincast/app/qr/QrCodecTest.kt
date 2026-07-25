package com.plaincast.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodecTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun roundTripKeepsAuthenticatedJoinFields() {
        val payload = QrPayload(roomId = "8K7P", host = "192.168.1.42", port = 7412, token = token)
        val decoded = QrCodec.decode(QrCodec.encode(payload))

        assertEquals("PlainCast", decoded.app)
        assertEquals(7, decoded.version)
        assertEquals("8K7P", decoded.roomId)
        assertEquals("192.168.1.42", decoded.host)
        assertEquals(7412, decoded.port)
        assertEquals(token, decoded.token)
        assertEquals("ws://192.168.1.42:7412", decoded.joinUrl)
    }

    @Test
    fun encodedPayloadIsPlainJsonForQrDebuggability() {
        val raw = QrCodec.encode(QrPayload(roomId = "ABCD", host = "10.0.0.2", port = 7412, token = token))
        assertTrue(raw.contains("PlainCast"))
        assertTrue(raw.contains("10.0.0.2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun olderInvitationIsRejectedInsteadOfSilentlyAdapted() {
        QrCodec.decode("""{"app":"PlainCast","version":1,"roomId":"ABCD","host":"10.0.0.2","port":7412,"token":"$token"}""")
    }
}
