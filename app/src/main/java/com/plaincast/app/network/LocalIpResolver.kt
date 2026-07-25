package com.plaincast.app.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIpResolver {
    fun bestLocalIpv4(context: Context): String? {
        val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val activeNetworkAddress = connectivityManager
            ?.activeNetwork
            ?.let(connectivityManager::getLinkProperties)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
        if (activeNetworkAddress != null) return activeNetworkAddress

        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { networkInterface ->
            networkInterface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .map { address ->
                    LanAddressCandidate(
                        interfaceName = networkInterface.name.orEmpty(),
                        address = address.hostAddress.orEmpty(),
                        isSiteLocal = address.isSiteLocalAddress,
                        isUp = runCatching { networkInterface.isUp }.getOrDefault(false),
                        isLoopback = runCatching { networkInterface.isLoopback }.getOrDefault(true) || address.isLoopbackAddress,
                    )
                }
        }
        return LanInterfaceSelector.select(candidates)
    }

}
