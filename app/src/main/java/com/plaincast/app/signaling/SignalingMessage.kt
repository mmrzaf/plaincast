package com.plaincast.app.signaling

import com.plaincast.app.model.Participant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SignalingEnvelope(
    val type: String,
    val roomId: String,
    val from: String,
    val to: String = "*",
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val payload: JsonObject = buildJsonObject {},
)

@Serializable
data class JoinPayload(
    val displayName: String,
    val deviceName: String,
    val token: String,
    val capabilities: Capabilities = Capabilities(),
)

@Serializable
data class Capabilities(
    val mic: Boolean = true,
    val screenShare: Boolean = false,
    val deviceAudio: Boolean = false,
)

@Serializable
data class JoinAcceptedPayload(
    val peerId: String,
    val participants: List<Participant>,
    val roomConfig: RoomConfig = RoomConfig(),
)

@Serializable
data class RoomConfig(
    val maxParticipants: Int = 4,
    val clientCanShare: Boolean = false,
)

@Serializable
data class JoinRejectedPayload(val reason: String)

@Serializable
data class SdpPayload(val sdp: String, val kind: SdpKind)

@Serializable
enum class SdpKind {
    @SerialName("offer") OFFER,
    @SerialName("answer") ANSWER,
}

@Serializable
data class IcePayload(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

@Serializable
data class TrackStatePayload(
    val mic: Boolean,
    val screen: Boolean,
    val deviceAudio: Boolean,
)

@Serializable
data class ParticipantLeftPayload(val peerId: String)
@Serializable
data class RoomEndedPayload(val reason: String)
@Serializable
data class RemovedPayload(val reason: String)

object SignalJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun encode(envelope: SignalingEnvelope): String = json.encodeToString(envelope)
    fun decode(raw: String): SignalingEnvelope = json.decodeFromString(raw)

    inline fun <reified T> payload(value: T): JsonObject = json.encodeToJsonElement(value) as JsonObject

    fun simple(type: String, roomId: String, from: String, to: String = "*", builder: JsonObject = buildJsonObject {}): String =
        encode(SignalingEnvelope(type = type, roomId = roomId, from = from, to = to, payload = builder))
}

fun pingMessage(roomId: String, from: String) = SignalJson.simple("ping", roomId, from, "*", buildJsonObject { put("t", System.currentTimeMillis()) })
