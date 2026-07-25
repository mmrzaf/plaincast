#!/usr/bin/env bash
set -euo pipefail

reject_grep() {
  local output status
  if output="$(grep "$@")"; then
    echo 'Unexpected forbidden text:' >&2
    printf '%s\n' "$output" >&2
    exit 1
  else
    status=$?
    if (( status != 1 )); then
      echo "grep failed with status $status" >&2
      exit "$status"
    fi
  fi
}

required=(
  app/src/main/java/com/plaincast/app/service/PlainCastRoomService.kt
  app/src/main/java/com/plaincast/app/room/RoomController.kt
  app/src/main/java/com/plaincast/app/audio/SharedAudioJitterBuffer.kt
  app/src/main/java/com/plaincast/app/signaling/AudioPublisherAuthority.kt
  app/src/main/java/com/plaincast/app/signaling/LocalRoomServer.kt
  app/src/main/java/com/plaincast/app/signaling/SignalingMessage.kt
  app/src/main/java/com/plaincast/app/web/BrowserHttpServer.kt
  app/src/main/java/com/plaincast/app/ui/PlainCastRoot.kt
  app/src/main/assets/browser/index.html
  app/src/main/assets/browser/styles.css
  app/src/main/assets/browser/app.js
  app/src/main/assets/browser/audio-worklet.js
  web-bridge/plaincast-https-bridge.mjs
  CHANGELOG.md
  .github/workflows/android.yml
  .github/workflows/release.yml
  artwork/plaincast-logo-source.png
  docs/assets/plaincast-logo.png
  app/src/main/assets/browser/favicon.png
  app/src/main/assets/browser/icon-192.png
  app/src/main/assets/browser/icon-512.png
  scripts/check-core-kotlin.sh
)
for file in "${required[@]}"; do test -f "$file" || { echo "Missing $file" >&2; exit 1; }; done

grep -q 'android:usesCleartextTraffic="true"' app/src/main/AndroidManifest.xml
grep -q 'const val PROTOCOL_VERSION = 10' app/src/main/java/com/plaincast/app/signaling/SignalingMessage.kt
grep -q 'const PROTOCOL_VERSION = 10' app/src/main/assets/browser/app.js
grep -q 'private const val QR_VERSION = 7' app/src/main/java/com/plaincast/app/qr/QrPayload.kt
grep -q 'private const val VERSION: Byte = 2' app/src/main/java/com/plaincast/app/audio/SharedAudioPacket.kt
grep -q 'plaincast.audio.opus.v2' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'plaincast.audio.opus.v2' app/src/main/assets/browser/app.js

# Single private-LAN product model.
reject_grep -RInE 'OperatingMode|AudioTakeoverPolicy|Ride mode|ride mode|approveAudioPublisher|rejectAudioPublisher' app/src/main app/src/test README.md docs
reject_grep -RIn 'RoomQualityProfile' app/src/main app/src/test
grep -q 'audioTargetDelayMs: Int = 60' app/src/main/java/com/plaincast/app/model/Models.kt
grep -q 'audioMaxBufferedMs: Int = 100' app/src/main/java/com/plaincast/app/model/Models.kt
grep -q 'AUDIO_PRIORITY_SCREEN_BITRATE_KBPS = 400' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'MAX_PENDING_FRAMES = 2' app/src/main/java/com/plaincast/app/audio/OpusEncoderController.kt
grep -q 'versionName = "2.0.0"' app/build.gradle.kts
grep -q 'versionCode = 20000' app/build.gradle.kts
grep -q 'distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854' gradle/wrapper/gradle-wrapper.properties
grep -q 'val useIranMirrors = !runningInGithubActions' settings.gradle.kts
grep -q 'google()' settings.gradle.kts
grep -q 'mavenCentral()' settings.gradle.kts
grep -q 'gradlePluginPortal()' settings.gradle.kts


