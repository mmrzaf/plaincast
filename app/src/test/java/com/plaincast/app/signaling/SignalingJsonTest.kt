package com.plaincast.app.signaling

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingJsonTest {
    @Test
    fun joinPayloadRoundTripsThroughEnvelope() {
        val envelope = SignalingEnvelope(
            type = "join",
            roomId = "8K7P",
            from = "peer-1",
            to = "host",
            payload = SignalJson.payload(JoinPayload("Ali", "Pixel", "token"))
        )

        val decoded = SignalJson.decode(SignalJson.encode(envelope))
        val payload = SignalJson.json.decodeFromJsonElement<JoinPayload>(decoded.payload)

        assertEquals("join", decoded.type)
        assertEquals("8K7P", decoded.roomId)
        assertEquals("peer-1", decoded.from)
        assertEquals("host", decoded.to)
        assertEquals("Ali", payload.displayName)
        assertEquals("Pixel", payload.deviceName)
        assertEquals("token", payload.token)
    }
}
