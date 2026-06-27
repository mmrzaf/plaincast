package com.plaincast.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.plaincast.app.service.PlainCastActiveService
import com.plaincast.app.ui.PlainCastRoot

class MainActivity : ComponentActivity() {
    private val viewModel: PlainCastViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenShare(result.resultCode, result.data!!)
        } else {
            viewModel.showStatus("Screen sharing was cancelled.")
        }
    }

    private val audioCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            viewModel.startDeviceAudio(result.resultCode, result.data!!, manager)
        } else {
            viewModel.showStatus("Device audio sharing was cancelled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBasePermissions()
        setContent {
            PlainCastRoot(
                viewModel = viewModel,
                onRequestPermissions = { ensureBasePermissions() },
                onStartScreenShare = {
                    ensureBasePermissions()
                    launchProjectionPrompt(
                        launcher = screenCaptureLauncher,
                        notificationText = "Preparing screen share"
                    )
                },
                onStartDeviceAudio = {
                    ensureBasePermissions()
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        viewModel.showStatus("Device audio requires Android 10 or newer.")
                    } else {
                        launchProjectionPrompt(
                            launcher = audioCaptureLauncher,
                            notificationText = "Preparing device audio"
                        )
                    }
                },
                onStopSharing = { viewModel.stopSharing() }
            )
        }
    }

    private fun launchProjectionPrompt(
        launcher: ActivityResultLauncher<android.content.Intent>,
        notificationText: String,
    ) {
        PlainCastActiveService.start(
            context = this,
            text = notificationText,
            mic = true,
            projection = true
        )
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Give the foreground service one main-loop turn to enter foreground before Android returns the token.
        window.decorView.post { launcher.launch(manager.createScreenCaptureIntent()) }
    }

    private fun ensureBasePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }
}
