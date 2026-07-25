package com.plaincast.app.signaling

object ReconnectBackoff {
    fun delayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 4)
        return (500L shl shift).coerceAtMost(8_000L)
    }
}
