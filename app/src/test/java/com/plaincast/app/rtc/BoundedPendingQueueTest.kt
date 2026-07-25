package com.plaincast.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPendingQueueTest {
    @Test fun keepsOrderAndDrains() {
        val queue = BoundedPendingQueue<Int>(3)
        assertFalse(queue.offer(1))
        assertFalse(queue.offer(2))
        assertEquals(listOf(1, 2), queue.drain())
        assertEquals(0, queue.size())
    }

    @Test fun evictsOldestAtCapacity() {
        val queue = BoundedPendingQueue<Int>(2)
        queue.offer(1)
        queue.offer(2)
        assertTrue(queue.offer(3))
        assertEquals(listOf(2, 3), queue.drain())
    }
}
