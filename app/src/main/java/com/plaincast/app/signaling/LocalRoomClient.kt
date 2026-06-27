package com.plaincast.app.signaling

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class LocalRoomClient(
    uri: URI,
    private val onEvent: (ClientEvent) -> Unit,
) : WebSocketClient(uri) {
    override fun onOpen(handshakedata: ServerHandshake?) {
        onEvent(ClientEvent.Open)
    }

    override fun onMessage(message: String) {
        val env = runCatching { SignalJson.decode(message) }.getOrElse {
            Log.w(TAG, "invalid signal: $message", it)
            return
        }
        onEvent(ClientEvent.Signal(env))
    }

    override fun onMessage(bytes: ByteBuffer) {
        val data = ByteArray(bytes.remaining())
        bytes.get(data)
        onEvent(ClientEvent.DeviceAudio(data))
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onEvent(ClientEvent.Closed(reason ?: "closed"))
    }

    override fun onError(ex: Exception) {
        onEvent(ClientEvent.Error(ex.message ?: "Unknown WebSocket error"))
    }

    fun send(env: SignalingEnvelope) = send(SignalJson.encode(env))

    companion object { private const val TAG = "LocalRoomClient" }
}

sealed interface ClientEvent {
    data object Open : ClientEvent
    data class Signal(val envelope: SignalingEnvelope) : ClientEvent
    data class DeviceAudio(val bytes: ByteArray) : ClientEvent
    data class Closed(val reason: String) : ClientEvent
    data class Error(val message: String) : ClientEvent
}