# Release identity, branding, and signed GitHub publication must stay consistent.
grep -q 'PlainCast 2.0.0' README.md
grep -q 'PlainCast protocol 10' README.md
grep -q 'PlainCast-2.0.0-release.apk' README.md
grep -q 'actions/checkout@v6' .github/workflows/android.yml
grep -q 'persist-credentials: false' .github/workflows/android.yml
grep -q 'actions/setup-java@v5' .github/workflows/android.yml
grep -q 'gradle/actions/setup-gradle@v6' .github/workflows/android.yml
grep -q 'actions/upload-artifact@v7' .github/workflows/android.yml
grep -q 'actions/checkout@v6' .github/workflows/release.yml
grep -q 'persist-credentials: false' .github/workflows/release.yml
grep -q 'actions/setup-java@v5' .github/workflows/release.yml
grep -q 'gradle/actions/setup-gradle@v6' .github/workflows/release.yml
grep -q 'actions/upload-artifact@v7' .github/workflows/release.yml
grep -q 'gh release create' .github/workflows/release.yml
grep -q 'gh release upload' .github/workflows/release.yml
grep -q -- '--generate-notes' .github/workflows/release.yml
grep -q 'apksigner.*verify' .github/workflows/release.yml
grep -q 'zipalign.*-P 16' .github/workflows/release.yml
grep -q 'ANDROID_KEYSTORE_BASE64' .github/workflows/release.yml
grep -q 'SHA256SUMS.txt' .github/workflows/release.yml
reject_grep -RInE --exclude=check-static.sh 'protocol 9|protocol-9|versionName = "2\.1\.0"|versionCode = 10|conservative-audio-overlay|audio-stability-overlay' README.md CHANGELOG.md docs scripts .github

python3 -S - <<'PY_RELEASE'
from pathlib import Path
import json
import struct
import xml.etree.ElementTree as ET

def png_info(path):
    with open(path, 'rb') as image:
        assert image.read(8) == b'\x89PNG\r\n\x1a\n', f'{path}: not a PNG'
        length, chunk = struct.unpack('>I4s', image.read(8))
        assert length == 13 and chunk == b'IHDR', f'{path}: invalid PNG header'
        width, height, bit_depth, color_type = struct.unpack('>IIBB', image.read(10))
        return (width, height), bit_depth, color_type

ET.parse('app/src/main/AndroidManifest.xml')
for path in Path('app/src/main/res').rglob('*.xml'):
    ET.parse(path)
for path in (Path('.github/workflows/android.yml'), Path('.github/workflows/release.yml')):
    assert '\njobs:\n' in path.read_text(), f'{path}: missing jobs'
manifest = json.loads(Path('app/src/main/assets/browser/manifest.webmanifest').read_text())
assert manifest['theme_color'].lower() == '#6d49af'
assert '<meta name="theme-color" content="#6D49AF">' in Path('app/src/main/assets/browser/index.html').read_text()
assert {item['src'] for item in manifest['icons']} == {'/icon-192.png', '/icon-512.png'}
expected = {
    'app/src/main/assets/browser/icon-192.png': (192, 192),
    'app/src/main/assets/browser/icon-512.png': (512, 512),
    'app/src/main/assets/browser/favicon.png': (64, 64),
    'docs/assets/plaincast-logo.png': (512, 512),
}
for path, size in expected.items():
    actual_size, _, _ = png_info(path)
    assert actual_size == size, (path, actual_size)
for density, scale in {'mdpi':1, 'hdpi':1.5, 'xhdpi':2, 'xxhdpi':3, 'xxxhdpi':4}.items():
    legacy, _, _ = png_info(f'app/src/main/res/mipmap-{density}/ic_launcher.png')
    foreground, foreground_depth, foreground_color = png_info(f'app/src/main/res/mipmap-{density}/ic_launcher_foreground.png')
    notification, notification_depth, notification_color = png_info(f'app/src/main/res/drawable-{density}/ic_notification.png')
    assert legacy == (round(48*scale), round(48*scale))
    assert foreground == (round(108*scale), round(108*scale))
    assert notification == (round(24*scale), round(24*scale))
    assert (foreground_depth, foreground_color) == (8, 6), f'{density} foreground must be RGBA'
    assert (notification_depth, notification_color) == (8, 6), f'{density} notification must be RGBA'
PY_RELEASE

# Source packages must not contain credentials, signing keys, local paths, or build products.
if git ls-files | grep -qE '(^|/)(local\.properties|[^/]+\.(jks|keystore|p12|pfx|apk|aab))$'; then
  echo 'Found a forbidden key, local configuration, or build product.' >&2
  exit 1
fi
if git grep -IlE 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AIza[0-9A-Za-z_-]{30,}|ghp_[0-9A-Za-z]{30,}' -- . | grep -q .; then
  echo 'Found a possible credential or private key.' >&2
  exit 1
fi

# Fixed main surfaces; scrolling is allowed only inside dialogs/details.
grep -q 'height: 100dvh' app/src/main/assets/browser/styles.css
grep -q 'html, body, #app.*overflow: hidden' app/src/main/assets/browser/styles.css
if grep -n 'RoomScreen' -A120 app/src/main/java/com/plaincast/app/ui/PlainCastRoot.kt | grep -q 'verticalScroll'; then
  echo 'RoomScreen must not use verticalScroll.' >&2
  exit 1
