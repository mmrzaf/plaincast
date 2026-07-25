package com.plaincast.app.rtc

/**
 * Small thread-safe FIFO used for signaling messages that may legally arrive
 * before the WebRTC object is ready to consume them. The oldest item is
 * discarded when the bounded capacity is reached so malformed or noisy peers
 * cannot grow memory without limit.
 */
class BoundedPendingQueue<T>(private val capacity: Int) {
    init { require(capacity > 0) }

    private val items = java.util.ArrayDeque<T>(capacity)

    @Synchronized
    fun offer(item: T): Boolean {
        val evicted = items.size >= capacity
        if (evicted) items.removeFirst()
        items.addLast(item)
        return evicted
    }

    @Synchronized
    fun drain(): List<T> = buildList(items.size) {
        while (items.isNotEmpty()) add(items.removeFirst())
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun clear() = items.clear()
}
