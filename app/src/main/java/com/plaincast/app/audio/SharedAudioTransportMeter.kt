package com.plaincast.app.audio

import com.plaincast.app.diagnostics.SharedAudioTransportDiagnostics

class SharedAudioTransportMeter(private val onMetrics: (SharedAudioTransportDiagnostics) -> Unit) {
    private val lock = Any()
    private var submitted = 0L
    private var sent = 0L
    private var received = 0L
    private var inactiveDrops = 0L
    private var backpressureDrops = 0L
    private var malformed = 0L
    private var unauthorized = 0L
    private var lastSentAt = 0L
    private var lastReceivedAt = 0L
    private var lastEmitAt = 0L

    fun onSubmitted() = update { submitted++ }
    fun onSent(count: Int = 1) = update { sent += count; lastSentAt = System.currentTimeMillis() }
    fun onReceived() = update { received++; lastReceivedAt = System.currentTimeMillis() }
    fun onInactiveDrop(count: Int = 1) = update { inactiveDrops += count }
    fun onBackpressureDrop(count: Int = 1) = update { backpressureDrops += count }
    fun onMalformed() = update { malformed++ }
    fun onUnauthorized() = update { unauthorized++ }
    fun flush() = synchronized(lock) { emitLocked(force = true) }
    fun reset() = synchronized(lock) {
        submitted = 0; sent = 0; received = 0; inactiveDrops = 0; backpressureDrops = 0; malformed = 0; unauthorized = 0
        lastSentAt = 0; lastReceivedAt = 0; lastEmitAt = 0; emitLocked(force = true)
    }

    private inline fun update(block: () -> Unit) = synchronized(lock) { block(); emitLocked() }
    private fun emitLocked(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitAt < METRICS_INTERVAL_MS) return
        lastEmitAt = now
        onMetrics(
        SharedAudioTransportDiagnostics(
            submittedPackets = submitted, sentDeliveries = sent, receivedPackets = received,
            inactiveChannelDrops = inactiveDrops, backpressureDrops = backpressureDrops,
            malformedPackets = malformed, unauthorizedPackets = unauthorized,
            lastSentAtMs = lastSentAt, lastReceivedAtMs = lastReceivedAt,
        )
    )
    }

    private companion object { const val METRICS_INTERVAL_MS = 250L }
}
