package com.plaincast.app.audio

import java.util.TreeMap
import kotlin.math.abs

class SharedAudioJitterBuffer(initialSettings: SharedAudioSettings) {
    sealed interface NextAction {
        data object Idle : NextAction
        data class Wait(val delayUs: Long) : NextAction
        data class Decode(val packet: SharedAudioPacket) : NextAction
        data class SkipGap(val sequence: Long) : NextAction
    }
    enum class OfferResult { Accepted, NewStream, Duplicate, TooLate, WrongGeneration, InvalidFormat, CapacityDrop }

    private var settings = initialSettings
    private val packets = TreeMap<Long, SharedAudioPacket>()
    private var generation = 0L
    private var streamId: Long? = null
    private var expectedSequence = -1L
    private var baseCaptureUs = 0L
    private var baseSequence = 0L
    private var basePlayoutUs = 0L
    private var maxDepth = 0
    private var lastArrivalUs = 0L
    private var lastCaptureUs = 0L
    private var jitterUs = 0.0
    var receivedPackets = 0L; private set
    var duplicatePackets = 0L; private set
    var outOfOrderPackets = 0L; private set
    var stalePackets = 0L; private set
    var skippedGaps = 0L; private set

    @Synchronized
    fun configure(value: SharedAudioSettings) { settings = value; reset() }
    @Synchronized
    fun setExpectedGeneration(value: Long) { if (generation != value) { reset(); generation = value } }

    @Synchronized
    fun offer(packet: SharedAudioPacket, arrivalUs: Long): OfferResult {
        if (packet.generation != generation || generation <= 0) return OfferResult.WrongGeneration
        if (packet.sampleRate != settings.sampleRate || packet.channelCount != settings.channelCount || packet.frameMs != settings.frameMs) return OfferResult.InvalidFormat
        val currentStream = streamId
        if (currentStream != null && packet.streamId < currentStream) {
            stalePackets++
            return OfferResult.TooLate
        }
        val newStream = currentStream == null || packet.streamId > currentStream
        if (newStream) startStream(packet, arrivalUs)
        receivedPackets++
        updateJitter(packet, arrivalUs)
        if (packet.sequence < expectedSequence) { stalePackets++; return OfferResult.TooLate }
        if (packets.containsKey(packet.sequence)) { duplicatePackets++; return OfferResult.Duplicate }
        if (packets.isNotEmpty() && packet.sequence < packets.lastKey()) outOfOrderPackets++
        val deadline = playoutTime(packet.captureTimestampUs)
        if (arrivalUs > deadline + settings.maxLateMs * 1_000L) { stalePackets++; return OfferResult.TooLate }
        packets[packet.sequence] = packet
        var capacityDrop = false
        while (packets.size > settings.maxBufferedPackets) {
            val removed = packets.pollFirstEntry()
            if (removed != null && removed.key == expectedSequence) {
                expectedSequence++
                skippedGaps++
            }
            stalePackets++
            capacityDrop = true
        }
        maxDepth = maxOf(maxDepth, packets.size)
        return when { newStream -> OfferResult.NewStream; capacityDrop -> OfferResult.CapacityDrop; else -> OfferResult.Accepted }
    }

    @Synchronized
    fun next(nowUs: Long): NextAction {
        val first = packets.firstEntry() ?: return NextAction.Idle
        if (expectedSequence < 0) expectedSequence = first.key
        val expected = packets[expectedSequence]
        if (expected != null) {
            val due = playoutTime(expected.captureTimestampUs)
            if (nowUs < due) return NextAction.Wait(due - nowUs)
            packets.remove(expectedSequence)
            expectedSequence++
            return NextAction.Decode(expected)
        }
        if (first.key > expectedSequence) {
            val due = basePlayoutUs + ((expectedSequence - baseSequence) * settings.frameMs * 1_000L)
            if (nowUs < due + settings.maxLateMs * 1_000L) return NextAction.Wait((due + settings.maxLateMs * 1_000L) - nowUs)
            val skipped = expectedSequence++
            skippedGaps++
            return NextAction.SkipGap(skipped)
        }
        packets.pollFirstEntry(); stalePackets++
        return NextAction.Idle
    }

    @Synchronized
    fun reset() {
        packets.clear(); streamId = null; expectedSequence = -1; baseCaptureUs = 0; baseSequence = 0; basePlayoutUs = 0
        maxDepth = 0; lastArrivalUs = 0; lastCaptureUs = 0; jitterUs = 0.0
    }
    @Synchronized
    fun resetMetrics() { receivedPackets = 0; duplicatePackets = 0; outOfOrderPackets = 0; stalePackets = 0; skippedGaps = 0; maxDepth = packets.size }
    @Synchronized
    fun depth(): Int = packets.size
    @Synchronized
    fun bufferedMs(): Int = packets.size * settings.frameMs
    @Synchronized
    fun maxDepth(): Int = maxDepth
    @Synchronized
    fun jitterMs(): Double = jitterUs / 1_000.0
    @Synchronized
    fun currentStreamId(): Long? = streamId

    private fun startStream(packet: SharedAudioPacket, arrivalUs: Long) {
        reset(); streamId = packet.streamId; expectedSequence = packet.sequence
        baseCaptureUs = packet.captureTimestampUs
        baseSequence = packet.sequence
        basePlayoutUs = arrivalUs + settings.targetDelayMs * 1_000L
    }
    private fun playoutTime(captureUs: Long): Long = basePlayoutUs + (captureUs - baseCaptureUs)
    private fun updateJitter(packet: SharedAudioPacket, arrivalUs: Long) {
        if (lastArrivalUs != 0L) {
            val variation = abs((arrivalUs - lastArrivalUs) - (packet.captureTimestampUs - lastCaptureUs)).toDouble()
            jitterUs += (variation - jitterUs) / 16.0
        }
        lastArrivalUs = arrivalUs; lastCaptureUs = packet.captureTimestampUs
    }
}
