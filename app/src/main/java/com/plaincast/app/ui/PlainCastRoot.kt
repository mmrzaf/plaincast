package com.plaincast.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.plaincast.app.PlainCastViewModel
import com.plaincast.app.diagnostics.DiagnosticsState
import com.plaincast.app.model.ClientType
import com.plaincast.app.model.ConnectionHealth
import com.plaincast.app.model.MediaLifecycle
import com.plaincast.app.model.NearbyRoom
import com.plaincast.app.model.Participant
import com.plaincast.app.model.RoomLifecycle
import com.plaincast.app.model.RoomState
import com.plaincast.app.qr.QrCodec
import com.plaincast.app.qr.QrGenerator
import com.plaincast.app.qr.QrPayload
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun PlainCastRoot(
    viewModel: PlainCastViewModel,
    onCreateRoom: (String) -> Unit,
    onOpenQrScanner: (() -> Unit) -> Unit,
    onJoinRoom: (QrPayload, String) -> Unit,
    onManualJoin: (String, Int, String, String, String) -> Unit,
    onStartScreenShare: () -> Unit,
    onStartAudioShare: () -> Unit,
    onPushToTalk: (Boolean) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
) {
    PlainCastTheme {
        Surface(Modifier.fillMaxSize()) {
            val room by viewModel.room.collectAsState()
            val nearbyRooms by viewModel.nearbyRooms.collectAsState()
            var screen by remember { mutableStateOf("home") }
            LaunchedEffect(room.lifecycle) {
                screen = when (room.lifecycle) {
                    RoomLifecycle.Creating, RoomLifecycle.Joining, RoomLifecycle.Connected,
                    RoomLifecycle.Reconnecting, RoomLifecycle.Leaving -> "room"
                    RoomLifecycle.Idle, RoomLifecycle.Failed -> if (screen == "room") "home" else screen
                }
            }
            when (screen) {
                "join" -> JoinScreen(
                    initialName = room.displayName,
                    nearbyRooms = nearbyRooms,
                    onBack = { screen = "home" },
                    onRefreshNearby = viewModel::refreshNearbyRooms,
                    onOpenQrScanner = onOpenQrScanner,
                    onJoin = onJoinRoom,
                    onManualJoin = onManualJoin,
                )
                "room" -> RoomScreen(
                    viewModel = viewModel,
                    room = room,
                    onStartScreenShare = onStartScreenShare,
                    onStartAudioShare = onStartAudioShare,
                    onPushToTalk = onPushToTalk,
                    onRequestBluetoothPermission = onRequestBluetoothPermission,
                    onLeave = { viewModel.leaveRoom(); screen = "home" },
                )
                else -> HomeScreen(room, onCreateRoom) { screen = "join" }
            }
        }
    }
}

@Composable
private fun HomeScreen(room: RoomState, onCreate: (String) -> Unit, onJoin: () -> Unit) {
    var name by remember(room.displayName) { mutableStateOf(room.displayName) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PlainCast", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Fast local audio sharing", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onCreate(name) }, modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).height(56.dp)) {
            Text("Create room")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).height(56.dp)) {
            Text("Join room")
        }
        if (room.lifecycle == RoomLifecycle.Failed && room.status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(room.status, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun JoinScreen(
    initialName: String,
    nearbyRooms: List<NearbyRoom>,
    onBack: () -> Unit,
    onRefreshNearby: () -> Unit,
    onOpenQrScanner: (() -> Unit) -> Unit,
    onJoin: (QrPayload, String) -> Unit,
    onManualJoin: (String, Int, String, String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var section by remember { mutableStateOf("nearby") }
    var scannerOpen by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { onRefreshNearby() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Join room", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { section = "nearby" },
                enabled = section != "nearby",
                modifier = Modifier.weight(1f),
            ) { Text("Nearby") }
            OutlinedButton(
                onClick = { onOpenQrScanner { scannerOpen = true } },
                modifier = Modifier.weight(1f),
            ) { Text("Scan QR") }
            OutlinedButton(onClick = { manualOpen = true }, modifier = Modifier.weight(1f)) { Text("Manual") }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        NearbyRoomsPanel(
            nearbyRooms = nearbyRooms,
            onRefresh = onRefreshNearby,
            onJoin = { onJoin(it.qrPayload, name) },
            modifier = Modifier.weight(1f),
        )
    }

    if (scannerOpen) {
        Dialog(onDismissRequest = { scannerOpen = false }) {
            Surface(Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 620.dp), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Scan room QR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { scannerOpen = false }) { Text("Close") }
                    }
                    QrScanner(
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
                        onPayload = { raw ->
                            runCatching { QrCodec.decode(raw) }
                                .onSuccess { scannerOpen = false; onJoin(it, name) }
                                .onFailure { error = "This is not a PlainCast app QR." }
                        },
                    )
                }
            }
        }
    }

    if (manualOpen) {
        ManualJoinDialog(
            onDismiss = { manualOpen = false },
            onJoin = { host, port, roomId, token ->
                manualOpen = false
                onManualJoin(host, port, roomId, token, name)
            },
        )
    }
}

