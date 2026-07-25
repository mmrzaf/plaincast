#!/usr/bin/env bash
set -euo pipefail
if ! command -v kotlinc >/dev/null 2>&1; then
  echo 'kotlinc not installed; core compile check skipped.'
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/stubs/kotlinx/serialization" "$tmp/stubs/com/plaincast/app/qr" "$tmp/stubs/android/media"
cat > "$tmp/stubs/kotlinx/serialization/Stubs.kt" <<'KOTLIN'
package kotlinx.serialization
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class Serializable
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class SerialName(val value: String)
KOTLIN
cat > "$tmp/stubs/com/plaincast/app/qr/QrPayload.kt" <<'KOTLIN'
package com.plaincast.app.qr
data class QrPayload(val roomId: String, val host: String, val port: Int, val token: String)
KOTLIN
cat > "$tmp/stubs/android/media/AudioFormat.kt" <<'KOTLIN'
package android.media
object AudioFormat {
    const val ENCODING_PCM_16BIT = 2
    const val CHANNEL_IN_MONO = 16
    const val CHANNEL_IN_STEREO = 12
    const val CHANNEL_OUT_MONO = 4
    const val CHANNEL_OUT_STEREO = 12
}
KOTLIN
cat > "$tmp/Main.kt" <<'KOTLIN'
import com.plaincast.app.audio.*
import com.plaincast.app.diagnostics.*
import com.plaincast.app.model.*
import com.plaincast.app.network.*
import com.plaincast.app.room.RoomStateMachine
import com.plaincast.app.signaling.*

