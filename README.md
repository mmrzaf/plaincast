<p align="center">
  <img src="docs/assets/plaincast-logo.png" width="180" alt="PlainCast logo">
</p>

# PlainCast 2.0.0

PlainCast is a private local-network Android app for sharing device audio, push-to-talk voice, and screen video with trusted people on the same Wi-Fi network or phone hotspot.

It has no accounts, cloud backend, analytics, advertising, or internet dependency for Android-to-Android rooms.

## Features

- Create or join a room on the same reachable LAN or hotspot.
- Join through nearby-room discovery, an Android QR invitation, or manual host details.
- Share Android playback audio from applications that permit playback capture.
- Use low-latency Hold to Talk with a prepared, normally muted WebRTC microphone sender.
- Share an Android screen as WebRTC video.
- Receive voice, device audio, and screen video in the packaged browser client.
- Keep one authoritative device-audio publisher at a time using a conservative stop-before-switch flow.
- Recover from signaling interruption and expose detailed media diagnostics.

## Requirements

- Android 10 or newer to publish device audio.
- Android 8 or newer to host, join, receive, talk, or share a screen.
- `RECORD_AUDIO` permission for Hold to Talk and Android playback capture.
- A source application that permits Android playback capture. Protected media may deliberately block capture.
- Devices on the same reachable Wi-Fi LAN or hotspot. Guest/client-isolated Wi-Fi can prevent peer connectivity.

## Build locally

Use JDK 17 and an Android SDK with compile SDK 35 installed.

```bash
./scripts/check-static.sh
./gradlew --no-daemon testDebugUnitTest lintDebug lintRelease
./gradlew --no-daemon assembleDebug assembleRelease
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The local release build is intentionally unsigned. Signed release APKs are produced by the GitHub release workflow so the private key never enters the repository.

## GitHub Actions

`Android CI` runs on pushes and pull requests. It performs the source checks, Android unit tests, debug/release lint, builds both variants, and uploads the debug APK as a workflow artifact.

`Publish Android release` runs for version tags such as `v2.0.0`. It:

1. verifies that the tag matches `versionName`;
2. runs all checks again;
3. builds debug and unsigned release APKs;
4. decodes the release keystore from GitHub encrypted secrets;
5. aligns, signs, and verifies the release APK with Android SDK tools;
6. publishes both APKs and `SHA256SUMS.txt` to the GitHub release.

Before creating a release, configure the `PLAINCAST_KEYSTORE_BASE64`,
`PLAINCAST_KEYSTORE_PASSWORD`, `PLAINCAST_KEY_ALIAS`, and
`PLAINCAST_KEY_PASSWORD` GitHub Actions secrets.

## Publish 2.0.0

After the signing secrets are configured:

```bash
git tag -a v2.0.0 -m "PlainCast 2.0.0"
git push origin v2.0.0
```

The release workflow publishes:

```text
PlainCast-2.0.0-debug.apk
PlainCast-2.0.0-release.apk
SHA256SUMS.txt
```

Use the signed release APK for normal installation and future upgrades. The debug APK is for troubleshooting and may be signed with a runner-specific debug key.

## Browser client

The Android host serves the browser receiver over local HTTP port `7413`; signaling uses WebSocket port `7412`.

- Local HTTP browser links can receive room media.
- Browser microphone, system/tab audio, and display publishing require the optional trusted local HTTPS/WSS bridge because capture APIs require a secure browser context.
- The bridge is not required for Android-to-Android use.

## Media behavior

PlainCast protocol 10 uses one active Android device-audio publisher:

1. the first accepted request becomes the publisher;
2. other devices cannot replace it while it remains active;
3. the current publisher stops;
4. another device can start a new capture session.

This avoids takeover races and stale media generations. All participants in one room must use a protocol-10 build.

## Verification

Before distributing a new signed APK, run the checklist in
[docs/TEST_PLAN.md](docs/TEST_PLAN.md). The most important test is two Android
devices plus one browser confirming:

- connected ICE and peer connection states;
- an open shared-audio data channel;
- microphone audio in both directions;
- Android playback audio through capture, encode, transport, decode, and playback;
- rendered screen video;
- repeated stop/start, lock/unlock, leave/rejoin, and reconnect behavior.

Use [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md) to interpret the resulting
connection and media metrics.

## Privacy and scope

PlainCast is designed for trusted local rooms, not hostile or internet-facing
deployment. Read [docs/LIMITATIONS.md](docs/LIMITATIONS.md) before publishing or
modifying it.

## License

MIT. See [LICENSE](LICENSE).
