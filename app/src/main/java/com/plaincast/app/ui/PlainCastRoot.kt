package com.plaincast.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.plaincast.app.PlainCastViewModel
import com.plaincast.app.model.RoomState
import com.plaincast.app.qr.QrCodec
import com.plaincast.app.qr.QrGenerator
import com.plaincast.app.qr.QrPayload
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun PlainCastRoot(
    viewModel: PlainCastViewModel,
    onRequestPermissions: () -> Unit,
    onStartScreenShare: () -> Unit,
    onStartDeviceAudio: () -> Unit,
    onStopSharing: () -> Unit,
) {
    PlainCastTheme {
        Surface(Modifier.fillMaxSize()) {
            val room by viewModel.room.collectAsState()
            var screen by remember { mutableStateOf("home") }
            LaunchedEffect(room.isConnected) { if (room.isConnected) screen = "room" }
            when (screen) {
                "join" -> JoinScreen(
                    onBack = { screen = "home" },
                    onJoin = { payload, name -> viewModel.joinRoom(payload, name) },
                    onManualJoin = { host, port, roomId, token, name -> viewModel.joinManual(host, port, roomId, token, name) }
                )
                "room" -> RoomScreen(
                    viewModel = viewModel,
                    room = room,
                    onStartScreenShare = onStartScreenShare,
                    onStartDeviceAudio = onStartDeviceAudio,
                    onStopSharing = onStopSharing,
                    onLeave = { viewModel.leaveRoom(); screen = "home" }
                )
                else -> HomeScreen(
                    room = room,
                    onRequestPermissions = onRequestPermissions,
                    onCreate = { name -> viewModel.createRoom(name) },
                    onJoin = { screen = "join" }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(room: RoomState, onRequestPermissions: () -> Unit, onCreate: (String) -> Unit, onJoin: () -> Unit) {
    var name by remember { mutableStateOf(room.displayName) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("PlainCast", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Local rooms for voice, screen, and device audio.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onRequestPermissions(); onCreate(name) }, Modifier.fillMaxWidth()) { Text("Create Local Room") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onJoin, Modifier.fillMaxWidth()) { Text("Join Room") }
        Spacer(Modifier.height(24.dp))
        Text("Everyone must be on the same Wi‑Fi or hotspot. v1 is Android-only and does not use a cloud server.")
    }
}

@Composable
private fun JoinScreen(
    onBack: () -> Unit,
    onJoin: (QrPayload, String) -> Unit,
    onManualJoin: (String, Int, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    var scanner by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7412") }
    var roomId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Join Room", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scanner = true }) { Text("Scan QR") }
            OutlinedButton(onClick = { scanner = false }) { Text("Manual") }
        }
        Spacer(Modifier.height(12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (scanner) {
            QrScanner(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onPayload = { raw ->
                    runCatching { QrCodec.decode(raw) }
                        .onSuccess { onJoin(it, name) }
                        .onFailure { error = "Invalid PlainCast QR" }
                }
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host IP") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = roomId, onValueChange = { roomId = it.uppercase() }, label = { Text("Room ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Room token") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onManualJoin(host, port.toIntOrNull() ?: 7412, roomId, token, name) }, Modifier.fillMaxWidth()) { Text("Join") }
            }
        }
    }
}

@Composable
private fun RoomScreen(
    viewModel: PlainCastViewModel,
    room: RoomState,
    onStartScreenShare: () -> Unit,
    onStartDeviceAudio: () -> Unit,
    onStopSharing: () -> Unit,
    onLeave: () -> Unit,
) {
    val remoteTrack by viewModel.remoteVideo.track.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(if (room.isHost) "Hosting ${room.roomId}" else "Room ${room.roomId}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(room.status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        if (room.isHost) HostInviteCard(viewModel)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))) {
            if (remoteTrack != null) RemoteVideoRenderer(remoteTrack!!, Modifier.fillMaxSize())
            else Text(room.sharingLabel, Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(8.dp))
        ParticipantList(room, onRemove = { viewModel.removeParticipant(it) })
        Spacer(Modifier.height(8.dp))
        Divider()
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.setMicEnabled(!room.micEnabled) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (room.micEnabled) "Mute mic" else "Unmute mic") }
                OutlinedButton(onClick = onLeave, modifier = Modifier.weight(1f)) { Text("Leave") }
            }
            if (room.isHost) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onStartScreenShare,
                        enabled = !room.screenEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (room.screenEnabled) "Screen on" else "Share screen") }
                    Button(
                        onClick = onStartDeviceAudio,
                        enabled = !room.deviceAudioEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (room.deviceAudioEnabled) "Audio on" else "Share audio") }
                }
                if (room.isSharing) {
                    OutlinedButton(onClick = onStopSharing, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop sharing")
                    }
                }
            }
        }
    }
}

@Composable
private fun HostInviteCard(viewModel: PlainCastViewModel) {
    val payload = viewModel.qrPayload() ?: return
    val raw = remember(payload) { QrCodec.encode(payload) }
    val bitmap: Bitmap = remember(raw) { QrGenerator.generate(raw, 512) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(bitmap.asImageBitmap(), contentDescription = "Join QR", modifier = Modifier.size(144.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text("Scan to join", fontWeight = FontWeight.Bold)
                Text("${payload.host}:${payload.port}")
                Text("Room ${payload.roomId}")
                Text("Same Wi‑Fi or hotspot required.")
            }
        }
    }
}

@Composable
private fun ParticipantList(room: RoomState, onRemove: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Participants", fontWeight = FontWeight.Bold)
            room.participants.forEach { participant ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("• ${participant.displayName} ${if (participant.role.name == "HOST") "(host)" else ""}")
                    if (room.isHost && participant.peerId != room.selfPeerId) {
                        TextButton(onClick = { onRemove(participant.peerId) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteVideoRenderer(track: VideoTrack, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(com.plaincast.app.rtc.RtcEngine.eglBase.eglBaseContext, null)
                setMirror(false)
                setEnableHardwareScaler(true)
                track.addSink(this)
            }
        },
        update = { renderer -> track.addSink(renderer) },
        onRelease = { renderer ->
            track.removeSink(renderer)
            renderer.release()
        }
    )
}
