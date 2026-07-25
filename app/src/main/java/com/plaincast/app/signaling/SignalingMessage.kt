package com.plaincast.app.signaling

import com.plaincast.app.model.ClientType
import com.plaincast.app.model.Participant
import com.plaincast.app.model.RoomQualityConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION = 10

@Serializable
data class SignalingEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val type: String,
    val roomId: String,
    val from: String,
    val to: String = "*",
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val payload: JsonObject = buildJsonObject {},
)

@Serializable data class JoinPayload(
    val token: String,
    val displayName: String,
    val deviceName: String,
    val clientType: ClientType = ClientType.Android,
    val capabilities: Capabilities = Capabilities.android(),
)

@Serializable data class Capabilities(
    val receiveVoice: Boolean,
    val sendVoice: Boolean,
    val receiveScreen: Boolean,
    val publishScreen: Boolean,
    val receiveAudio: Boolean,
    val publishAudio: Boolean,
) {
    companion object {
        fun android() = Capabilities(true, true, true, true, true, true)
        fun browser() = Capabilities(true, true, true, true, true, false)
    }

    fun isValidFor(clientType: ClientType): Boolean = when (clientType) {
        ClientType.Android -> this == android()
        ClientType.Browser -> receiveVoice && receiveScreen && receiveAudio && !publishAudio
    }
}

@Serializable data class JoinAcceptedPayload(
    val peerId: String,
    val participants: List<Participant>,
    val roomConfig: RoomConfig = RoomConfig(),
    val activeAudioPublisherId: String? = null,
    val audioGeneration: Long = 0,
    val activeScreenSharerId: String? = null,
)

@Serializable data class RoomConfig(
    val maxParticipants: Int = 8,
    val qualityConfig: RoomQualityConfig = RoomQualityConfig(),
)

@Serializable data class JoinRejectedPayload(val reason: String)
@Serializable data class SdpPayload(val sdp: String, val kind: SdpKind)
@Serializable enum class SdpKind { @SerialName("offer") OFFER, @SerialName("answer") ANSWER }
@Serializable data class IcePayload(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)
@Serializable data class TrackStatePayload(val mic: Boolean)
@Serializable data class ShareStatePayload(val peerId: String, val displayName: String, val active: Boolean)
@Serializable data class AudioPublishRequestPayload(val active: Boolean)
@Serializable data class AudioPublishRejectedPayload(
    val reason: String,
    val activePublisherPeerId: String? = null,
    val displayName: String? = null,
)
@Serializable data class AudioPublisherChangedPayload(
    val publisherPeerId: String? = null,
    val previousPublisherPeerId: String? = null,
    val displayName: String? = null,
    val generation: Long,
    val reason: String,
)
@Serializable data class RoomConfigPayload(val config: RoomConfig)
@Serializable data class ParticipantLeftPayload(val peerId: String)
@Serializable data class RoomEndedPayload(val reason: String)
@Serializable data class RemovedPayload(val reason: String)
@Serializable data class ParticipantsPayload(
    val participants: List<Participant>,
    val activeAudioPublisherId: String? = null,
    val audioGeneration: Long = 0,
    val activeScreenSharerId: String? = null,
    val roomConfig: RoomConfig = RoomConfig(),
)

object SignalJson {
    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }
    fun encode(envelope: SignalingEnvelope): String = json.encodeToString(envelope)
    fun decode(raw: String): SignalingEnvelope = json.decodeFromString<SignalingEnvelope>(raw).also {
        require(it.protocolVersion == PROTOCOL_VERSION) { "Unsupported PlainCast protocol version ${it.protocolVersion}." }
    }
    inline fun <reified T> payload(value: T): JsonObject = json.encodeToJsonElement(value) as JsonObject
    fun simple(type: String, roomId: String, from: String, to: String = "*", builder: JsonObject = buildJsonObject {}): String =
        encode(SignalingEnvelope(type = type, roomId = roomId, from = from, to = to, payload = builder))
}

fun pingMessage(roomId: String, from: String) =
    SignalJson.simple("ping", roomId, from, "*", buildJsonObject { put("t", System.currentTimeMillis()) })
