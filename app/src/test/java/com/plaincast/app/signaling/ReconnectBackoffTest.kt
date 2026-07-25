package com.plaincast.app.signaling

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun backoffGrowsAndStopsAtEightSeconds() {
        assertEquals(500L, ReconnectBackoff.delayMs(1))
        assertEquals(1_000L, ReconnectBackoff.delayMs(2))
        assertEquals(2_000L, ReconnectBackoff.delayMs(3))
        assertEquals(4_000L, ReconnectBackoff.delayMs(4))
        assertEquals(8_000L, ReconnectBackoff.delayMs(5))
        assertEquals(8_000L, ReconnectBackoff.delayMs(50))
    }
}
