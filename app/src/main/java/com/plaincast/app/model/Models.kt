package com.plaincast.app.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom

const val DEFAULT_PORT = 7412
const val DEFAULT_WEB_PORT = 7413
const val ROOM_ID_LENGTH = 4

@Serializable
data class Participant(
    val peerId: String,
    val displayName: String,
    val role: Role = Role.PARTICIPANT,
    val clientType: ClientType = ClientType.Android,
    val mic: Boolean = false,
    val screen: Boolean = false,
    val audio: Boolean = false,
)

@Serializable enum class Role { HOST, PARTICIPANT }
@Serializable enum class ClientType { Android, Browser }
@Serializable enum class RoomLifecycle { Idle, Creating, Joining, Connected, Reconnecting, Leaving, Failed }
@Serializable enum class MediaLifecycle { Stopped, Starting, Live, Failed }
@Serializable enum class ConnectionHealth { Idle, Connecting, Stable, Reconnecting, Poor, Disconnected }
@Serializable
data class RoomQualityConfig(
    val audioChannelCount: Int = 2,
    val audioFrameMs: Int = 20,
    val audioBitrateKbps: Int = 128,
    val audioTargetDelayMs: Int = 60,
    val audioMaxLateMs: Int = 40,
    val audioMaxBufferedMs: Int = 100,
    val screenWidth: Int = 720,
    val screenHeight: Int = 1280,
    val screenFps: Int = 12,
    val screenMaxBitrateKbps: Int = 700,
) {
    init {
        require(audioChannelCount in 1..2)
        require(audioFrameMs in 10..40)
        require(audioBitrateKbps in 48..256)
        require(audioTargetDelayMs >= audioFrameMs)
        require(audioMaxLateMs >= audioFrameMs)
        require(audioMaxBufferedMs >= audioTargetDelayMs)
        require(screenFps in 5..30)
        require(screenMaxBitrateKbps in 200..2_500)
    }
}

@Serializable
data class RoomState(
    val roomId: String = "",
    val hostAddress: String = "",
    val port: Int = DEFAULT_PORT,
    val webPort: Int = DEFAULT_WEB_PORT,
    val joinToken: String = "",
    val selfPeerId: String = randomId("peer"),
    val displayName: String = "Android",
    val isHost: Boolean = false,
    val lifecycle: RoomLifecycle = RoomLifecycle.Idle,
    val microphoneState: MediaLifecycle = MediaLifecycle.Stopped,
    val screenState: MediaLifecycle = MediaLifecycle.Stopped,
    val audioShareState: MediaLifecycle = MediaLifecycle.Stopped,
    val activeAudioPublisherId: String? = null,
    val audioGeneration: Long = 0,
    val activeScreenSharerId: String? = null,
    val qualityConfig: RoomQualityConfig = RoomQualityConfig(),
    val connectionHealth: ConnectionHealth = ConnectionHealth.Idle,
    val reconnectAttempt: Int = 0,
    val status: String = "Idle",
    val participants: List<Participant> = emptyList(),
) {
    val joinUrl: String get() = "ws://$hostAddress:$port"
    val browserUrl: String get() = "http://$hostAddress:$webPort/join/$roomId#token=$joinToken&signalPort=$port"
    val isConnected: Boolean get() = lifecycle == RoomLifecycle.Connected
    val micEnabled: Boolean get() = microphoneState == MediaLifecycle.Live
    val screenEnabled: Boolean get() = screenState == MediaLifecycle.Live
    val audioSharingEnabled: Boolean get() = audioShareState == MediaLifecycle.Live
    val localIsAudioPublisher: Boolean get() = activeAudioPublisherId == selfPeerId && audioSharingEnabled
    val localIsScreenSharer: Boolean get() = activeScreenSharerId == selfPeerId && screenEnabled
    val anyParticipantTalking: Boolean get() = participants.any { it.mic }
    val isBusy: Boolean get() = lifecycle in setOf(RoomLifecycle.Creating, RoomLifecycle.Joining, RoomLifecycle.Leaving) ||
        screenState == MediaLifecycle.Starting || audioShareState == MediaLifecycle.Starting
    val sharingLabel: String get() = when {
        screenEnabled && audioSharingEnabled -> "Sharing audio and video"
        screenEnabled -> "Sharing video"
        audioSharingEnabled -> "Sharing audio"
        activeAudioPublisherId != null && activeScreenSharerId != null -> "Audio and video are live"
        activeAudioPublisherId != null -> "Audio is live"
        activeScreenSharerId != null -> "Video is live"
        else -> "Ready to share audio"
    }

    fun participantName(peerId: String?): String? = peerId?.let { id ->
        participants.firstOrNull { it.peerId == id }?.displayName ?: if (id == selfPeerId) displayName else null
    }
}

data class NearbyRoom(
    val serviceName: String,
    val roomId: String,
    val host: String,
    val port: Int,
    val token: String,
    val hostName: String,
) {
    val qrPayload: com.plaincast.app.qr.QrPayload
        get() = com.plaincast.app.qr.QrPayload(roomId = roomId, host = host, port = port, token = token)
}

fun randomRoomId(): String = randomAlpha(ROOM_ID_LENGTH)
fun randomId(prefix: String): String = "$prefix-${randomAlpha(10)}"
fun randomJoinToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val random = SecureRandom()
private const val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private fun randomAlpha(length: Int): String = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
