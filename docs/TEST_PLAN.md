# Private-beta test plan

Do not send the APK to the group until every Critical item passes on the exact APK being shared.

## Critical — build gate

- `./scripts/check-static.sh`
- `./gradlew test`
- `./gradlew assembleDebug`
- install that APK on every test phone;
- verify create, join, leave, foreground notification, permission denial, lock/unlock, and process return do not crash.

## Critical — common WebRTC transport

Test Android↔Android and Android↔browser before testing individual features:

- every peer reaches `CONNECTED` for both peer connection and ICE;
- a candidate received before the offer/answer is queued, then accepted after remote SDP is installed;
- pending ICE returns to zero after connection;
- rejected ICE remains zero during a normal join;
- disconnect/reconnect does not create duplicate peer slots or duplicate remote tracks;
- a failed/disconnected link performs bounded, rate-limited ICE recovery;
- Diagnostics clearly distinguishes signaling connection from media connection.

If this section fails, microphone, shared audio, and video results are invalid because they share the same peer transport.

## Critical — MediaProjection startup

On Android 10, 12, 13, 14, and the newest available test device where possible:

- grant playback-capture consent;
- confirm no `Media projections require a foreground service of type ... MEDIA_PROJECTION` error;
- deny/cancel consent and confirm the UI returns to a recoverable failed/idle state;
- start, stop, and restart capture 20 times;
- stop capture from the Android system projection control and confirm authority is released once;
- verify the foreground notification changes to and from audio sharing correctly.

## Critical — Hold to Talk

For Android↔Android and Android↔secure-browser:

- the microphone sender exists before the first press;
- first permission grant is handled cleanly;
- after room connection, button-down does not add a track or trigger SDP renegotiation;
- release stops voice immediately;
- pointer/gesture cancellation and app backgrounding stop voice;
- 100 rapid press/release cycles finish muted;
- communication route remains warm for the short grace period and restores cleanly afterward;
- wired, speaker, earpiece, Bluetooth Classic, and Bluetooth LE routes are checked where available.

Measure button-down to audible output on the intended LAN. Record median and p95. Investigate repeated delay above the network/audio-device baseline rather than hiding it with a larger jitter buffer.

## Critical — shared audio

Android publisher to Android and browser receivers:

- share audio from an app known to permit playback capture;
- verify the publisher stays in `Starting` until a packet is accepted by at least one peer;
- verify a transport timeout produces a visible media-connection error rather than false success;
- verify a protected/unsupported source produces a clear failure rather than silence presented as success;
- stop and restart 20 times;
- run continuously for 30 minutes;
- verify delay does not progressively grow;
- verify the Android queue remains at or below 100 ms;
- verify the browser playout hard cap remains at or below 120 ms;
- verify packet loss causes skips, not delayed replay.

## Critical — conservative publisher switching

1. A starts audio and remains audible.
2. While A is active, B's Android control shows audio in use; browsers show the active receiver state.
3. A competing Android request from B is rejected and A remains active.
4. A stops; receivers clear the active publisher.
5. B starts and becomes audible without reconnecting.
6. Repeat A stop → B start → B stop → A start 20 times.
7. Race two Android requests after the slot becomes empty; exactly one publisher wins and the other receives a busy response.
8. Deny or cancel capture permission and verify the room remains usable.

## Critical — screen video

Android publisher to Android and browser receivers, plus secure-browser publisher to Android:

- the first remote frame appears after capture starts;
- portrait/landscape rotation does not duplicate or orphan tracks;
- stop/restart works 20 times without reconnecting;
- picker/system stop clears active screen authority exactly once;
- remote video removal clears the renderer and returns to the normal stage;
- video load does not make audio queues grow beyond their hard caps.

## Critical — browser

Local HTTP receiver:

- join/reconnect;
- receive Android microphone, custom Opus shared audio, and screen video;
- microphone/video publishing controls are unavailable on insecure HTTP;
- device-audio publishing remains disabled;
- AudioDecoder/AudioWorklet path works where supported;
- bounded Ogg fallback works where WebCodecs is unavailable;
- autoplay blockage shows a recoverable prompt/message;
- 120 ms hard playout cap holds under burst delivery.

Optional trusted local HTTPS/WSS bridge:

- browser microphone publishing works;
- browser screen-video publishing works;
- browser device-audio control remains receive-only;
- browser receives the Android Opus/DataChannel stream;
- picker stop releases screen capture cleanly;
- reconnect restores media without duplicate tracks.

## Critical — lifecycle and UI

- controls remain visible on the smallest supported phone;
- dialogs remain reachable;
- host and participant lock/unlock;
- app foreground/background;
- participant force-stop and rejoin;
- host ends room;
- network interruption and reconnect;
- audio share is stopped safely if Android revokes projection.

## Important — latency and soak

- 4-hour room with shared audio.
- 1-hour shared audio with intermittent Hold to Talk and screen video.
- 100 create/join/leave cycles.
- 100 audio start/stop cycles.
- 100 screen start/stop cycles.
- record microphone press-to-audible median and p95.
- record shared-audio capture-to-audible median and p95.
- monitor memory, thermal state, battery, AudioTrack underruns, encoder drops, DataChannel backpressure, pending/rejected ICE, and reconnect count.
