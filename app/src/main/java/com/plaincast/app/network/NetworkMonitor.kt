package com.plaincast.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkMonitor(
    context: Context,
    private val onNetworkChanged: (available: Boolean) -> Unit,
) {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged(true)
        override fun onLost(network: Network) = onNetworkChanged(false)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            onNetworkChanged(
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        }
    }

    fun start() {
        if (started) return
        started = true
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        if (!started) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        started = false
    }
}