@Composable
private fun NearbyRoomsPanel(
    nearbyRooms: List<NearbyRoom>,
    onRefresh: () -> Unit,
    onJoin: (NearbyRoom) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Rooms on this network", fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (nearbyRooms.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No rooms found\nScan a QR or refresh.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nearbyRooms.take(5).forEach { nearby ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nearby.hostName, fontWeight = FontWeight.Bold)
                                Text("Room ${nearby.roomId}", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onJoin(nearby) }) { Text("Join") }
                        }
                    }
                }
                if (nearbyRooms.size > 5) Text("${nearbyRooms.size - 5} more rooms available", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ManualJoinDialog(onDismiss: () -> Unit, onJoin: (String, Int, String, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7412") }
    var roomId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join manually") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.take(255) }, label = { Text("Host address") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true)
                OutlinedTextField(roomId, { roomId = it.uppercase().filter(Char::isLetterOrDigit).take(4) }, label = { Text("Room ID") }, singleLine = true)
                OutlinedTextField(token, { token = it.lowercase().filter { c -> c.isDigit() || c in 'a'..'f' }.take(32) }, label = { Text("Join key") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = port.toIntOrNull()
                if (host.isBlank() || parsed == null || roomId.length != 4 || token.length != 32) error = "Enter complete room details."
                else onJoin(host.trim(), parsed, roomId, token)
            }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class RoomModal { Share, Participants, Audio, Diagnostics, Leave }

@Composable
private fun RoomScreen(
    viewModel: PlainCastViewModel,
    room: RoomState,
    onStartScreenShare: () -> Unit,
    onStartAudioShare: () -> Unit,
    onPushToTalk: (Boolean) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onLeave: () -> Unit,
) {
    val remoteTrack by viewModel.remoteVideo.track.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    var modal by remember { mutableStateOf<RoomModal?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { onPushToTalk(false) } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PlainCast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (room.roomId.isBlank()) room.status else "Room ${room.roomId} · ${room.connectionHealth.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (room.connectionHealth == ConnectionHealth.Poor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { modal = RoomModal.Participants }) { Text("${room.participants.size} people") }
            TextButton(onClick = { modal = RoomModal.Share }, enabled = room.isHost && room.isConnected) { Text("QR") }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Share room") }, enabled = room.isHost, onClick = { menuOpen = false; modal = RoomModal.Share })
                    DropdownMenuItem(text = { Text("Participants") }, onClick = { menuOpen = false; modal = RoomModal.Participants })
                    DropdownMenuItem(text = { Text("Audio settings") }, onClick = { menuOpen = false; modal = RoomModal.Audio })
                    DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menuOpen = false; modal = RoomModal.Diagnostics })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (room.isHost) "End room" else "Leave room") }, onClick = { menuOpen = false; modal = RoomModal.Leave })
                }
            }
        }

        MediaStage(room, remoteTrack, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))

        HoldToTalkButton(
            enabled = room.isConnected && !room.isBusy,
            talking = room.micEnabled,
            onPushToTalk = onPushToTalk,
            modifier = Modifier.fillMaxWidth().height(88.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val audioOn = room.localIsAudioPublisher || room.audioSharingEnabled
            val audioPending = room.audioShareState == MediaLifecycle.Starting
            val audioBusyByOther = room.activeAudioPublisherId != null && room.activeAudioPublisherId != room.selfPeerId
            val audioLabel = when {
                audioPending -> "Cancel Audio"
                audioOn -> "Stop Audio"
                audioBusyByOther -> "Audio in Use"
                else -> "Share Audio"
            }
            Button(
                onClick = { if (audioOn || audioPending) viewModel.stopAudioSharing() else onStartAudioShare() },
                enabled = room.isConnected && !audioBusyByOther && (!room.isBusy || audioPending),
                modifier = Modifier.weight(1f).height(58.dp),
            ) { Text(audioLabel, textAlign = TextAlign.Center) }
            val videoOn = room.localIsScreenSharer || room.screenEnabled
            Button(
                onClick = { if (videoOn) viewModel.stopScreenSharing() else onStartScreenShare() },
                enabled = room.isConnected && !room.isBusy,
                modifier = Modifier.weight(1f).height(58.dp),
            ) { Text(if (videoOn) "Stop Video" else "Share Video", textAlign = TextAlign.Center) }
        }
    }

    when (modal) {
        RoomModal.Share -> ShareRoomDialog(room) { modal = null }
        RoomModal.Participants -> ParticipantsDialog(room, viewModel::removeParticipant) { modal = null }
        RoomModal.Audio -> AudioSettingsDialog(
            diagnostics = diagnostics,
            onSelectRoute = viewModel::selectCommunicationRoute,
            onClearRoute = viewModel::clearCommunicationRoute,
            onRefreshRoutes = viewModel::refreshAudioRoutes,
            onRequestBluetoothPermission = onRequestBluetoothPermission,
            onDismiss = { modal = null },
        )
        RoomModal.Diagnostics -> DiagnosticsDialog(
            diagnostics = diagnostics,
            viewModel = viewModel,
            onRequestBluetoothPermission = onRequestBluetoothPermission,
            onDismiss = { modal = null },
        )
        RoomModal.Leave -> AlertDialog(
            onDismissRequest = { modal = null },
            title = { Text(if (room.isHost) "End room?" else "Leave room?") },
            text = { Text(if (room.isHost) "Everyone will be disconnected." else "You can join again later.") },
            confirmButton = { Button(onClick = { modal = null; onLeave() }) { Text(if (room.isHost) "End" else "Leave") } },
            dismissButton = { TextButton(onClick = { modal = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun MediaStage(room: RoomState, remoteTrack: VideoTrack?, modifier: Modifier = Modifier) {
    val screenName = room.participantName(room.activeScreenSharerId)
    val audioName = room.participantName(room.activeAudioPublisherId)
    Card(modifier) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (remoteTrack != null && room.activeScreenSharerId != room.selfPeerId) {
                RemoteVideoRenderer(remoteTrack, Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
                    Text(
                        when {
                            room.localIsScreenSharer -> "You are sharing video"
                            screenName != null -> "$screenName is sharing video"
                            audioName != null -> "$audioName is sharing audio"
                            else -> "Ready to share audio"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    if (room.anyParticipantTalking) Text("Someone is talking", color = MaterialTheme.colorScheme.primary)
                    else Text(room.status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HoldToTalkButton(
    enabled: Boolean,
    talking: Boolean,
    onPushToTalk: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed, enabled) { onPushToTalk(pressed && enabled) }
    Button(
        onClick = {},
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            when {
                talking -> "TALKING"
                pressed -> "PREPARING…"
                else -> "HOLD TO TALK"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ShareRoomDialog(room: RoomState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var browserInvite by remember { mutableStateOf(true) }
    var secureBridgeOpen by remember { mutableStateOf(false) }
    var secureBridgeBase by remember { mutableStateOf("") }
    val secureBrowserUrl = buildSecureBrowserUrl(room, secureBridgeBase)
    val browserUrl = secureBrowserUrl ?: room.browserUrl
    val value = if (browserInvite) browserUrl else runCatching {
        QrCodec.encode(
            QrPayload(
                roomId = room.roomId,
                host = room.hostAddress,
                port = room.port,
                token = room.joinToken,
            ),
        )
    }.getOrDefault("")
    val qr = remember(value) { value.takeIf(String::isNotBlank)?.let { runCatching { QrGenerator.generate(it, 600) }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share room") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (browserInvite) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Browser") }
                    else OutlinedButton(onClick = { browserInvite = true }, modifier = Modifier.weight(1f)) { Text("Browser") }
                    if (!browserInvite) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Android") }
                    else OutlinedButton(onClick = { browserInvite = false }, modifier = Modifier.weight(1f)) { Text("Android") }
                }
                if (browserInvite) {
                    TextButton(onClick = { secureBridgeOpen = !secureBridgeOpen }) {
                        Text(if (secureBridgeOpen) "Use local receiver link" else "Use HTTPS publishing bridge")
                    }
                    if (secureBridgeOpen) {
                        OutlinedTextField(
                            value = secureBridgeBase,
                            onValueChange = { secureBridgeBase = it.trim().take(255) },
                            label = { Text("HTTPS bridge address") },
                            placeholder = { Text("https://plaincast.example") },
                            supportingText = {
                                Text(if (secureBridgeBase.isBlank() || secureBrowserUrl != null) "Enables browser microphone and video publishing. Device audio is received from Android." else "Address must start with https://")
                            },
                            singleLine = true,
                            isError = secureBridgeBase.isNotBlank() && secureBrowserUrl == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                qr?.let { Image(it.asImageBitmap(), contentDescription = "Room QR code", Modifier.size(250.dp)) }
                Text(
                    when {
                        !browserInvite -> "Scan in the PlainCast Android app"
                        secureBrowserUrl != null -> "Scan to join through the secure publishing bridge"
                        else -> "Scan to open the local browser receiver"
                    },
                    textAlign = TextAlign.Center,
                )
                Text("Room ${room.roomId}", fontWeight = FontWeight.Bold)
                if (browserInvite) Text(browserUrl, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(value)) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                    Button(onClick = { shareInvitation(context, value, browserInvite) }, modifier = Modifier.weight(1f)) { Text("Share") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun buildSecureBrowserUrl(room: RoomState, base: String): String? {
    val clean = base.trim().trimEnd('/')
    if (!clean.startsWith("https://") || clean.length <= "https://".length) return null
    return "$clean/join/${room.roomId}#token=${room.joinToken}&signalPort=${room.port}"
}

@Composable
private fun ParticipantsDialog(room: RoomState, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Participants (${room.participants.size})") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                room.participants.forEach { participant ->
                    ParticipantRow(participant, participant.peerId == room.selfPeerId, room.isHost && participant.peerId != room.selfPeerId) { onRemove(participant.peerId) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ParticipantRow(participant: Participant, isSelf: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    val badges = buildList {
        if (participant.role.name == "HOST") add("host")
        if (participant.clientType == ClientType.Browser) add("web")
        if (participant.mic) add("talking")
        if (participant.audio) add("audio")
        if (participant.screen) add("video")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(participant.displayName + if (isSelf) " (you)" else "", fontWeight = FontWeight.Medium)
            if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun AudioSettingsDialog(
    diagnostics: DiagnosticsState,
    onSelectRoute: (Int) -> Unit,
    onClearRoute: () -> Unit,
    onRefreshRoutes: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio settings") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Output", fontWeight = FontWeight.Bold)
                Text(diagnostics.audioRoute.selectedCommunicationDeviceName ?: "Android default", style = MaterialTheme.typography.bodySmall)
                diagnostics.audioRoute.availableCommunicationDevices.take(6).forEach { route ->
                    val selected = route.id == diagnostics.audioRoute.selectedCommunicationDeviceId
                    if (selected) Button(onClick = { onSelectRoute(route.id) }, modifier = Modifier.fillMaxWidth()) { Text(route.name) }
                    else OutlinedButton(onClick = { onSelectRoute(route.id) }, modifier = Modifier.fillMaxWidth()) { Text(route.name) }
                }
                if (!diagnostics.audioRoute.bluetoothPermissionGranted) Button(onClick = onRequestBluetoothPermission, modifier = Modifier.fillMaxWidth()) { Text("Enable Bluetooth routes") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshRoutes, modifier = Modifier.weight(1f)) { Text("Refresh") }
                    OutlinedButton(onClick = onClearRoute, modifier = Modifier.weight(1f)) { Text("Default") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DiagnosticsDialog(
    diagnostics: DiagnosticsState,
    viewModel: PlainCastViewModel,
    onRequestBluetoothPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 700.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    DiagnosticsCard(
                        diagnostics = diagnostics,
                        onSelectRoute = viewModel::selectCommunicationRoute,
                        onClearRoute = viewModel::clearCommunicationRoute,
                        onRefreshRoutes = viewModel::refreshAudioRoutes,
                        onRequestBluetoothPermission = onRequestBluetoothPermission,
                        onReset = viewModel::resetDiagnostics,
                    )
                }
            }
        }
    }
}

private fun shareInvitation(context: Context, value: String, browserInvite: Boolean) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PlainCast room")
        putExtra(Intent.EXTRA_TEXT, if (browserInvite) "Join my PlainCast room on the same network:\n$value" else value)
    }
    context.findActivity()?.startActivity(Intent.createChooser(sendIntent, "Share PlainCast room"))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun ConnectionHealth.label(): String = when (this) {
    ConnectionHealth.Idle -> "Idle"
    ConnectionHealth.Connecting -> "Connecting"
    ConnectionHealth.Stable -> "Connected"
    ConnectionHealth.Reconnecting -> "Reconnecting"
    ConnectionHealth.Poor -> "Weak connection"
    ConnectionHealth.Disconnected -> "Disconnected"
}

@Composable
private fun RemoteVideoRenderer(track: VideoTrack, modifier: Modifier = Modifier) {
    key(track.id()) {
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
            onRelease = { renderer ->
                track.removeSink(renderer)
                renderer.release()
            },
        )
    }
}