fi
grep -q 'HOLD TO TALK' app/src/main/assets/browser/index.html
grep -q 'Audio: Android Only' app/src/main/assets/browser/index.html
grep -q 'Share Video' app/src/main/assets/browser/index.html
grep -q 'withPermissions(roomEntryPermissions(), setOf(Manifest.permission.RECORD_AUDIO))' app/src/main/java/com/plaincast/app/MainActivity.kt
grep -q 'add(Manifest.permission.RECORD_AUDIO)' app/src/main/java/com/plaincast/app/MainActivity.kt
grep -q 'heightIn(max = 620.dp).verticalScroll' app/src/main/java/com/plaincast/app/ui/PlainCastRoot.kt
grep -q 'heightIn(max = 520.dp).verticalScroll' app/src/main/java/com/plaincast/app/ui/PlainCastRoot.kt

# MediaProjection acquisition happens only after foreground promotion with the projection type.
grep -q 'ACTION_START_AUDIO' app/src/main/java/com/plaincast/app/service/PlainCastRoomService.kt
grep -q 'FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION' app/src/main/java/com/plaincast/app/service/PlainCastRoomService.kt
python3 -S - <<'PY'
from pathlib import Path
source = Path('app/src/main/java/com/plaincast/app/service/PlainCastRoomService.kt').read_text()
start = source.index('private fun startAudioCaptureFromConsent')
body = source[start:source.index('private fun projectionConsentData', start)]
assert body.index('applyForegroundNeeds(needs)') < body.index('getMediaProjection(resultCode, consentData)')
PY
grep -q 'state.screenState in setOf(MediaLifecycle.Starting, MediaLifecycle.Live) || activeAudioProjection != null' app/src/main/java/com/plaincast/app/room/RoomController.kt

# Hold to Talk prepares the WebRTC sender once, activates routing only while voice is active,
# and uses a short release grace period instead of forcing communication mode for the room.
grep -q 'Prepared muted microphone sender' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'keepCommunicationRouteActive()' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'COMMUNICATION_ROUTE_GRACE_MS = 1_500L' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'if (active && !lastForegroundNeeds.microphone)' app/src/main/java/com/plaincast/app/service/PlainCastRoomService.kt
reject_grep -n 'val roomAudioActive = state.lifecycle' app/src/main/java/com/plaincast/app/room/RoomController.kt

# Shared audio uses direct packet delivery and conservative stop-before-switch authority.
grep -q 'publisher_busy' app/src/main/java/com/plaincast/app/signaling/AudioPublisherAuthority.kt
grep -q 'data class RequestRejected' app/src/main/java/com/plaincast/app/signaling/AudioPublisherAuthority.kt
grep -q 'type = "audio_publish_rejected"' app/src/main/java/com/plaincast/app/signaling/LocalRoomServer.kt
grep -q 'val result = rtc?.broadcastSharedAudioPacket' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'markLocalAudioTransportLive' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'They must stop before another device can share' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q "case 'audio_publish_rejected'" app/src/main/assets/browser/app.js
grep -q "'Receiving Audio'" app/src/main/assets/browser/app.js
reject_grep -RInE 'audio_publish_prepare|audio_publish_ready|audio_publish_failed|audioPacketsEnabled|preparedAudioRequestId' app/src/main app/src/test

# Media lifecycle must be explicit, and shared-audio startup must survive pipeline cleanup.
grep -q 'MediaLifecycle.Stopped to setOf(MediaLifecycle.Starting, MediaLifecycle.Failed)' app/src/main/java/com/plaincast/app/room/RoomStateMachine.kt
reject_grep -n 'MediaLifecycle.Stopped to setOf(MediaLifecycle.Starting, MediaLifecycle.Live' app/src/main/java/com/plaincast/app/room/RoomStateMachine.kt
grep -q 'localAudioPipelineGeneration' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'if (room.value.audioShareState != MediaLifecycle.Starting) setAudioShareState(MediaLifecycle.Starting)' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'private val remoteVideoTracks = mutableMapOf<String, VideoTrack>()' app/src/main/java/com/plaincast/app/room/RoomController.kt
grep -q 'refreshRemoteVideo()' app/src/main/java/com/plaincast/app/room/RoomController.kt

