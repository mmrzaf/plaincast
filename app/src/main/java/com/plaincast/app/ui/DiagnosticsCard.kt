package com.plaincast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plaincast.app.diagnostics.DiagnosticSeverity
import com.plaincast.app.diagnostics.DiagnosticsAnalyzer
import com.plaincast.app.diagnostics.DiagnosticsState
import com.plaincast.app.diagnostics.PeerDiagnostics
import java.util.Locale

@Composable
fun DiagnosticsCard(
    diagnostics: DiagnosticsState,
    onSelectRoute: (Int) -> Unit,
    onClearRoute: () -> Unit,
    onRefreshRoutes: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val findings = DiagnosticsAnalyzer.analyze(diagnostics)
    val top = findings.first()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostics", fontWeight = FontWeight.Bold)
                    Text(top.title, color = findingColor(top.severity), style = MaterialTheme.typography.bodySmall)
                    Text(top.detail, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Details") }
            }
            if (expanded) {
                findings.drop(1).forEach { finding ->
                    Text(finding.title, color = findingColor(finding.severity), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(finding.detail, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                DiagnosticSection("Microphone") {
                    val mic = diagnostics.microphone
                    DiagnosticLine("Capture", if (mic.recording) "Running" else "Stopped")
                    DiagnosticLine("Playout", if (mic.playout) "Running" else "Stopped")
                    DiagnosticLine("Level", formatDb(mic.rmsDbfs))
                    DiagnosticLine("Voice sent", "${mic.packetsSent} packets · ${formatBytes(mic.bytesSent)}")
                    DiagnosticLine("Voice received", "${mic.packetsReceived} packets · ${formatBytes(mic.bytesReceived)}")
                }
                HorizontalDivider()
                DiagnosticSection("Audio route") {
                    DiagnosticLine("Android mode", diagnostics.audioRoute.mode)
                    DiagnosticLine("Selected", diagnostics.audioRoute.selectedCommunicationDeviceName ?: "Android default")
                    diagnostics.audioRoute.availableCommunicationDevices.forEach { route ->
                        val selected = route.id == diagnostics.audioRoute.selectedCommunicationDeviceId
                        if (selected) Button(onClick = { onSelectRoute(route.id) }, modifier = Modifier.fillMaxWidth()) { Text("${route.name} · ${route.type}") }
                        else OutlinedButton(onClick = { onSelectRoute(route.id) }, modifier = Modifier.fillMaxWidth()) { Text("${route.name} · ${route.type}") }
                    }
                    if (!diagnostics.audioRoute.bluetoothPermissionGranted) Button(onClick = onRequestBluetoothPermission, modifier = Modifier.fillMaxWidth()) { Text("Enable Bluetooth routes") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshRoutes, modifier = Modifier.weight(1f)) { Text("Refresh") }
                        OutlinedButton(onClick = onClearRoute, modifier = Modifier.weight(1f)) { Text("Use default") }
                    }
                }
                HorizontalDivider()
                DiagnosticSection("Shared audio") {
                    val capture = diagnostics.sharedAudioCapture
                    val encoder = diagnostics.sharedAudioEncoder
                    val transport = diagnostics.sharedAudioTransport
                    val playback = diagnostics.sharedAudioPlayback
                    DiagnosticLine("Capture", if (capture.active) "Running · ${formatDb(capture.rmsDbfs)}" else "Stopped")
                    DiagnosticLine("Captured", "${capture.totalFrames} PCM frames · ${formatBytes(capture.totalBytes)}")
                    DiagnosticLine("Encoder", encoder.codecName ?: "Stopped")
                    DiagnosticLine("Opus", "${encoder.encodedPackets} packets · ${formatBytes(encoder.encodedBytes)} · ${encoder.bitrateKbps} kbps")
                    DiagnosticLine("Encoder drops", encoder.inputDrops.toString())
                    DiagnosticLine("WebRTC deliveries", transport.sentDeliveries.toString())
                    DiagnosticLine("Received", transport.receivedPackets.toString())
                    DiagnosticLine("Congestion drops", transport.backpressureDrops.toString())
                    DiagnosticLine("Inactive-channel drops", transport.inactiveChannelDrops.toString())
                    DiagnosticLine("Rejected stale packets", transport.unauthorizedPackets.toString())
                    DiagnosticLine("Playback buffer", "${playback.bufferedMs} ms / target ${playback.targetDelayMs} ms")
                    DiagnosticLine("Decoder", playback.decoderName ?: "Stopped")
                    DiagnosticLine("Decoded", "${playback.decodedFrames} frames · ${formatBytes(playback.decodedBytes)}")
                    DiagnosticLine("Expired / gaps", "${playback.stalePackets} / ${playback.skippedGaps}")
                    DiagnosticLine("Audio jitter", formatMs(playback.jitterMs))
                    DiagnosticLine("Underruns", playback.underruns.toString())
                }
                diagnostics.peers.values.sortedBy { it.peerId }.forEach { peer ->
                    HorizontalDivider(); PeerSection(peer)
                }
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Reset diagnostic counters") }
            }
        }
    }
}

@Composable private fun PeerSection(peer: PeerDiagnostics) = DiagnosticSection("Peer ${peer.peerId.takeLast(8)}") {
    DiagnosticLine("Connection", "${peer.connectionState} · ICE ${peer.iceState}")
    DiagnosticLine("ICE candidates", "${peer.acceptedIceCandidates} accepted · ${peer.pendingIceCandidates} pending · ${peer.rejectedIceCandidates} rejected")
    DiagnosticLine("Audio channel", "${peer.audioChannelState} · ${formatBytes(peer.audioBufferedBytes)} queued")
    DiagnosticLine("Voice sent", "${peer.outboundVoicePackets} packets · ${formatRate(peer.outboundVoiceBitrateKbps)}")
    DiagnosticLine("Voice received", "${peer.inboundVoicePackets} packets · ${formatBytes(peer.inboundVoiceBytes)}")
    DiagnosticLine("Voice packet loss", peer.inboundVoicePacketsLost.toString())
    DiagnosticLine("Voice jitter", formatMs(peer.jitterMs))
    DiagnosticLine("Round trip", formatMs(peer.roundTripTimeMs))
    DiagnosticLine("Available upload", formatRate(peer.availableOutgoingBitrateKbps))
}

@Composable private fun DiagnosticSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontWeight = FontWeight.SemiBold); content() }
}
@Composable private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun findingColor(severity: DiagnosticSeverity) = when (severity) {
    DiagnosticSeverity.Healthy -> MaterialTheme.colorScheme.primary
    DiagnosticSeverity.Warning -> MaterialTheme.colorScheme.tertiary
    DiagnosticSeverity.Failure -> MaterialTheme.colorScheme.error
}
private fun formatDb(value: Float): String = if (value <= -119f) "silent" else String.format(Locale.US, "%.1f dBFS", value)
private fun formatMs(value: Double): String = if (value <= 0.0) "—" else String.format(Locale.US, "%.1f ms", value)
private fun formatRate(value: Double): String = if (value <= 0.0) "—" else String.format(Locale.US, "%.1f kbps", value)
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
