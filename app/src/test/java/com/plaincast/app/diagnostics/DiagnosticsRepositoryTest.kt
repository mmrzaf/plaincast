package com.plaincast.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsRepositoryTest {
    @Test
    fun resetUsesCurrentRtpCountersAsNewBaseline() {
        val repository = DiagnosticsRepository()
        repository.beginSession("room", "self")
        repository.updatePeerVoiceOutbound("peer", packets = 100, bytes = 10_000, bitrateKbps = 80.0, roundTripTimeMs = 20.0)
        repository.updatePeerVoiceInbound("peer", packets = 90, bytes = 9_000, packetsLost = 2, jitterMs = 4.0, audioLevel = 0.3, concealedSamples = 100)

        repository.resetCounters()
        repository.updatePeerVoiceOutbound("peer", packets = 112, bytes = 11_200, bitrateKbps = 90.0, roundTripTimeMs = 22.0)
        repository.updatePeerVoiceInbound("peer", packets = 97, bytes = 9_700, packetsLost = 3, jitterMs = 5.0, audioLevel = 0.4, concealedSamples = 140)

        val peer = repository.state.value.peers.getValue("peer")
        assertEquals(12L, peer.outboundVoicePackets)
        assertEquals(1_200L, peer.outboundVoiceBytes)
        assertEquals(7L, peer.inboundVoicePackets)
        assertEquals(700L, peer.inboundVoiceBytes)
        assertEquals(1L, peer.inboundVoicePacketsLost)
        assertEquals(40L, peer.voiceConcealedSamples)
    }

    @Test
    fun rtpCounterRestartStartsANewGeneration() {
        val repository = DiagnosticsRepository()
        repository.beginSession("room", "self")
        repository.updatePeerVoiceOutbound("peer", packets = 100, bytes = 10_000, bitrateKbps = 80.0, roundTripTimeMs = 20.0)
        repository.updatePeerVoiceInbound("peer", packets = 90, bytes = 9_000, packetsLost = 2, jitterMs = 4.0, audioLevel = 0.3, concealedSamples = 100)
        repository.resetCounters()

        repository.updatePeerVoiceOutbound("peer", packets = 3, bytes = 300, bitrateKbps = 24.0, roundTripTimeMs = 18.0)
        repository.updatePeerVoiceInbound("peer", packets = 2, bytes = 200, packetsLost = 0, jitterMs = 3.0, audioLevel = 0.2, concealedSamples = 20)

        val peer = repository.state.value.peers.getValue("peer")
        assertEquals(3L, peer.outboundVoicePackets)
        assertEquals(300L, peer.outboundVoiceBytes)
        assertEquals(2L, peer.inboundVoicePackets)
        assertEquals(200L, peer.inboundVoiceBytes)
        assertEquals(0L, peer.inboundVoicePacketsLost)
        assertEquals(20L, peer.voiceConcealedSamples)
    }

    @Test
    fun publisherChangeTimestampOnlyMovesWhenPublisherChanges() {
        val repository = DiagnosticsRepository()
        repository.beginSession("room", "self")

        repository.setActiveAudioPublisher("peer")
        val first = repository.state.value.activeAudioPublisherChangedAtMs
        repository.setActiveAudioPublisher("peer")
        val second = repository.state.value.activeAudioPublisherChangedAtMs

        assertEquals(first, second)
    }
}
