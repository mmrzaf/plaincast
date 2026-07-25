# Product specification

## Product

PlainCast is a private, audio-first room for trusted devices on the same local network. It has one operating model, no accounts, and no cloud dependency.

## Primary flow

1. A user creates or joins a room.
2. The room opens to a fixed media screen.
3. On Android, the user holds the main button to talk, taps **Share Audio** to publish app/system audio, or taps **Share Video** to publish a screen.
4. In a browser, the user may talk and publish screen video only through the trusted HTTPS/WSS bridge. Browser device-audio publishing is intentionally receive-only.
5. When another Android device already publishes shared audio, Android shows **Audio in Use** and prevents a competing start.
6. Secondary controls remain in dialogs.

## Main room requirements

The Android and browser room pages must not scroll. They contain:

- top bar with room state, participant count, QR/menu actions;
- flexible media stage;
- Hold to Talk;
- Android: Share Audio / Stop Audio / Audio in Use;
- Browser: Android-only shared-audio indicator;
- Share Video.

The stage shows, in priority order:

1. active remote screen;
2. local screen preview;
3. active shared-audio participant;
4. `Ready to share audio` empty state;
5. actionable capture, transport, or publisher-busy failure text.

## Audio behavior

- Exactly one Android shared-audio publisher exists at a time.
- A second publisher request is rejected while the slot is occupied.
- The current publisher must stop before another device may start.
- Publisher generation increments only when authority changes.
- Receivers discard packets from the wrong peer or generation.
- Live queues are bounded; old audio is dropped instead of increasing delay.
- Hold to Talk keeps the WebRTC sender prepared and uses a short route-release grace period rather than forcing communication mode for the whole room.
- A shared-audio publisher is reported as live only after at least one peer accepts an encoded packet.

## Media transport requirements

- Signaling success must not be presented as media success.
- Remote ICE candidates are bounded and held until the matching remote SDP is installed.
- Android handles both legacy and Unified Plan remote-track callbacks.
- Failed or stuck peer connections expose diagnostics and perform rate-limited ICE recovery.
- Voice and screen video use WebRTC media tracks.
- Android shared device audio uses the negotiated low-latency Opus DataChannel path.

## Browser behavior

- Local HTTP room: receive voice, Android shared audio, and screen video.
- Trusted local HTTPS/WSS bridge: additionally publish microphone and display video.
- Browser device-audio publishing is intentionally unavailable so the room has one interoperable shared-audio transport.
- Unsupported publishing controls are disabled with a clear explanation.
- Autoplay failures remain recoverable through a user gesture and a visible message.

## Out of scope

- public internet rooms;
- app-store distribution requirements;
- camera calls;
- cloud accounts or relays;
- media recording;
- multi-publisher audio mixing;
- capture of protected apps that opt out of Android playback capture;
- sample-accurate synchronization between unrelated devices;
- browser system/tab-audio publishing;
- background browser capture without user permission.
