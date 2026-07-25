package com.plaincast.app.signaling

data class AudioPublisherAuthoritySnapshot(
    val activePeerId: String? = null,
    val generation: Long = 0,
)

data class AudioPublisherTransition(
    val previousPeerId: String?,
    val currentPeerId: String?,
    val generation: Long,
    val reason: String,
)

sealed interface AudioPublisherAuthorityEvent {
    data class PublisherChanged(val transition: AudioPublisherTransition) : AudioPublisherAuthorityEvent
    data class RequestRejected(
        val peerId: String,
        val activePeerId: String,
        val reason: String,
    ) : AudioPublisherAuthorityEvent
}

/**
 * Conservative single-publisher authority.
 *
 * A publisher may start only when the slot is empty. PlainCast deliberately does not perform
 * live takeovers: the current publisher must stop first. This avoids destroying a working stream
 * while a replacement device is still requesting Android capture permission or preparing audio.
 */
class AudioPublisherAuthority {
    private var activePeerId: String? = null
    private var generation = 0L

    @Synchronized
    fun snapshot(): AudioPublisherAuthoritySnapshot = AudioPublisherAuthoritySnapshot(activePeerId, generation)

    @Synchronized
    fun request(peerId: String, reason: String): List<AudioPublisherAuthorityEvent> {
        require(peerId.isNotBlank())
        val current = activePeerId
        if (current == peerId) return emptyList()
        if (current != null) {
            return listOf(AudioPublisherAuthorityEvent.RequestRejected(peerId, current, "publisher_busy"))
        }
        return listOf(changePublisher(peerId, reason))
    }

    @Synchronized
    fun stop(peerId: String?, force: Boolean, reason: String): List<AudioPublisherAuthorityEvent> {
        if (activePeerId == null) return emptyList()
        if (!force && activePeerId != peerId) return emptyList()
        return listOf(changePublisher(null, reason))
    }

    @Synchronized
    fun disconnect(peerId: String): List<AudioPublisherAuthorityEvent> =
        if (activePeerId == peerId) listOf(changePublisher(null, "publisher_disconnected")) else emptyList()

    private fun changePublisher(peerId: String?, reason: String): AudioPublisherAuthorityEvent.PublisherChanged {
        val previous = activePeerId
        check(previous != peerId) { "Audio publisher did not change." }
        activePeerId = peerId
        generation++
        return AudioPublisherAuthorityEvent.PublisherChanged(
            AudioPublisherTransition(previous, peerId, generation, reason)
        )
    }
}
