package com.plaincast.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SharedAudioPacket(
    val generation: Long,
    val streamId: Long,
    val sequence: Long,
    val captureTimestampUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val frameMs: Int,
    val payload: ByteArray,
) {
    init {
        require(generation > 0)
        require(streamId > 0)
        require(sequence >= 0)
        require(captureTimestampUs >= 0)
        require(sampleRate == 48_000)
        require(channelCount in 1..2)
        require(frameMs in 10..40)
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES)
    }

    companion object { const val MAX_PAYLOAD_BYTES = 8_192 }
}

object SharedAudioPacketCodec {
    private const val MAGIC = 0x50434F50 // PCOP
    private const val VERSION: Byte = 2
    private const val HEADER_BYTES = 48

    fun encode(packet: SharedAudioPacket): ByteArray = ByteBuffer.allocate(HEADER_BYTES + packet.payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(MAGIC)
        .put(VERSION)
        .putLong(packet.generation)
        .putLong(packet.streamId)
        .putLong(packet.sequence)
        .putLong(packet.captureTimestampUs)
        .putInt(packet.sampleRate)
        .put(packet.channelCount.toByte())
        .putShort(packet.frameMs.toShort())
        .putInt(packet.payload.size)
        .put(packet.payload)
        .array()

    fun decode(bytes: ByteArray): Result<SharedAudioPacket> = runCatching {
        require(bytes.size >= HEADER_BYTES) { "Shared-audio packet is truncated." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Shared-audio packet magic is invalid." }
        require(buffer.get() == VERSION) { "Shared-audio packet version is unsupported." }
        val generation = buffer.long
        val streamId = buffer.long
        val sequence = buffer.long
        val timestamp = buffer.long
        val sampleRate = buffer.int
        val channels = buffer.get().toInt() and 0xff
        val frameMs = buffer.short.toInt() and 0xffff
        val payloadSize = buffer.int
        require(payloadSize in 1..SharedAudioPacket.MAX_PAYLOAD_BYTES) { "Shared-audio payload length is invalid." }
        require(buffer.remaining() == payloadSize) { "Shared-audio packet length does not match its header." }
        SharedAudioPacket(generation, streamId, sequence, timestamp, sampleRate, channels, frameMs, ByteArray(payloadSize).also(buffer::get))
    }
}