fun main() {
    val quality = RoomQualityConfig()
    check(quality.audioFrameMs == 20)
    check(quality.audioTargetDelayMs == 60)
    check(quality.audioMaxBufferedMs == 100)
    val settings = SharedAudioConfig.settingsFor(quality)
    check(settings.maxBufferedPackets == 5)
    check(settings.frameBytes == 3840)

    val authority = AudioPublisherAuthority()
    val first = authority.request("A", "start").single() as AudioPublisherAuthorityEvent.PublisherChanged
    check(first.transition.currentPeerId == "A")
    check(first.transition.generation == 1L)

    val busy = authority.request("B", "start").single() as AudioPublisherAuthorityEvent.RequestRejected
    check(busy.peerId == "B")
    check(busy.activePeerId == "A")
    check(busy.reason == "publisher_busy")
    check(authority.snapshot() == AudioPublisherAuthoritySnapshot("A", 1L))

    check(authority.stop("B", force = false, reason = "stop").isEmpty())
    authority.stop("A", force = false, reason = "stop")
    val second = authority.request("B", "start").single() as AudioPublisherAuthorityEvent.PublisherChanged
    check(second.transition.currentPeerId == "B")
    check(second.transition.generation == 3L)

    RoomStateMachine.requireRoomTransition(RoomLifecycle.Idle, RoomLifecycle.Creating)
    RoomStateMachine.requireRoomTransition(RoomLifecycle.Creating, RoomLifecycle.Connected)
    check(runCatching { RoomStateMachine.requireRoomTransition(RoomLifecycle.Idle, RoomLifecycle.Connected) }.isFailure)
    RoomStateMachine.requireMediaTransition(MediaLifecycle.Stopped, MediaLifecycle.Starting)
    RoomStateMachine.requireMediaTransition(MediaLifecycle.Starting, MediaLifecycle.Live)
    check(runCatching { RoomStateMachine.requireMediaTransition(MediaLifecycle.Stopped, MediaLifecycle.Live) }.isFailure)

    val pending = com.plaincast.app.rtc.BoundedPendingQueue<Int>(2)
    check(!pending.offer(1))
    check(!pending.offer(2))
    check(pending.offer(3))
    check(pending.drain() == listOf(2, 3))

    val packet = SharedAudioPacket(1, 7, 3, 60_000, 48_000, 2, 20, byteArrayOf(1, 2, 3))
    val decoded = SharedAudioPacketCodec.decode(SharedAudioPacketCodec.encode(packet)).getOrThrow()
    check(decoded.generation == packet.generation && decoded.sequence == packet.sequence)
    check(decoded.payload.contentEquals(packet.payload))

    val jitter = SharedAudioJitterBuffer(settings)
    jitter.setExpectedGeneration(1)
    val arrival = 1_000_000L
    repeat(20) { index ->
        jitter.offer(
            SharedAudioPacket(1, 9, index.toLong(), index * 20_000L, 48_000, 2, 20, byteArrayOf(index.toByte())),
            arrival,
        )
    }
    check(jitter.depth() <= settings.maxBufferedPackets)
    check(jitter.bufferedMs() <= quality.audioMaxBufferedMs)
    check(jitter.stalePackets > 0)
    check(jitter.offer(SharedAudioPacket(2, 10, 0, 0, 48_000, 2, 20, byteArrayOf(1)), arrival) == SharedAudioJitterBuffer.OfferResult.WrongGeneration)

    val silence = AudioLevelMeter.measurePcm16(ByteArray(40))
    check(silence.sampleCount == 20 && silence.normalized == 0f)
    val loud = AudioLevelMeter.measurePcm16(byteArrayOf(0xff.toByte(), 0x7f, 0x00, 0x80.toByte()))
    check(loud.sampleCount == 2 && loud.peakDbfs > -0.1f)

    check(ReconnectBackoff.delayMs(1) == 500L)
    check(ReconnectBackoff.delayMs(5) == 8_000L)
    check(ReconnectBackoff.delayMs(99) == 8_000L)

    val selected = LanInterfaceSelector.select(
        listOf(
            LanAddressCandidate("rmnet0", "10.0.0.2", true, true, false),
            LanAddressCandidate("wlan0", "192.168.1.20", true, true, false),
            LanAddressCandidate("ap0", "192.168.43.1", true, true, false),
        )
    )
    check(selected == "192.168.43.1")

    val now = 10_000L
    val transportFailure = DiagnosticsState(
        sessionId = "room",
        sessionStartedAtMs = 1_000L,
        countersResetAtMs = 1_000L,
        sharedAudioEncoder = SharedAudioEncoderDiagnostics(encodedPackets = 10),
        peers = mapOf("peer" to PeerDiagnostics(peerId = "peer", connectionState = "FAILED", iceState = "FAILED")),
    )
    val findings = DiagnosticsAnalyzer.analyze(transportFailure, now)
    check(findings.any { it.severity == DiagnosticSeverity.Failure && it.title == "Peer connection failed" })
    check(findings.any { it.severity == DiagnosticSeverity.Failure && it.title == "Opus packets are not leaving" })

    println("PlainCast Kotlin core checks passed.")
}
KOTLIN

kotlinc \
  "$tmp/stubs/kotlinx/serialization/Stubs.kt" \
  "$tmp/stubs/com/plaincast/app/qr/QrPayload.kt" \
  "$tmp/stubs/android/media/AudioFormat.kt" \
  app/src/main/java/com/plaincast/app/model/Models.kt \
  app/src/main/java/com/plaincast/app/network/LanInterfaceSelector.kt \
  app/src/main/java/com/plaincast/app/diagnostics/DiagnosticsModels.kt \
  app/src/main/java/com/plaincast/app/diagnostics/DiagnosticsAnalyzer.kt \
  app/src/main/java/com/plaincast/app/room/RoomStateMachine.kt \
  app/src/main/java/com/plaincast/app/rtc/BoundedPendingQueue.kt \
  app/src/main/java/com/plaincast/app/signaling/AudioPublisherAuthority.kt \
  app/src/main/java/com/plaincast/app/signaling/ReconnectBackoff.kt \
  app/src/main/java/com/plaincast/app/audio/AudioLevelMeter.kt \
  app/src/main/java/com/plaincast/app/audio/SharedAudioConfig.kt \
  app/src/main/java/com/plaincast/app/audio/SharedAudioPacket.kt \
  app/src/main/java/com/plaincast/app/audio/SharedAudioJitterBuffer.kt \
  "$tmp/Main.kt" \
  -include-runtime -d "$tmp/core-check.jar"
java -jar "$tmp/core-check.jar"
