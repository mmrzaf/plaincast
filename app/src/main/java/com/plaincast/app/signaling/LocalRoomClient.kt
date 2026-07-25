package com.plaincast.app.signaling

import android.util.Log
import java.net.URI
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

class LocalRoomClient(
    private val uri: URI,
    private val scope: CoroutineScope,
    private val onEvent: (ClientEvent) -> Unit,
) {
    private val lock = Any()
    private var socket: SessionSocket? = null
    private var reconnectJob: Job? = null
    private var generation = 0
    private var reconnectAttempt = 0
    private var stopped = false

    val isOpen: Boolean get() = synchronized(lock) { socket?.isOpen == true }

    fun start() {
        synchronized(lock) {
            check(!stopped) { "Client is closed." }
            if (socket != null || reconnectJob?.isActive == true) return
        }
        connectNew()
    }

    fun reconnectNow() {
        val shouldConnect = synchronized(lock) {
            if (stopped || socket?.isOpen == true) return
            reconnectJob?.cancel(); reconnectJob = null
            socket == null
        }
        if (shouldConnect) connectNew()
    }

    fun send(envelope: SignalingEnvelope): Boolean {
        val active = synchronized(lock) { socket }
        if (active?.isOpen != true) return false
        return runCatching { active.send(SignalJson.encode(envelope)); true }.getOrElse { false }
    }

    fun close() {
        val active = synchronized(lock) {
            if (stopped) return
            stopped = true
            reconnectJob?.cancel(); reconnectJob = null
            socket.also { socket = null }
        }
        active?.close(1000, "left")
    }

    private fun connectNew() {
        val session = synchronized(lock) {
            if (stopped || socket != null) return
            SessionSocket(++generation).also { socket = it }
        }
        onEvent(ClientEvent.Connecting(reconnectAttempt))
        runCatching { session.connect() }.onFailure { handleTerminal(session.generation, it.message ?: "Could not open WebSocket") }
    }

    private fun scheduleReconnect(reason: String) {
        val attempt: Int
        synchronized(lock) {
            if (stopped || reconnectJob?.isActive == true) return
            attempt = ++reconnectAttempt
            reconnectJob = scope.launch {
                delay(ReconnectBackoff.delayMs(attempt))
                synchronized(lock) { reconnectJob = null }
                connectNew()
            }
        }
        onEvent(ClientEvent.Reconnecting(attempt, reason))
    }

    private fun handleTerminal(sessionGeneration: Int, reason: String) {
        val shouldReconnect = synchronized(lock) {
            val active = socket
            if (active?.generation != sessionGeneration) return
            socket = null
            !stopped
        }
        if (shouldReconnect) scheduleReconnect(reason)
    }

    private inner class SessionSocket(val generation: Int) : WebSocketClient(uri) {
        init { connectionLostTimeout = HEARTBEAT_TIMEOUT_SECONDS }
        override fun onOpen(handshakedata: ServerHandshake?) {
            val valid = synchronized(lock) {
                !stopped && this@LocalRoomClient.socket === this@SessionSocket
            }
            if (!valid) return close()
            reconnectAttempt = 0
            onEvent(ClientEvent.Open)
        }
        override fun onMessage(message: String) {
            if (message.length > MAX_SIGNAL_CHARS) {
                close(1009, "signal too large")
                return
            }
            val envelope = runCatching { SignalJson.decode(message) }.getOrElse { error ->
                Log.w(TAG, "Invalid signal", error)
                onEvent(ClientEvent.Error(error.message ?: "Invalid signaling message")); return
            }
            onEvent(ClientEvent.Signal(envelope))
        }
        override fun onMessage(bytes: ByteBuffer) {
            close(1003, "binary signaling is not supported")
        }
        override fun onClose(code: Int, reason: String?, remote: Boolean) = handleTerminal(generation, reason ?: "connection closed")
        override fun onError(ex: Exception) = onEvent(ClientEvent.Error(ex.message ?: "Unknown WebSocket error"))
    }

    companion object {
        private const val TAG = "LocalRoomClient"
        private const val HEARTBEAT_TIMEOUT_SECONDS = 6
        private const val MAX_SIGNAL_CHARS = 64 * 1024
    }
}

sealed interface ClientEvent {
    data class Connecting(val attempt: Int) : ClientEvent
    data object Open : ClientEvent
    data class Signal(val envelope: SignalingEnvelope) : ClientEvent
    data class Reconnecting(val attempt: Int, val reason: String) : ClientEvent
    data class Error(val message: String) : ClientEvent
}
