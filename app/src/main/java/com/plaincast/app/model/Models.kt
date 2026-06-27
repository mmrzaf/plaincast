package com.plaincast.app.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom

const val DEFAULT_PORT = 7412
const val ROOM_ID_LENGTH = 4

@Serializable
data class Participant(
    val peerId: String,
    val displayName: String,
    val role: Role = Role.PARTICIPANT,
    val mic: Boolean = false,
    val screen: Boolean = false,
    val deviceAudio: Boolean = false,
)

@Serializable
enum class Role { HOST, PARTICIPANT }

@Serializable
data class RoomState(
    val roomId: String = "",
    val token: String = "",
    val hostAddress: String = "",
    val port: Int = DEFAULT_PORT,
    val selfPeerId: String = randomId("peer"),
    val displayName: String = "Android",
    val isHost: Boolean = false,
    val isConnected: Boolean = false,
    val micEnabled: Boolean = false,
    val screenEnabled: Boolean = false,
    val deviceAudioEnabled: Boolean = false,
    val status: String = "Idle",
    val participants: List<Participant> = emptyList(),
) {
    val joinUrl: String get() = "ws://$hostAddress:$port"
    val isSharing: Boolean get() = screenEnabled || deviceAudioEnabled
    val sharingLabel: String get() = when {
        screenEnabled && deviceAudioEnabled -> "Sharing screen and device audio"
        screenEnabled -> "Sharing screen"
        deviceAudioEnabled -> "Sharing device audio"
        else -> "No screen share active"
    }
}

fun randomRoomId(): String = randomAlpha(ROOM_ID_LENGTH)
fun randomToken(): String = randomAlpha(24)
fun randomId(prefix: String): String = "$prefix-${randomAlpha(10)}"

private val random = SecureRandom()
private const val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private fun randomAlpha(length: Int): String = buildString {
    repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
}
