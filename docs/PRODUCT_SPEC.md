# PlainCast Product Specification

## Product

PlainCast is an Android LAN room app for small nearby groups. One Android phone hosts a local room over Wi-Fi or hotspot. Other Android phones join by scanning a QR code. Participants can talk, and the host can share screen with supported device audio.

## Positioning

PlainCast should be described as:

> Local rooms for voice, screen, and device audio.

Avoid music-specific positioning. The device-audio source may be used for videos, games, apps, browser audio, demos, and music, but the product itself is generic.

## v1 scope

### Included

- Android-only app
- Local room creation
- QR join
- Manual host/IP join fallback
- Voice chat
- Host screen sharing
- Host device-audio sharing with screen share
- Participant list
- Host participant removal
- Foreground notification
- No accounts
- No cloud server

### Excluded

- iOS
- Web client
- Cloud room
- Recording
- Public discovery
- Text chat
- Reactions
- Camera
- Wi-Fi Direct
- Nearby Connections
- Large rooms

## Primary flow

1. Host taps **Create Local Room**.
2. App starts local WebSocket signaling server.
3. App displays QR invite.
4. Client scans QR.
5. Client joins room.
6. WebRTC voice connects.
7. Host taps **Share Screen**.
8. Optional **Audio** checkbox includes supported device audio.
9. Client sees screen and hears audio.

## UX principles

- One-screen start.
- No account creation.
- Explain same-Wi-Fi/hotspot requirement early.
- Be honest that some apps block device audio.
- Always show capture state.
- Keep the host in control.

## MVP acceptance criteria

- Host + one client can join over LAN.
- Host and client can talk.
- Host can share screen.
- Host can include supported device audio.
- Client can see/hear both shared sources.
- Host can end room.
- Client can leave room.
- App does not require remote infrastructure.
