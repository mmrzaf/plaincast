package com.plaincast.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAudioJitterBufferTest {
    private val settings = SharedAudioSettings(48_000, 2, 20, 128, 80, 60, 160)

    @Test fun waitsForTargetDelayThenDecodes() {
        val buffer = SharedAudioJitterBuffer(settings)
        buffer.setExpectedGeneration(4)
        val arrival = 1_000_000L
        assertEquals(SharedAudioJitterBuffer.OfferResult.NewStream, buffer.offer(packet(0), arrival))
        assertTrue(buffer.next(arrival) is SharedAudioJitterBuffer.NextAction.Wait)
        val action = buffer.next(arrival + 80_000)
        assertTrue(action is SharedAudioJitterBuffer.NextAction.Decode)
        assertEquals(0L, (action as SharedAudioJitterBuffer.NextAction.Decode).packet.sequence)
    }

    @Test fun rejectsWrongGeneration() {
        val buffer = SharedAudioJitterBuffer(settings)
        buffer.setExpectedGeneration(5)
        assertEquals(SharedAudioJitterBuffer.OfferResult.WrongGeneration, buffer.offer(packet(0), 1_000_000))
    }

    @Test fun missingPacketIsSkippedAfterLateWindow() {
        val buffer = SharedAudioJitterBuffer(settings)
        buffer.setExpectedGeneration(4)
        val arrival = 1_000_000L
        buffer.offer(packet(0), arrival)
        buffer.offer(packet(2), arrival + 40_000)
        buffer.next(arrival + 80_000)
        val action = buffer.next(arrival + 160_001)
        assertTrue(action is SharedAudioJitterBuffer.NextAction.SkipGap)
        assertEquals(1L, (action as SharedAudioJitterBuffer.NextAction.SkipGap).sequence)
    }

    @Test fun capacityIsBounded() {
        val small = settings.copy(maxBufferedMs = 80)
        val buffer = SharedAudioJitterBuffer(small)
        buffer.setExpectedGeneration(4)
        val arrival = 1_000_000L
        var result: SharedAudioJitterBuffer.OfferResult = SharedAudioJitterBuffer.OfferResult.Accepted
        repeat(8) { index -> result = buffer.offer(packet(index.toLong()), arrival + index * 20_000L) }
        assertTrue(result == SharedAudioJitterBuffer.OfferResult.CapacityDrop || buffer.depth() <= small.maxBufferedPackets)
        assertTrue(buffer.depth() <= small.maxBufferedPackets)
    }


    @Test fun packetFromOlderStreamCannotReplaceCurrentStream() {
        val buffer = SharedAudioJitterBuffer(settings)
        buffer.setExpectedGeneration(4)
        buffer.offer(packet(0, streamId = 12), 1_000_000)
        assertEquals(SharedAudioJitterBuffer.OfferResult.TooLate, buffer.offer(packet(50, streamId = 11), 1_010_000))
        assertEquals(12L, buffer.currentStreamId())
    }

    @Test fun newStreamResetsSequence() {
        val buffer = SharedAudioJitterBuffer(settings)
        buffer.setExpectedGeneration(4)
        buffer.offer(packet(10, streamId = 11), 1_000_000)
        assertEquals(SharedAudioJitterBuffer.OfferResult.NewStream, buffer.offer(packet(0, streamId = 12), 1_010_000))
    }

    private fun packet(sequence: Long, streamId: Long = 11, generation: Long = 4) = SharedAudioPacket(
        generation = generation,
        streamId = streamId,
        sequence = sequence,
        captureTimestampUs = sequence * 20_000,
        sampleRate = 48_000,
        channelCount = 2,
        frameMs = 20,
        payload = byteArrayOf(1, 2, 3),
    )
}
