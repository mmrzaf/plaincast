package com.plaincast.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.plaincast.app.R

class PlainCastActiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Local room active"
        val needsMic = intent?.getBooleanExtra(EXTRA_MIC, true) ?: true
        val needsProjection = intent?.getBooleanExtra(EXTRA_PROJECTION, false) ?: false
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            var types = 0
            if (needsMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (needsProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("PlainCast")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.plaincast_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.plaincast_channel_description) }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "plaincast_active"
        private const val NOTIFICATION_ID = 7412
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_MIC = "mic"
        private const val EXTRA_PROJECTION = "projection"

        fun start(context: Context, text: String = "Local room active", mic: Boolean = true, projection: Boolean = false) {
            val intent = Intent(context, PlainCastActiveService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_MIC, mic)
                .putExtra(EXTRA_PROJECTION, projection)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlainCastActiveService::class.java))
        }
    }
}
