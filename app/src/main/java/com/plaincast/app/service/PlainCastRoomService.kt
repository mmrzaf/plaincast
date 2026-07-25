package com.plaincast.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.plaincast.app.MainActivity
import com.plaincast.app.PlainCastApplication
import com.plaincast.app.R
import com.plaincast.app.audio.AudioRouteManager
import com.plaincast.app.network.NearbyRoomDiscovery
import com.plaincast.app.qr.QrPayload
import com.plaincast.app.room.ForegroundNeeds
import com.plaincast.app.room.RoomController

class PlainCastRoomService : Service() {
    inner class LocalBinder : Binder() {
        val service: PlainCastRoomService get() = this@PlainCastRoomService
    }

    private val binder = LocalBinder()
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var controller: RoomController
    private lateinit var nearbyRoomDiscovery: NearbyRoomDiscovery
    private var lastForegroundNeeds = ForegroundNeeds(false, false, false, "PlainCast")

    val room get() = controller.room
    val remoteVideo get() = controller.remoteVideo
    val nearbyRooms get() = nearbyRoomDiscovery.rooms

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val runtime = application as PlainCastApplication
        audioRouteManager = AudioRouteManager(this, runtime.diagnostics).also { it.start() }
        nearbyRoomDiscovery = NearbyRoomDiscovery(this).also { it.start() }
        controller = RoomController(
            context = this,
            diagnostics = runtime.diagnostics,
            audioRouteManager = audioRouteManager,
            onForegroundNeedsChanged = ::applyForegroundNeeds,
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUDIO -> {
                startAudioCaptureFromConsent(intent)
                return START_NOT_STICKY
            }
            ACTION_STOP_AUDIO -> controller.stopAudioSharing()
            ACTION_STOP_SCREEN -> controller.stopScreenSharing()
            ACTION_STOP_TALKING -> controller.setPushToTalk(false)
            ACTION_LEAVE_ROOM -> controller.leaveRoom()
        }
        if (lastForegroundNeeds.roomActive) applyForegroundNeeds(lastForegroundNeeds)
        else applyForegroundNeeds(ForegroundNeeds(true, false, false, "Starting PlainCast"))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.close()
        nearbyRoomDiscovery.close()
        audioRouteManager.stop()
        super.onDestroy()
    }

    fun createRoom(displayName: String) {
        ensureStarted("Creating local room")
        controller.createRoom(displayName)
    }

    fun joinRoom(payload: QrPayload, displayName: String) {
        ensureStarted("Joining local room")
        controller.joinRoom(payload, displayName)
    }

    fun joinManual(host: String, port: Int, roomId: String, token: String, displayName: String) {
        ensureStarted("Joining local room")
        controller.joinManual(host, port, roomId, token, displayName)
    }

    fun setPushToTalk(active: Boolean) {
        if (active && !lastForegroundNeeds.microphone) {
            ensureStarted(lastForegroundNeeds.notificationText, microphone = true)
        }
        controller.setPushToTalk(active)
    }

    fun startScreenShare(data: Intent) {
        ensureStarted("Starting screen share", projection = true)
        controller.startScreenShare(data)
    }

    fun startAudioShare(resultCode: Int, data: Intent) {
        val command = Intent(this, PlainCastRoomService::class.java)
            .setAction(ACTION_START_AUDIO)
            .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
            .putExtra(EXTRA_PROJECTION_DATA, data)
        runCatching { ContextCompat.startForegroundService(this, command) }
            .onFailure { error ->
                controller.audioShareStartFailed(error.message ?: "Android could not start the audio capture service.")
            }
    }

    fun stopSharing() = controller.stopSharing()
    fun stopAudioSharing() = controller.stopAudioSharing()
    fun stopScreenSharing() = controller.stopScreenSharing()
    fun leaveRoom() = controller.leaveRoom()
    fun removeParticipant(peerId: String) = controller.removeParticipant(peerId)
    fun stopActiveAudioPublisher() = controller.stopActiveAudioPublisher()
    fun selectCommunicationRoute(deviceId: Int) = controller.selectCommunicationRoute(deviceId)
    fun clearCommunicationRoute() = controller.clearCommunicationRoute()
    fun refreshAudioRoutes() = controller.refreshAudioRoutes()
    fun resetDiagnostics() = controller.resetDiagnostics()
    fun refreshNearbyRooms() = nearbyRoomDiscovery.refresh()
    fun showStatus(message: String) = controller.showStatus(message)


    private fun startAudioCaptureFromConsent(intent: Intent) {
        val needs = ForegroundNeeds(
            roomActive = true,
            microphone = lastForegroundNeeds.microphone,
            projection = true,
            notificationText = "Starting audio sharing",
        )
        // Android 14+ requires the service to be promoted with the mediaProjection
        // type before MediaProjectionManager.getMediaProjection() is called.
        val promoted = runCatching { applyForegroundNeeds(needs) }
        if (promoted.isFailure) {
            controller.audioShareStartFailed(
                promoted.exceptionOrNull()?.message ?: "Android could not promote the media-projection service.",
            )
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
        val consentData = projectionConsentData(intent)
        if (resultCode != Activity.RESULT_OK || consentData == null) {
            controller.audioShareStartFailed("Android did not provide a valid capture permission result.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            controller.audioShareStartFailed("Shared audio requires Android 10 or newer.")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(resultCode, consentData) }.getOrElse { error ->
            controller.audioShareStartFailed(error.message ?: "Android rejected the capture session.")
            return
        }
        if (projection == null) {
            controller.audioShareStartFailed("Android did not create a capture session.")
            return
        }
        controller.startAudioShare(projection)
    }

    @Suppress("DEPRECATION")
    private fun projectionConsentData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

    private fun ensureStarted(text: String, microphone: Boolean = false, projection: Boolean = false) {
        val needs = ForegroundNeeds(
            roomActive = true,
            microphone = microphone || lastForegroundNeeds.microphone,
            projection = projection || lastForegroundNeeds.projection,
            notificationText = text,
        )
        lastForegroundNeeds = needs
        ContextCompat.startForegroundService(this, Intent(this, PlainCastRoomService::class.java).setAction(ACTION_KEEP_ALIVE))
        applyForegroundNeeds(needs)
    }

    private fun applyForegroundNeeds(needs: ForegroundNeeds) {
        lastForegroundNeeds = needs
        if (!needs.roomActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        var types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        if (needs.microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (needs.projection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(needs.notificationText),
            types,
        )
    }

    private fun notification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PlainCast")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
        if (::controller.isInitialized) {
            val state = controller.room.value
            if (state.micEnabled) builder.addAction(0, "Stop talking", serviceAction(ACTION_STOP_TALKING, 1))
            if (state.audioSharingEnabled) builder.addAction(0, "Stop audio", serviceAction(ACTION_STOP_AUDIO, 2))
            if (state.screenEnabled) builder.addAction(0, "Stop screen", serviceAction(ACTION_STOP_SCREEN, 3))
            builder.addAction(0, if (state.isHost) "End room" else "Leave", serviceAction(ACTION_LEAVE_ROOM, 4))
        }
        return builder.build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, PlainCastRoomService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.plaincast_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.plaincast_channel_description) }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "plaincast_room"
        private const val NOTIFICATION_ID = 7412
        private const val ACTION_KEEP_ALIVE = "com.plaincast.app.KEEP_ROOM_ACTIVE"
        private const val ACTION_START_AUDIO = "com.plaincast.app.START_AUDIO_SHARING"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val ACTION_STOP_AUDIO = "com.plaincast.app.STOP_AUDIO_SHARING"
        private const val ACTION_STOP_SCREEN = "com.plaincast.app.STOP_SCREEN_SHARING"
        private const val ACTION_STOP_TALKING = "com.plaincast.app.STOP_TALKING"
        private const val ACTION_LEAVE_ROOM = "com.plaincast.app.LEAVE_ROOM"

        fun intent(context: Context): Intent = Intent(context, PlainCastRoomService::class.java)
    }
}
