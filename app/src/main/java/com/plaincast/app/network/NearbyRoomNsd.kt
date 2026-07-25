package com.plaincast.app.network

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.annotation.RequiresApi
import com.plaincast.app.model.NearbyRoom
import com.plaincast.app.signaling.PROTOCOL_VERSION
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SERVICE_TYPE = "_plaincast._tcp."

class NearbyRoomAdvertiser(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null
    private var current: Advertisement? = null

    data class Advertisement(
        val roomId: String,
        val port: Int,
        val webPort: Int,
        val token: String,
        val hostName: String,
    )

    @Synchronized
    fun publish(value: Advertisement) {
        if (current == value && listener != null) return
        unregisterLocked()
        current = value
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@NearbyRoomAdvertiser) {
                    if (listener === this) listener = null
                }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registrationListener
        val info = NsdServiceInfo().apply {
            serviceName = "PlainCast-${value.roomId}"
            serviceType = SERVICE_TYPE
            port = value.port
            setAttribute("room", value.roomId)
            setAttribute("token", value.token)
            setAttribute("host", value.hostName.take(40))
            setAttribute("webPort", value.webPort.toString())
            setAttribute("protocol", PROTOCOL_VERSION.toString())
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    @Synchronized
    override fun close() {
        current = null
        unregisterLocked()
    }

    private fun unregisterLocked() {
        val active = listener ?: return
        listener = null
        runCatching { manager.unregisterService(active) }
    }
}

class NearbyRoomDiscovery(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _rooms = MutableStateFlow<List<NearbyRoom>>(emptyList())
    val rooms: StateFlow<List<NearbyRoom>> = _rooms.asStateFlow()

    private val resolved = ConcurrentHashMap<String, NearbyRoom>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serviceInfoCallback: NsdManager.ServiceInfoCallback? = null
    private var resolving = false
    private var generation = 0L

    @Synchronized
    fun start() {
        if (discoveryListener != null) return
        val activeGeneration = ++generation
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@NearbyRoomDiscovery) {
                    if (generation == activeGeneration && discoveryListener === this) discoveryListener = null
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(this@NearbyRoomDiscovery) {
                    if (generation == activeGeneration && discoveryListener === this) discoveryListener = null
                }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(this@NearbyRoomDiscovery) {
                    if (generation != activeGeneration || discoveryListener !== this) return
                    if (serviceInfo.serviceType != SERVICE_TYPE) return
                    enqueueResolve(serviceInfo, activeGeneration)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(this@NearbyRoomDiscovery) {
                    if (generation != activeGeneration || discoveryListener !== this) return
                    resolved.remove(serviceInfo.serviceName)
                    publishRooms()
                }
            }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                if (generation == activeGeneration && discoveryListener === listener) discoveryListener = null
            }
    }

    @Synchronized
    fun refresh() {
        closeDiscoveryLocked(clearRooms = true)
        start()
    }

    @Synchronized
    override fun close() {
        closeDiscoveryLocked(clearRooms = true)
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo, activeGeneration: Long) {
        if (generation != activeGeneration) return
        if (resolved.containsKey(info.serviceName) || resolveQueue.any { it.serviceName == info.serviceName }) return
        if (resolved.size + resolveQueue.size >= MAX_DISCOVERED_ROOMS) return
        resolveQueue.addLast(info)
        resolveNextLocked(activeGeneration)
    }

    @Synchronized
    private fun resolveNextLocked(activeGeneration: Long) {
        if (generation != activeGeneration || discoveryListener == null || resolving) return
        val next = if (resolveQueue.isEmpty()) null else resolveQueue.removeFirst()
        next ?: return
        resolving = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveServiceLegacy(next, activeGeneration)
            return
        }
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finishResolution(activeGeneration, this)

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val room = decode(serviceInfo)
                synchronized(this@NearbyRoomDiscovery) {
                    if (generation != activeGeneration) return
                    if (room != null) resolved[serviceInfo.serviceName] = room
                    publishRooms()
                    finishResolution(activeGeneration, this)
                }
            }

            override fun onServiceLost() = finishResolution(activeGeneration, this)
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        serviceInfoCallback = callback
        runCatching { manager.registerServiceInfoCallback(next, DIRECT_EXECUTOR, callback) }.onFailure {
            serviceInfoCallback = null
            resolving = false
            resolveNextLocked(activeGeneration)
            return
        }
    }

    @Synchronized
    private fun finishResolution(
        activeGeneration: Long,
        callback: NsdManager.ServiceInfoCallback? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callback?.let(::unregisterServiceInfoCallbackLocked)
        }
        if (generation == activeGeneration) {
            resolving = false
            resolveNextLocked(activeGeneration)
        }
    }

    private fun resolveServiceLegacy(info: NsdServiceInfo, activeGeneration: Long) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finishResolution(activeGeneration)

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val room = decode(serviceInfo)
                synchronized(this@NearbyRoomDiscovery) {
                    if (generation != activeGeneration) return
                    if (room != null) resolved[serviceInfo.serviceName] = room
                    publishRooms()
                    finishResolution(activeGeneration)
                }
            }
        }
        runCatching {
            NsdManager::class.java
                .getMethod("resolveService", NsdServiceInfo::class.java, NsdManager.ResolveListener::class.java)
                .invoke(manager, info, listener)
        }.onFailure { finishResolution(activeGeneration) }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterServiceInfoCallbackLocked(callback: NsdManager.ServiceInfoCallback) {
        if (serviceInfoCallback !== callback) return
        serviceInfoCallback = null
        runCatching { manager.unregisterServiceInfoCallback(callback) }
    }

    private fun decode(info: NsdServiceInfo): NearbyRoom? {
        val attributes = info.attributes
        fun attribute(name: String): String? = attributes[name]?.toString(StandardCharsets.UTF_8)
        val protocol = attribute("protocol")?.toIntOrNull() ?: return null
        if (protocol != PROTOCOL_VERSION) return null
        val roomId = attribute("room")?.uppercase()?.takeIf { it.matches(Regex("[A-Z2-9]{4}")) } ?: return null
        val token = attribute("token")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
        val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            runCatching {
                (NsdServiceInfo::class.java.getMethod("getHost").invoke(info) as? InetAddress)?.hostAddress
            }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: return null
        val port = info.port.takeIf { it in 1..65_535 } ?: return null
        attribute("webPort")?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        val hostName = attribute("host")?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: return null
        return NearbyRoom(
            serviceName = info.serviceName,
            roomId = roomId,
            host = hostAddress,
            port = port,
            token = token,
            hostName = hostName,
        )
    }

    @Synchronized
    private fun publishRooms() {
        _rooms.value = resolved.values.sortedBy { it.hostName.lowercase() }
    }

    private fun closeDiscoveryLocked(clearRooms: Boolean) {
        generation += 1
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discoveryListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallback?.let(::unregisterServiceInfoCallbackLocked)
        }
        serviceInfoCallback = null
        resolveQueue.clear()
        resolving = false
        if (clearRooms) {
            resolved.clear()
            _rooms.value = emptyList()
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = java.util.concurrent.Executor { runnable -> runnable.run() }
        const val MAX_DISCOVERED_ROOMS = 32
    }
}
