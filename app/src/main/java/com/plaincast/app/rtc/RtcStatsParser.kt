package com.plaincast.app.rtc

import org.webrtc.RTCStatsReport
import java.math.BigInteger

data class RtcVoiceStatsBaseline(
    val outboundBytes: Long = 0,
    val timestampMs: Long = 0,
)

data class VoiceOutboundStats(
    val packets: Long = 0,
    val bytes: Long = 0,
    val bitrateKbps: Double = 0.0,
    val roundTripTimeMs: Double = 0.0,
)

data class VoiceInboundStats(
    val packets: Long = 0,
    val bytes: Long = 0,
    val packetsLost: Long = 0,
    val jitterMs: Double = 0.0,
    val audioLevel: Double = 0.0,
    val concealedSamples: Long = 0,
)

data class TransportStats(
    val roundTripTimeMs: Double = 0.0,
    val availableOutgoingBitrateKbps: Double = 0.0,
)

data class ParsedVoiceOutboundStats(
    val stats: VoiceOutboundStats,
    val baseline: RtcVoiceStatsBaseline,
)

internal data class RawRtcStat(
    val type: String,
    val members: Map<String, Any?>,
)

object RtcStatsParser {
    fun parseVoiceOutbound(
        report: RTCStatsReport,
        baseline: RtcVoiceStatsBaseline,
    ): ParsedVoiceOutboundStats = parseVoiceOutbound(
        records = report.records(),
        reportTimestampMs = (report.timestampUs / 1_000.0).toLong(),
        baseline = baseline,
    )

    internal fun parseVoiceOutbound(
        records: List<RawRtcStat>,
        reportTimestampMs: Long,
        baseline: RtcVoiceStatsBaseline,
    ): ParsedVoiceOutboundStats {
        var packets = 0L
        var bytes = 0L
        var roundTripTimeMs = 0.0
        records.forEach { stat ->
            val kind = stat.mediaKind()
            when {
                stat.type == "outbound-rtp" && kind == "audio" -> {
                    packets += stat.members.long("packetsSent")
                    bytes += stat.members.long("bytesSent")
                }
                stat.type == "remote-inbound-rtp" && kind == "audio" -> {
                    roundTripTimeMs = maxOf(roundTripTimeMs, stat.members.double("roundTripTime") * 1_000.0)
                }
            }
        }

        val elapsedMs = reportTimestampMs - baseline.timestampMs
        val deltaBytes = bytes - baseline.outboundBytes
        val bitrateKbps = if (baseline.timestampMs > 0 && elapsedMs > 0 && deltaBytes >= 0) {
            deltaBytes * 8.0 / elapsedMs
        } else {
            0.0
        }
        return ParsedVoiceOutboundStats(
            stats = VoiceOutboundStats(
                packets = packets,
                bytes = bytes,
                bitrateKbps = bitrateKbps,
                roundTripTimeMs = roundTripTimeMs,
            ),
            baseline = RtcVoiceStatsBaseline(bytes, reportTimestampMs),
        )
    }

    fun parseVoiceInbound(report: RTCStatsReport): VoiceInboundStats = parseVoiceInbound(report.records())

    internal fun parseVoiceInbound(records: List<RawRtcStat>): VoiceInboundStats {
        var packets = 0L
        var bytes = 0L
        var packetsLost = 0L
        var jitterMs = 0.0
        var audioLevel = 0.0
        var concealedSamples = 0L
        records.forEach { stat ->
            if (stat.type != "inbound-rtp" || stat.mediaKind() != "audio") return@forEach
            packets += stat.members.long("packetsReceived")
            bytes += stat.members.long("bytesReceived")
            packetsLost += stat.members.long("packetsLost")
            jitterMs = maxOf(jitterMs, stat.members.double("jitter") * 1_000.0)
            audioLevel = maxOf(audioLevel, stat.members.double("audioLevel"))
            concealedSamples += stat.members.long("concealedSamples")
        }
        return VoiceInboundStats(
            packets = packets,
            bytes = bytes,
            packetsLost = packetsLost,
            jitterMs = jitterMs,
            audioLevel = audioLevel,
            concealedSamples = concealedSamples,
        )
    }

    fun parseTransport(report: RTCStatsReport): TransportStats = parseTransport(report.records())

    internal fun parseTransport(records: List<RawRtcStat>): TransportStats {
        var roundTripTimeMs = 0.0
        var availableOutgoingBitrateKbps = 0.0
        records.forEach { stat ->
            when {
                stat.type == "candidate-pair" && stat.isSelectedCandidatePair() -> {
                    roundTripTimeMs = maxOf(roundTripTimeMs, stat.members.double("currentRoundTripTime") * 1_000.0)
                    availableOutgoingBitrateKbps = maxOf(
                        availableOutgoingBitrateKbps,
                        stat.members.double("availableOutgoingBitrate") / 1_000.0,
                    )
                }
                stat.type == "remote-inbound-rtp" && stat.mediaKind() == "audio" -> {
                    roundTripTimeMs = maxOf(roundTripTimeMs, stat.members.double("roundTripTime") * 1_000.0)
                }
            }
        }
        return TransportStats(
            roundTripTimeMs = roundTripTimeMs,
            availableOutgoingBitrateKbps = availableOutgoingBitrateKbps,
        )
    }

    private fun RTCStatsReport.records(): List<RawRtcStat> =
        statsMap.values.map { RawRtcStat(it.type, it.members) }

    private fun RawRtcStat.mediaKind(): String? =
        members.string("kind") ?: members.string("mediaType")

    private fun RawRtcStat.isSelectedCandidatePair(): Boolean {
        val state = members.string("state")
        val nominated = members.boolean("nominated")
        val selected = members.boolean("selected")
        return selected || (nominated && state == "succeeded")
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()

    private fun Map<String, Any?>.boolean(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    private fun Map<String, Any?>.long(key: String): Long = when (val value = this[key]) {
        is BigInteger -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun Map<String, Any?>.double(key: String): Double = when (val value = this[key]) {
        is BigInteger -> value.toDouble()
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
