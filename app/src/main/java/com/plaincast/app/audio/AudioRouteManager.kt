package com.plaincast.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.plaincast.app.diagnostics.AudioRouteDevice
import com.plaincast.app.diagnostics.AudioRouteDiagnostics
import com.plaincast.app.diagnostics.DiagnosticsRepository

class AudioRouteManager(
    context: Context,
    private val diagnostics: DiagnosticsRepository,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var originalMode: Int? = null
    private var started = false
    private var userSelectedDeviceId: Int? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refresh()
    }

    fun stop() {
        if (!started) return
        deactivateCommunicationMode()
        userSelectedDeviceId = null
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        started = false
    }

    fun activateCommunicationMode() {
        if (originalMode != null) {
            publishSnapshot()
            return
        }
        originalMode = audioManager.mode
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        applyPreferredRoute()
        publishSnapshot()
    }

    fun deactivateCommunicationMode() {
        val restoreMode = originalMode ?: run {
            publishSnapshot()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        if (audioManager.mode != restoreMode) audioManager.mode = restoreMode
        originalMode = null
        publishSnapshot()
    }

    fun selectCommunicationDevice(deviceId: Int): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            error("Explicit audio-route selection requires Android 12 or newer.")
        }
        selectCommunicationDeviceApi31(deviceId)
    }.onFailure { error ->
        diagnostics.updateAudioRoute(snapshot(lastError = routeError(error, "Could not select audio route.")))
    }

    fun clearCommunicationDevice(): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            error("Explicit audio-route selection requires Android 12 or newer.")
        }
        clearCommunicationDeviceApi31()
    }.onFailure { error ->
        diagnostics.updateAudioRoute(snapshot(lastError = routeError(error, "Could not clear audio route.")))
    }

    fun refresh() {
        if (originalMode != null) applyPreferredRoute()
        publishSnapshot()
    }

    private fun applyPreferredRoute() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        applyPreferredRouteApi31()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun selectCommunicationDeviceApi31(deviceId: Int) {
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == deviceId }
            ?: error("Selected audio route is no longer available.")
        check(audioManager.setCommunicationDevice(device)) {
            "Android rejected the selected communication route."
        }
        userSelectedDeviceId = device.id
        publishSnapshot()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun clearCommunicationDeviceApi31() {
        userSelectedDeviceId = null
        audioManager.clearCommunicationDevice()
        applyPreferredRouteApi31()
        publishSnapshot()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyPreferredRouteApi31() {
        val available = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        if (available.isEmpty()) return
        val requested = userSelectedDeviceId?.let { id -> available.firstOrNull { it.id == id } }
        if (userSelectedDeviceId != null && requested == null) userSelectedDeviceId = null
        val target = requested ?: available.minByOrNull(::routePriority) ?: return
        if (audioManager.communicationDevice?.id == target.id) return
        runCatching { audioManager.setCommunicationDevice(target) }
            .onFailure { error -> diagnostics.updateAudioRoute(snapshot(lastError = routeError(error, "Could not select the preferred audio route."))) }
    }

    private fun publishSnapshot() {
        diagnostics.updateAudioRoute(snapshot())
    }

    private fun snapshot(lastError: String? = null): AudioRouteDiagnostics {
        val bluetoothPermissionGranted = hasBluetoothPermission()
        val inputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::toRouteDevice)
        }.getOrDefault(emptyList())
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::toRouteDevice)
        }.getOrDefault(emptyList())
        val availableCommunicationDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val activeCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.communicationDevice }.getOrNull()
        } else {
            null
        }
        return AudioRouteDiagnostics(
            mode = audioModeLabel(audioManager.mode),
            bluetoothPermissionGranted = bluetoothPermissionGranted,
            routeSelectionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            selectionMode = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "Android default"
                userSelectedDeviceId != null -> "Manual"
                else -> "Auto"
            },
            selectedCommunicationDeviceId = activeCommunicationDevice?.id,
            selectedCommunicationDeviceName = activeCommunicationDevice?.productName?.toString(),
            availableCommunicationDevices = availableCommunicationDevices.map(::toRouteDevice).distinctBy { it.id },
            inputDevices = inputs.distinctBy { it.id },
            outputDevices = outputs.distinctBy { it.id },
            lastError = lastError,
        )
    }

    private fun routeError(error: Throwable, fallback: String): String =
        if (error is SecurityException && !hasBluetoothPermission()) {
            "Nearby devices permission is required for this Bluetooth communication route."
        } else {
            error.message ?: fallback
        }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun toRouteDevice(device: AudioDeviceInfo): AudioRouteDevice = AudioRouteDevice(
        id = device.id,
        name = device.productName.toString().takeIf { it.isNotBlank() } ?: audioDeviceTypeLabel(device.type),
        type = audioDeviceTypeLabel(device.type),
        isInput = device.isSource,
        isOutput = device.isSink,
    )

    private fun routePriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 2
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 3
        else -> 4
    }

    private fun audioModeLabel(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "MODE_$mode"
    }

    private fun audioDeviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV tuner"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
        AudioDeviceInfo.TYPE_IP -> "IP audio"
        AudioDeviceInfo.TYPE_BUS -> "Audio bus"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Safe speaker"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote submix"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE broadcast"
        else -> "Audio device $type"
    }
}
