package com.plaincast.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAudioPacketCodecTest {
    @Test fun packetRoundTrips() {
        val packet = SharedAudioPacket(3, 11, 7, 123_456, 48_000, 2, 20, byteArrayOf(1, 2, 3, 4))
        val decoded = SharedAudioPacketCodec.decode(SharedAudioPacketCodec.encode(packet)).getOrThrow()
        assertEquals(packet.generation, decoded.generation)
        assertEquals(packet.streamId, decoded.streamId)
        assertEquals(packet.sequence, decoded.sequence)
        assertEquals(packet.captureTimestampUs, decoded.captureTimestampUs)
        assertEquals(packet.sampleRate, decoded.sampleRate)
        assertEquals(packet.channelCount, decoded.channelCount)
        assertEquals(packet.frameMs, decoded.frameMs)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    @Test fun unframedBytesAreRejected() {
        assertTrue(SharedAudioPacketCodec.decode(ByteArray(960)).isFailure)
    }

    @Test fun tamperedPayloadLengthIsRejected() {
        val encoded = SharedAudioPacketCodec.encode(SharedAudioPacket(1, 2, 0, 0, 48_000, 2, 20, byteArrayOf(1, 2)))
        encoded[47] = 5
        assertTrue(SharedAudioPacketCodec.decode(encoded).isFailure)
    }
}
