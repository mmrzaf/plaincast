package com.plaincast.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plaincast.app.model.ConnectionHealth
import com.plaincast.app.model.NearbyRoom
import com.plaincast.app.model.RoomLifecycle
import com.plaincast.app.model.RoomState
import com.plaincast.app.qr.QrPayload
import com.plaincast.app.rtc.RemoteVideoSink
import com.plaincast.app.service.PlainCastRoomService
import java.util.ArrayDeque
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlainCastViewModel(private val app: Application) : AndroidViewModel(app) {
    private val runtime = app as PlainCastApplication
    private val preferences = app.getSharedPreferences("plaincast_user", Context.MODE_PRIVATE)
    private val _room = MutableStateFlow(RoomState(displayName = savedDisplayName()))
    val room: StateFlow<RoomState> = _room.asStateFlow()
    val diagnostics = runtime.diagnostics.state
    val remoteVideo = RemoteVideoSink()
    private val _nearbyRooms = MutableStateFlow<List<NearbyRoom>>(emptyList())
    val nearbyRooms: StateFlow<List<NearbyRoom>> = _nearbyRooms.asStateFlow()

    private val pendingCommands = ArrayDeque<(PlainCastRoomService) -> Unit>()
    private var service: PlainCastRoomService? = null
    private var bound = false
    private var roomCollection: Job? = null
    private var videoCollection: Job? = null
    private var nearbyRoomsCollection: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val roomBinder = binder as? PlainCastRoomService.LocalBinder ?: return
            val connectedService = roomBinder.service
            service = connectedService
            bound = true
            roomCollection?.cancel()
            videoCollection?.cancel()
            nearbyRoomsCollection?.cancel()
            roomCollection = viewModelScope.launch {
                connectedService.room.collect { _room.value = it }
            }
            videoCollection = viewModelScope.launch {
                connectedService.remoteVideo.track.collect(remoteVideo::set)
            }
            nearbyRoomsCollection = viewModelScope.launch {
                connectedService.nearbyRooms.collect { _nearbyRooms.value = it }
            }
            while (pendingCommands.isNotEmpty()) {
                runCatching { pendingCommands.removeFirst().invoke(connectedService) }
                    .onFailure { error -> connectedService.showStatus(error.message ?: "PlainCast command failed.") }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            roomCollection?.cancel()
            videoCollection?.cancel()
            nearbyRoomsCollection?.cancel()
            remoteVideo.set(null)
            _room.value = room.value.copy(
                lifecycle = RoomLifecycle.Failed,
                connectionHealth = ConnectionHealth.Disconnected,
                status = "PlainCast room service stopped unexpectedly.",
            )
            bindService()
        }
    }

    init {
        bindService()
    }

    fun createRoom(displayName: String = savedDisplayName()) {
        val cleanName = persistDisplayName(displayName)
        withService { it.createRoom(cleanName) }
    }
    fun joinRoom(payload: QrPayload, displayName: String = savedDisplayName()) {
        val cleanName = persistDisplayName(displayName)
        withService { it.joinRoom(payload, cleanName) }
    }
    fun joinManual(host: String, port: Int, roomId: String, token: String, displayName: String = savedDisplayName()) {
        val cleanName = persistDisplayName(displayName)
        withService { it.joinManual(host, port, roomId, token, cleanName) }
    }

    fun setPushToTalk(active: Boolean) = withService { it.setPushToTalk(active) }
    fun startScreenShare(data: Intent) = withService { it.startScreenShare(data) }

    fun startAudioShare(resultCode: Int, data: Intent) =
        withService { it.startAudioShare(resultCode, data) }

    fun stopSharing() = withService(PlainCastRoomService::stopSharing)
    fun leaveRoom() = withService(PlainCastRoomService::leaveRoom)
    fun removeParticipant(peerId: String) = withService { it.removeParticipant(peerId) }
    fun stopAudioSharing() = withService(PlainCastRoomService::stopAudioSharing)
    fun stopScreenSharing() = withService(PlainCastRoomService::stopScreenSharing)
    fun stopActiveAudioPublisher() = withService(PlainCastRoomService::stopActiveAudioPublisher)
    fun selectCommunicationRoute(deviceId: Int) = withService { it.selectCommunicationRoute(deviceId) }
    fun clearCommunicationRoute() = withService(PlainCastRoomService::clearCommunicationRoute)
    fun refreshAudioRoutes() = withService(PlainCastRoomService::refreshAudioRoutes)
    fun resetDiagnostics() = withService(PlainCastRoomService::resetDiagnostics)
    fun refreshNearbyRooms() = withService(PlainCastRoomService::refreshNearbyRooms)
    fun showStatus(message: String) = withService { it.showStatus(message) }

    fun qrPayload(): QrPayload? = room.value.takeIf { it.isHost && it.isConnected }?.let {
        QrPayload(roomId = it.roomId, host = it.hostAddress, port = it.port, token = it.joinToken)
    }

    fun browserUrl(): String? = room.value.takeIf { it.isHost && it.isConnected }?.browserUrl

    override fun onCleared() {
        runCatching { service?.setPushToTalk(false) }
        roomCollection?.cancel()
        videoCollection?.cancel()
        nearbyRoomsCollection?.cancel()
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service = null
        super.onCleared()
    }

    private fun bindService() {
        if (bound) return
        bound = app.bindService(
            PlainCastRoomService.intent(app),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            _room.value = room.value.copy(
                lifecycle = RoomLifecycle.Failed,
                status = "Could not connect to the PlainCast room service.",
            )
        }
    }

    private fun withService(command: (PlainCastRoomService) -> Unit) {
        val connected = service
        if (connected != null) {
            command(connected)
        } else {
            if (pendingCommands.size >= MAX_PENDING_COMMANDS) pendingCommands.removeFirst()
            pendingCommands.addLast(command)
            bindService()
        }
    }

    private fun savedDisplayName(): String = preferences.getString("display_name", null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName()

    private fun persistDisplayName(value: String): String {
        val clean = value.trim().take(40).ifBlank { defaultDeviceName() }
        preferences.edit().putString("display_name", clean).apply()
        return clean
    }

    private fun defaultDeviceName(): String = Build.MODEL ?: "Android"

    private companion object {
        const val MAX_PENDING_COMMANDS = 16
    }
}
