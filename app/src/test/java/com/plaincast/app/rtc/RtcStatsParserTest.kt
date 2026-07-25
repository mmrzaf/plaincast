package com.plaincast.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class RtcStatsParserTest {
    @Test
    fun parsesVoiceSenderStatsAndBitrate() {
        val records = listOf(
            RawRtcStat(
                type = "outbound-rtp",
                members = mapOf(
                    "kind" to "audio",
                    "packetsSent" to 50L,
                    "bytesSent" to 3_000L,
                ),
            ),
            RawRtcStat(
                type = "remote-inbound-rtp",
                members = mapOf(
                    "kind" to "audio",
                    "roundTripTime" to 0.08,
                ),
            ),
        )

        val parsed = RtcStatsParser.parseVoiceOutbound(
            records = records,
            reportTimestampMs = 2_000,
            baseline = RtcVoiceStatsBaseline(outboundBytes = 1_000, timestampMs = 1_000),
        )

        assertEquals(50L, parsed.stats.packets)
        assertEquals(3_000L, parsed.stats.bytes)
        assertEquals(16.0, parsed.stats.bitrateKbps, 0.001)
        assertEquals(80.0, parsed.stats.roundTripTimeMs, 0.001)
    }

    @Test
    fun parsesVoiceReceiverStats() {
        val records = listOf(
            RawRtcStat(
                type = "inbound-rtp",
                members = mapOf(
                    "mediaType" to "audio",
                    "packetsReceived" to 45L,
                    "bytesReceived" to 2_500L,
                    "packetsLost" to 2L,
                    "jitter" to 0.012,
                    "audioLevel" to 0.35,
                    "concealedSamples" to 480L,
                ),
            ),
        )

        val parsed = RtcStatsParser.parseVoiceInbound(records)

        assertEquals(45L, parsed.packets)
        assertEquals(2_500L, parsed.bytes)
        assertEquals(2L, parsed.packetsLost)
        assertEquals(12.0, parsed.jitterMs, 0.001)
        assertEquals(0.35, parsed.audioLevel, 0.001)
        assertEquals(480L, parsed.concealedSamples)
    }

    @Test
    fun parsesSelectedTransportStats() {
        val records = listOf(
            RawRtcStat(
                type = "candidate-pair",
                members = mapOf(
                    "state" to "succeeded",
                    "nominated" to true,
                    "currentRoundTripTime" to 0.08,
                    "availableOutgoingBitrate" to 1_500_000.0,
                ),
            ),
        )

        val parsed = RtcStatsParser.parseTransport(records)

        assertEquals(80.0, parsed.roundTripTimeMs, 0.001)
        assertEquals(1_500.0, parsed.availableOutgoingBitrateKbps, 0.001)
    }
}
