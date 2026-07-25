package com.plaincast.app.network

data class LanAddressCandidate(
    val interfaceName: String,
    val address: String,
    val isSiteLocal: Boolean,
    val isUp: Boolean,
    val isLoopback: Boolean,
)

object LanInterfaceSelector {
    fun select(candidates: List<LanAddressCandidate>): String? = candidates
        .asSequence()
        .filter { it.isUp && !it.isLoopback && it.isSiteLocal }
        .filterNot { isExcludedInterface(it.interfaceName) }
        .sortedWith(
            compareBy<LanAddressCandidate> { interfacePriority(it.interfaceName) }
                .thenBy { it.interfaceName }
                .thenBy { it.address }
        )
        .map { it.address }
        .firstOrNull()

    internal fun interfacePriority(name: String): Int {
        val normalized = name.lowercase()
        return when {
            normalized.startsWith("ap") ||
                normalized.contains("softap") ||
                normalized.startsWith("swlan") -> 0
            normalized.startsWith("wlan") || normalized.startsWith("wifi") -> 1
            normalized.startsWith("eth") || normalized.startsWith("en") -> 2
            else -> 3
        }
    }

    internal fun isExcludedInterface(name: String): Boolean {
        val normalized = name.lowercase()
        return EXCLUDED_PREFIXES.any(normalized::startsWith)
    }

    private val EXCLUDED_PREFIXES = listOf(
        "lo",
        "tun",
        "tap",
        "ppp",
        "rmnet",
        "ccmni",
        "pdp",
        "dummy",
        "clat",
        "v4-",
    )
}
