# Limitations

- Android playback-audio publishing requires Android 10 or newer and captures only applications that permit playback capture.
- Browser device-audio publishing is intentionally unavailable. Browsers receive Android device audio; trusted HTTPS/WSS enables browser microphone and screen-video publishing.
- One participant publishes device audio at a time. PlainCast does not mix multiple system-audio publishers.
- One participant publishes a screen at a time. Camera calling is not implemented.
- No TURN server is configured. Peers must be directly reachable on the same LAN or hotspot; guest/client-isolated networks will fail.
- Bluetooth devices add hardware and codec latency outside PlainCast's jitter buffer.
- Live media favors bounded delay over completeness. Late or excessive packets are intentionally discarded.
- Browser autoplay policy may require one tap before sound or video starts.
- The project contains no release signing secrets. Private APK updates must be signed with the same key as the installed build.
