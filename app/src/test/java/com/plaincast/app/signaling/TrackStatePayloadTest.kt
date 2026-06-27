package com.plaincast.app.signaling

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatePayloadTest {
    @Test
    fun trackStateRoundTripsThroughEnvelope() {
        val envelope = SignalingEnvelope(
            type = "track_state",
            roomId = "ROOM",
            from = "host",
            payload = SignalJson.payload(TrackStatePayload(mic = true, screen = true, deviceAudio = false))
        )

        val decoded = SignalJson.decode(SignalJson.encode(envelope))
        val payload = SignalJson.json.decodeFromJsonElement<TrackStatePayload>(decoded.payload)

        assertTrue(payload.mic)
        assertTrue(payload.screen)
        assertFalse(payload.deviceAudio)
    }
}
