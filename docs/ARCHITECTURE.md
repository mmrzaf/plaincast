# PlainCast Architecture

## Overview

PlainCast v1 is LAN-first and serverless. The host Android device runs a local WebSocket server for signaling. Media connections are created with WebRTC between Android devices.

## Network

- Default signaling port: `7412`
- Join method: QR payload with host IP, port, room ID, and room token
- Supported network: same Wi-Fi or host phone hotspot
- No cloud server
- No TURN/STUN required for v1 LAN mode

## Signaling

The host runs `LocalRoomServer`, backed by `org.java-websocket`.

Clients connect with `LocalRoomClient`.

Message envelope:

```json
{
  "type": "offer",
  "roomId": "8K7P",
  "from": "peer-a",
  "to": "peer-b",
  "id": "uuid",
  "timestamp": 1710000000000,
  "payload": {}
}
```

Core message types:

- `join`
- `join_accepted`
- `join_rejected`
- `participants`
- `offer`
- `answer`
- `ice`
- `track_state`
- `participant_left`
- `removed`
- `room_ended`

## Peer topology

v1 uses deterministic full mesh for voice:

```text
Host <-> Client A
Host <-> Client B
Client A <-> Client B
```

Each peer creates a connection to every other peer. Exactly one side creates the initial offer based on deterministic peer ID ordering.

Screen sharing is host-only for v1.

## Media

### Microphone

- WebRTC audio track
- Echo cancellation/noise suppression constraints enabled
- Published by host and clients

### Screen

- WebRTC video track
- Host-only
- Uses `ScreenCapturerAndroid`
- Requires Android MediaProjection consent

### Device audio

- Host-only in v1
- Uses Android `AudioPlaybackCapture`
- Captures eligible `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` playback
- Streams PCM over LAN WebSocket binary frames
- Clients play via `AudioTrack`

The PCM design is intentional for v1. It avoids the complexity of injecting custom PCM into WebRTC audio sources while still meeting the local LAN product requirement.

## Security

- Local-only signaling
- Random room token in QR payload
- WebRTC encrypted media channels for mic/screen
- No accounts
- No persistent room history
- No recording

## Failure modes

- Router blocks local clients: advise host hotspot.
- Device audio unavailable: screen share continues.
- MediaProjection denied: share flow cancels cleanly.
- Client disconnects: participant removed.
- Host exits: room ends.
