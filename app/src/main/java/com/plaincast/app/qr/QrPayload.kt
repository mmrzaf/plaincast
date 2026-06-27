package com.plaincast.app.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class QrPayload(
    val app: String = "PlainCast",
    val version: Int = 1,
    val roomId: String,
    val host: String,
    val port: Int,
    val token: String,
    val name: String = "PlainCast Room",
) {
    val joinUrl: String get() = "ws://$host:$port"
}

object QrCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(payload: QrPayload): String = json.encodeToString(payload)
    fun decode(raw: String): QrPayload = json.decodeFromString(raw)
}
