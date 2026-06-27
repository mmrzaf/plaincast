# PlainCast Test Plan

## Device matrix

Test at minimum:

- Android 10
- Android 12
- Android 14
- Android 15+
- Pixel device
- Samsung device
- Xiaomi/Redmi or another aggressive battery-management device

## Network matrix

- Same home Wi-Fi
- Host phone hotspot
- Office Wi-Fi
- Router with client isolation enabled
- Weak Wi-Fi signal
- Client joins after screen share already started
- Client leaves during screen share
- Host ends room during active capture

## Functional tests

### Room

- Create room
- Show QR
- Scan QR
- Manual join
- Invalid token rejected
- Room full rejected
- Host removes participant
- Host ends room

### Voice

- Host to client
- Client to host
- Client to client with 2+ clients
- Mute/unmute
- Speaker/headset output
- Background/lock behavior

### Screen

- Permission accepted
- Permission denied
- Start screen share
- Stop screen share
- Client joins during active share
- Orientation changes

### Device audio

Test capture from:

- Browser audio/video
- YouTube
- Telegram media
- Local media player
- Game audio
- App that blocks capture

Expected behavior for blocked apps: screen works, device audio may be silent, app status explains limitation.

## Performance tests

- Host + 1 client for 30 minutes
- Host + 3 clients for 15 minutes
- Watch battery and heat
- Measure audio latency by clap/tone
- Observe dropped frames during screen share

## Release gate

Do not call v1 usable until these pass on physical devices:

- QR LAN join
- bidirectional voice
- full-mesh voice among 3 devices
- host screen share
- host device audio from at least one supported app
- graceful handling when device-audio capture is blocked
