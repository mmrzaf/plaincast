#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

printf 'PlainCast static repository checks\n'

required=(
  README.md
  docs/PRODUCT_SPEC.md
  docs/ARCHITECTURE.md
  docs/TEST_PLAN.md
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/plaincast/app/MainActivity.kt
  app/src/main/java/com/plaincast/app/signaling/LocalRoomServer.kt
  app/src/main/java/com/plaincast/app/rtc/PeerConnectionManager.kt
  app/src/main/java/com/plaincast/app/audio/DeviceAudioCaptureController.kt
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/test/java/com/plaincast/app/model/RandomIdTest.kt
  app/src/test/java/com/plaincast/app/model/RoomStateTest.kt
  app/src/test/java/com/plaincast/app/model/SharingStatusTest.kt
  app/src/test/java/com/plaincast/app/qr/QrCodecTest.kt
  app/src/test/java/com/plaincast/app/qr/QrPayloadTest.kt
  app/src/test/java/com/plaincast/app/signaling/SignalingJsonTest.kt
  app/src/test/java/com/plaincast/app/signaling/TrackStatePayloadTest.kt
)

for path in "${required[@]}"; do
  test -f "$path" || { echo "missing $path" >&2; exit 1; }
done

if grep -R "TODO\|FIXME\|NotImplementedError" -n app/src/main app/src/test app/build.gradle.kts; then
  echo 'blocking marker found' >&2
  exit 1
fi

echo 'ok'
