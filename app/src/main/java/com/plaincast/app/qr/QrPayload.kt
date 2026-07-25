package com.plaincast.app.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val QR_VERSION = 7
private val TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")
private val ROOM_PATTERN = Regex("^[A-Z2-9]{4}$")

@Serializable
data class QrPayload(
    val app: String = "PlainCast",
    val version: Int = QR_VERSION,
    val roomId: String,
    val host: String,
    val port: Int,
    val token: String,
    val name: String = "PlainCast Room",
) {
    init {
        require(app == "PlainCast") { "Not a PlainCast invitation." }
        require(version == QR_VERSION) { "Unsupported PlainCast invitation version." }
        require(ROOM_PATTERN.matches(roomId)) { "Invalid room ID." }
        require(host.isNotBlank()) { "Host address is missing." }
        require(port in 1..65_535) { "Invalid room port." }
        require(TOKEN_PATTERN.matches(token)) { "Invalid room token." }
    }

    val joinUrl: String get() = "ws://$host:$port"
}

object QrCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
    fun encode(payload: QrPayload): String = json.encodeToString(payload)
    fun decode(raw: String): QrPayload = json.decodeFromString<QrPayload>(raw.trim())
}
