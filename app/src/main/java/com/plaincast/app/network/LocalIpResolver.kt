package com.plaincast.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIpResolver {
    fun bestLocalIpv4(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiIp = wifi?.connectionInfo?.ipAddress?.takeIf { it != 0 }?.let { intToIp(it) }
        if (!wifiIp.isNullOrBlank() && !wifiIp.startsWith("0.")) return wifiIp
        return interfacesIpv4().firstOrNull()
    }

    fun isLikelyOnWifiOrHotspot(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun intToIp(ip: Int): String = listOf(
        ip and 0xff,
        ip shr 8 and 0xff,
        ip shr 16 and 0xff,
        ip shr 24 and 0xff,
    ).joinToString(".")

    private fun interfacesIpv4(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress ?: "" }
        .filter { it.isNotBlank() && !it.startsWith("127.") }
}
