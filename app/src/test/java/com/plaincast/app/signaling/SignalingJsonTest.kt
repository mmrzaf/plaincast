package com.plaincast.app.signaling

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingJsonTest {
    @Test fun authenticatedJoinPayloadRoundTripsThroughEnvelope() {
        val token = "0123456789abcdef0123456789abcdef"
        val envelope = SignalingEnvelope(
            type = "join", roomId = "8K7P", from = "peer-1", to = "host",
            payload = SignalJson.payload(JoinPayload(token = token, displayName = "Ali", deviceName = "Pixel")),
        )
        val decoded = SignalJson.decode(SignalJson.encode(envelope))
        val payload = SignalJson.json.decodeFromJsonElement<JoinPayload>(decoded.payload)
        assertEquals(PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals(token, payload.token)
        assertEquals("Ali", payload.displayName)
    }

    @Test fun roomConfigurationRoundTripsLowLatencyDefaults() {
        val envelope = SignalingEnvelope(
            type = "join_accepted", roomId = "ABCD", from = "host", to = "peer-a",
            payload = SignalJson.payload(JoinAcceptedPayload(peerId = "peer-a", participants = emptyList())),
        )
        val decoded = SignalJson.decode(SignalJson.encode(envelope))
        val payload = SignalJson.json.decodeFromJsonElement<JoinAcceptedPayload>(decoded.payload)
        assertEquals(8, payload.roomConfig.maxParticipants)
        assertEquals(60, payload.roomConfig.qualityConfig.audioTargetDelayMs)
        assertEquals(100, payload.roomConfig.qualityConfig.audioMaxBufferedMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oldProtocolVersionIsRejected() {
        val current = SignalingEnvelope(type = "ping", roomId = "ABCD", from = "peer")
        SignalJson.decode(SignalJson.encode(current).replace("\"protocolVersion\":10", "\"protocolVersion\":9"))
    }

    @Test fun audioBusyRejectionRoundTrips() {
        val envelope = SignalingEnvelope(
            type = "audio_publish_rejected", roomId = "ABCD", from = "host", to = "peer-b",
            payload = SignalJson.payload(
                AudioPublishRejectedPayload(
                    reason = "publisher_busy",
                    activePublisherPeerId = "peer-a",
                    displayName = "Alex",
                )
            ),
        )
        val decoded = SignalJson.decode(SignalJson.encode(envelope))
        val payload = SignalJson.json.decodeFromJsonElement<AudioPublishRejectedPayload>(decoded.payload)
        assertEquals("publisher_busy", payload.reason)
        assertEquals("peer-a", payload.activePublisherPeerId)
        assertEquals("Alex", payload.displayName)
    }
}
