# Architecture

## Runtime ownership

`PlainCastRoomService` owns the long-lived Android runtime. `RoomController` serializes commands on one dedicated dispatcher and owns signaling, WebRTC peers, playback capture, Opus encode/decode, screen capture, routing, discovery, network monitoring, and diagnostics. Compose observes immutable state and sends commands through the service/ViewModel boundary.

## Foreground service and MediaProjection

Playback capture starts only through the foreground-service command:

```text
Activity receives MediaProjection consent
→ startForegroundService(ACTION_START_AUDIO)
→ service promotes itself with mediaProjection type
→ service calls MediaProjectionManager.getMediaProjection()
→ RoomController starts playback capture
```

Projection ownership stays in `RoomController`. Deliberate shutdown clears references before calling `stop()` so the MediaProjection callback cannot process the same shutdown twice.

## Signaling and ICE ordering

Protocol 10 uses JSON envelopes over a local WebSocket server on port `7412`. SDP and ICE are trickled independently, so an ICE candidate may arrive before the offer or answer that defines its media section.

Android therefore keeps a bounded FIFO of at most 64 candidates per peer whenever no peer connection or remote description exists. The queue is flushed only after `setRemoteDescription` succeeds. Rejected candidates are counted in Diagnostics instead of failing silently. Browser peers follow the same rule.

Deterministic peer-ID ordering elects the initial offerer. Polite/impolite collision handling protects later renegotiation. Failed or persistently disconnected peer connections receive a rate-limited ICE restart and fresh offer.

## Voice plane

Each Android peer connection is created with a disabled microphone sender already attached. Hold to Talk activates communication routing before enabling the existing track, avoiding sender creation and SDP renegotiation on each press.

Release disables the track immediately and restores the previous Android audio mode after a 1.5-second grace period. A new press during that window cancels route teardown. WebRTC voice output is mono for broad device compatibility; Android device-audio playback remains stereo through its separate media `AudioTrack`.

## Shared-audio plane

Android playback capture path:

```text
AudioPlaybackCapture
→ bounded PCM queue (maximum 2 × 20 ms)
→ hardware Opus encoder
→ versioned packet with publisher generation
→ negotiated unordered DataChannel, maxRetransmits=0
→ bounded reorder/jitter buffer
→ hardware Opus decoder
→ low-latency AudioTrack
```

A receiver accepts packets only from the host-authorized publisher and generation. The Android jitter buffer targets 60 ms and caps queued media at 100 ms. Old or late live audio is dropped rather than allowed to accumulate delay.

The publisher remains in `Starting` until at least one peer's WebRTC audio channel accepts an encoded packet. If capture and encoding work but transport does not become ready within four seconds, the UI points the user to Diagnostics instead of falsely reporting a live stream.

Browser receivers prefer `AudioDecoder` plus an `AudioWorklet` queue with a 120 ms hard cap and use a bounded Ogg/`decodeAudioData` fallback where necessary. Browser device-audio publishing is disabled so all shared device audio uses one interoperable transport.

## Shared-audio authority

The host owns the active publisher ID and generation. Protocol 10 rejects a new request while another publisher is active. Switching requires an explicit stop followed by a new start. Delayed packets from old generations are discarded.

## Video plane

Screen capture is a WebRTC video track. Android handles both Unified Plan `onTrack` and `onAddTrack` callbacks because WebRTC builds may deliver remote tracks through either observer path. Default limits are 720×1280, 12 FPS, and 700 kbps. Video falls to 400 kbps while voice or device audio is active to protect audio latency.

Screen authority remains latest-start-wins. Only one remote screen is rendered at a time.

## Browser delivery

The Android host serves browser assets on HTTP port `7413`. Local HTTP can receive all media. `web-bridge/plaincast-https-bridge.mjs` optionally supplies trusted HTTPS and same-origin WSS for browser microphone and video publishing.

## Bounded resources

- Pending ICE: 64 candidates per peer on Android and browser.
- Android encoder input: 2 audio frames.
- Android shared-audio jitter: 100 ms maximum.
- Browser streaming PCM: 120 ms maximum.
- Browser fallback decode queue: two batches.
- ViewModel pending commands: 16.
- Embedded HTTP client queue: bounded.
- Signaling message size: 64 KiB.
- DataChannel backpressure: derived from the configured audio window and hard-capped.
