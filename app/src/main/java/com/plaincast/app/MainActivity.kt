package com.plaincast.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import com.plaincast.app.ui.PlainCastRoot

class MainActivity : ComponentActivity() {
    private val viewModel: PlainCastViewModel by viewModels()

    private data class PendingPermissionRequest(
        val required: Set<String>,
        val onReady: () -> Unit,
    )

    private var pendingPermissionRequest: PendingPermissionRequest? = null
    private var pushToTalkHeld = false

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val pending = pendingPermissionRequest
        pendingPermissionRequest = null
        if (pending == null) return@registerForActivityResult

        val deniedRequired = pending.required.filter { permission ->
            grants[permission] != true &&
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (deniedRequired.isEmpty()) {
            pending.onReady()
        } else {
            pushToTalkHeld = false
            viewModel.setPushToTalk(false)
            viewModel.showStatus("Permission needed: ${deniedRequired.joinToString { it.substringAfterLast('.') }}")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startScreenShare(data)
        } else {
            viewModel.showStatus("Screen sharing was cancelled.")
        }
    }

    private val audioCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startAudioShare(result.resultCode, data)
        } else {
            viewModel.showStatus("Audio sharing was cancelled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlainCastRoot(
                viewModel = viewModel,
                onCreateRoom = { name ->
                    withPermissions(roomEntryPermissions(), setOf(Manifest.permission.RECORD_AUDIO)) { viewModel.createRoom(name) }
                },
                onOpenQrScanner = { openScanner ->
                    withPermissions(cameraPermissions(), cameraPermissions().toSet()) { openScanner() }
                },
                onJoinRoom = { payload, name ->
                    withPermissions(roomEntryPermissions(), setOf(Manifest.permission.RECORD_AUDIO)) { viewModel.joinRoom(payload, name) }
                },
                onManualJoin = { host, port, roomId, token, name ->
                    withPermissions(roomEntryPermissions(), setOf(Manifest.permission.RECORD_AUDIO)) { viewModel.joinManual(host, port, roomId, token, name) }
                },
                onStartScreenShare = { launchProjectionPrompt(screenCaptureLauncher) },
                onStartAudioShare = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        viewModel.showStatus("Audio sharing requires Android 10 or newer.")
                    } else {
                        launchProjectionPrompt(audioCaptureLauncher)
                    }
                },
                onPushToTalk = ::setPushToTalk,
                onRequestBluetoothPermission = ::requestBluetoothRoutePermission,
            )
        }
    }

    override fun onStop() {
        pushToTalkHeld = false
        viewModel.setPushToTalk(false)
        super.onStop()
    }

    private fun setPushToTalk(active: Boolean) {
        pushToTalkHeld = active
        if (!active) {
            viewModel.setPushToTalk(false)
            return
        }
        withPermissions(
            permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
            requiredPermissions = setOf(Manifest.permission.RECORD_AUDIO),
        ) {
            if (pushToTalkHeld) {
                viewModel.setPushToTalk(true)
            } else {
                viewModel.showStatus("Microphone ready. Hold to talk again.")
            }
        }
    }

    private fun requestBluetoothRoutePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            viewModel.refreshAudioRoutes()
            return
        }
        withPermissions(
            permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            requiredPermissions = emptySet(),
            onReady = viewModel::refreshAudioRoutes,
        )
    }

    private fun launchProjectionPrompt(launcher: ActivityResultLauncher<Intent>) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }

    private fun withPermissions(
        permissions: Array<String>,
        requiredPermissions: Set<String>,
        onReady: () -> Unit,
    ) {
        if (pendingPermissionRequest != null) {
            viewModel.showStatus("Finish the current permission request first.")
            return
        }
        val needed = permissions.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            onReady()
            return
        }
        pendingPermissionRequest = PendingPermissionRequest(requiredPermissions, onReady)
        requestPermissions.launch(needed.toTypedArray())
    }

    private fun cameraPermissions(): Array<String> = arrayOf(Manifest.permission.CAMERA)

    private fun roomEntryPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
