package com.plaincast.app.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPublisherAuthorityTest {
    @Test fun firstRequestBecomesPublisher() {
        val authority = AudioPublisherAuthority()
        val changed = authority.request("peer-a", "started").single() as AudioPublisherAuthorityEvent.PublisherChanged
        assertNull(changed.transition.previousPeerId)
        assertEquals("peer-a", changed.transition.currentPeerId)
        assertEquals(1L, changed.transition.generation)
    }

    @Test fun anotherPublisherIsRejectedUntilCurrentPublisherStops() {
        val authority = AudioPublisherAuthority()
        authority.request("peer-a", "started")

        val rejected = authority.request("peer-b", "requested").single() as AudioPublisherAuthorityEvent.RequestRejected
        assertEquals("peer-b", rejected.peerId)
        assertEquals("peer-a", rejected.activePeerId)
        assertEquals("publisher_busy", rejected.reason)
        assertEquals("peer-a", authority.snapshot().activePeerId)
        assertEquals(1L, authority.snapshot().generation)
    }

    @Test fun secondPublisherCanStartAfterFirstStops() {
        val authority = AudioPublisherAuthority()
        authority.request("peer-a", "started")
        authority.stop("peer-a", force = false, reason = "publisher_stopped")

        val changed = authority.request("peer-b", "started").single() as AudioPublisherAuthorityEvent.PublisherChanged
        assertNull(changed.transition.previousPeerId)
        assertEquals("peer-b", changed.transition.currentPeerId)
        assertEquals(3L, changed.transition.generation)
    }

    @Test fun repeatRequestIsNoOp() {
        val authority = AudioPublisherAuthority()
        authority.request("peer-a", "started")
        assertTrue(authority.request("peer-a", "repeat").isEmpty())
        assertEquals(1L, authority.snapshot().generation)
    }

    @Test fun publisherCannotBeStoppedByAnotherParticipant() {
        val authority = AudioPublisherAuthority()
        authority.request("peer-a", "started")
        assertTrue(authority.stop("peer-b", force = false, reason = "stopped").isEmpty())
        assertEquals("peer-a", authority.snapshot().activePeerId)
    }

    @Test fun hostCanForceStopPublisher() {
        val authority = AudioPublisherAuthority()
        authority.request("peer-a", "started")
        val changed = authority.stop(null, force = true, reason = "host_stopped").single() as AudioPublisherAuthorityEvent.PublisherChanged
        assertNull(changed.transition.currentPeerId)
        assertEquals(2L, changed.transition.generation)
    }

    @Test fun disconnectClearsPublisher() {
        val authority = AudioPublisherAuthority()
        authority.request("peer-a", "started")
        authority.disconnect("peer-a")
        assertNull(authority.snapshot().activePeerId)
        assertEquals(2L, authority.snapshot().generation)
    }
}
