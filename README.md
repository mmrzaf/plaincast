# PlainCast Android

PlainCast is an Android-first LAN room app. One Android phone hosts a local room over same Wi-Fi/hotspot; nearby Android users scan a QR code and join. v1 supports voice, host screen share, and host device-audio sharing.

## Current implementation

This directory contains a standalone Android app implementation:

- Kotlin + Jetpack Compose UI
- QR room invites and QR scanning
- Embedded LAN WebSocket signaling server using `org.java-websocket`
- WebRTC Android peer connections using `io.github.webrtc-sdk:android:144.7559.09`
- Microphone voice track over WebRTC
- Host screen share over WebRTC using `ScreenCapturerAndroid`
- Device audio capture using Android `AudioPlaybackCapture` and LAN PCM binary WebSocket frames
- Device audio playback using `AudioTrack`
- Foreground service with Android 14+ `microphone` and `mediaProjection` service types
- Android-only, no cloud server, no accounts
- App namespace/application id: `com.plaincast.app`
- Lightweight unit tests for QR codec, room state, random IDs, and signaling JSON

## Why device audio uses PCM WebSocket in v1

WebRTC microphone and screen sharing are straightforward with the native SDK. Publishing arbitrary PCM from Android `AudioPlaybackCapture` as a custom WebRTC audio source is significantly more complex and SDK-dependent. For a LAN-first MVP, raw PCM over WebSocket is simpler, debuggable, and acceptable on local Wi-Fi:

- 48 kHz, stereo, PCM 16-bit is about 1.5 Mbps before WebSocket overhead.
- This is acceptable for host + 1–3 clients on LAN.
- Later, replace this with Opus/WebRTC or an SFU if the product needs larger rooms.

## Build

Open `plaincast` in Android Studio, or from this directory run:

```bash
./gradlew :app:assembleDebug
```

This archive does not include a Gradle wrapper. If Android Studio asks to create/sync one, allow it. The project uses Maven Central and Google's Maven repository.

## Run test path

1. Install the app on two Android devices.
2. Put both devices on the same Wi-Fi or connect the client to the host phone hotspot.
3. Host taps **Create Local Room**.
4. Client taps **Join Room** and scans the QR code.
5. Confirm voice works.
6. Host taps **Share Screen**.
7. Leave **Audio** checked to request a second MediaProjection prompt for device audio.
8. Play supported media on the host device.
9. Client should see host screen and hear supported device audio.

## Known limitations

- Device audio requires Android 10 / API 29+.
- Some apps block audio capture.
- Android 14+ MediaProjection token rules are strict. This implementation uses separate prompts for screen and device audio because WebRTC `ScreenCapturerAndroid` owns its own projection internally.
- v1 is host + up to 3 clients.
- v1 does not implement Wi-Fi Direct, Nearby Connections, web clients, accounts, camera, recording, or cloud relay.
- Router client isolation can prevent LAN joining. If joining fails, use a phone hotspot.

## Next hardening pass

- Build on a real Android toolchain and fix any dependency/API drift.
- Replace second MediaProjection prompt with a shared capture pipeline if you implement custom screen capture.
- Add NSD discovery.
- Add connection quality and reconnect.
- Add participant removal UI.
- Add client-to-client audio mesh if clients must hear each other directly instead of host-centric voice.