# ICE candidates must wait for a remote description; flushing early breaks all media.
grep -q 'slot == null || slot.pc.remoteDescription == null' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'flushPendingIce(peerId)' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
reject_grep -n 'candidates.forEach { pc.addIceCandidate(it) }' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'class BoundedPendingQueue' app/src/main/java/com/plaincast/app/rtc/BoundedPendingQueue.kt
grep -q '@Volatile var ignoreOffer: Boolean = false' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'if (slot?.ignoreOffer == true)' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'pendingIce.remove(peerId)' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q '@Volatile var settingRemoteDescription: Boolean = false' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q '@Volatile var pendingRemoteOffer: SessionDescription? = null' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'slot.makingOffer || slot.settingRemoteDescription || slot.pendingRemoteOffer != null' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'Queued remote offer from .* while a remote description is in progress' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'remoteVideoReceiver: RtpReceiver? = null' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt

# Bounded low-latency paths.
grep -q 'MAX_PENDING_ICE = 64' app/src/main/assets/browser/app.js
grep -q 'MAX_PENDING_ICE_PER_PEER = 64' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'maxRetransmits = 0' app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
grep -q 'this.maxFrames = Math.round(sampleRate \* 0.12)' app/src/main/assets/browser/audio-worklet.js
grep -q 'AudioDecoder' app/src/main/assets/browser/app.js
grep -q 'decodeQueueSize > 5' app/src/main/assets/browser/app.js
grep -q 'ArrayBlockingQueue(MAX_QUEUED_CLIENTS)' app/src/main/java/com/plaincast/app/web/BrowserHttpServer.kt
grep -q 'MAX_SIGNAL_CHARS = 64 \* 1024' app/src/main/java/com/plaincast/app/signaling/LocalRoomServer.kt
grep -q 'MAX_PENDING_COMMANDS = 16' app/src/main/java/com/plaincast/app/PlainCastViewModel.kt

# Browser perfect negotiation and autoplay recovery must remain active.
grep -q 'const readyForOffer = !slot.makingOffer' app/src/main/assets/browser/app.js
grep -q 'async function resumeBlockedMedia' app/src/main/assets/browser/app.js
grep -q 'function queuePeerSignal' app/src/main/assets/browser/app.js
grep -q 'signalChain: Promise.resolve()' app/src/main/assets/browser/app.js
grep -q 'if (slot.ignoreOffer) return;' app/src/main/assets/browser/app.js
grep -q 'slot.pendingIce.length = 0;' app/src/main/assets/browser/app.js
grep -q "document.addEventListener('pointerdown', resumeBlockedMedia" app/src/main/assets/browser/app.js

# Browser uses the same WebRTC voice/video transport and receives Android device audio.
grep -q 'sendVoice: Boolean(secureCapture' app/src/main/assets/browser/app.js
grep -q 'publishScreen: Boolean(secureCapture' app/src/main/assets/browser/app.js
grep -q 'publishAudio: false' app/src/main/assets/browser/app.js
reject_grep -n 'sharedAudioTransceiver' app/src/main/assets/browser/app.js
reject_grep -n 'sharedAudioSender' app/src/main/assets/browser/app.js
grep -q "case 'offer': case 'answer'" app/src/main/assets/browser/app.js
grep -q 'getUserMedia' app/src/main/assets/browser/app.js
grep -q 'getDisplayMedia' app/src/main/assets/browser/app.js
grep -q 'Browsers receive the low-latency Opus stream' app/src/main/assets/browser/app.js
grep -q 'fun browser() = Capabilities(true, true, true, true, true, false)' app/src/main/java/com/plaincast/app/signaling/SignalingMessage.kt
grep -q 'ClientType.Browser -> receiveVoice && receiveScreen && receiveAudio && !publishAudio' app/src/main/java/com/plaincast/app/signaling/SignalingMessage.kt
grep -q 'wss://\${location.host}/signal' app/src/main/assets/browser/app.js
grep -q 'Use HTTPS publishing bridge' app/src/main/java/com/plaincast/app/ui/PlainCastRoot.kt

node --check app/src/main/assets/browser/app.js >/dev/null
node --check app/src/main/assets/browser/audio-worklet.js >/dev/null
node --check web-bridge/plaincast-https-bridge.mjs >/dev/null
node scripts/check-browser.mjs >/dev/null
node scripts/check-web-bridge.mjs >/dev/null
./scripts/check-core-kotlin.sh >/dev/null

echo 'PlainCast static, browser, bridge, and Kotlin core checks passed.'
