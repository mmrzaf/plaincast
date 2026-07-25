# Diagnostics

Diagnostics are intentionally hidden from the main room and available from the overflow menu.

## Session

- room lifecycle and signaling health;
- reconnect count;
- participant count;
- active shared-audio publisher/generation;
- active screen publisher.

## Capture and encoder

- playback-capture state;
- PCM frame count and drops;
- Opus codec name;
- encoder input frames and drops;
- encoded packets/bytes;
- last encoded packet time.

The encoder queue is limited to two 20 ms input frames. Any input drop is worth investigating because it indicates the device cannot keep up in real time.

## Shared-audio receiver

- authorized publisher and generation;
- queue depth and buffered milliseconds;
- received, duplicate, out-of-order, stale, and gap-skipped packets;
- decoded frames/bytes;
- jitter estimate;
- AudioTrack underruns;
- decoder name and last error.

The Android latency invariant is `bufferedMs <= 100`. Late or missing packets should increase drop/gap counters, not accumulated delay. Browser playout has a separate 120 ms hard cap.

## WebRTC peers

- signaling, ICE, and peer-connection states;
- pending, accepted, and rejected ICE candidate counts;
- voice RTP packet/byte/loss/jitter deltas;
- negotiated shared-audio DataChannel state and buffered amount;
- rate-limited connection-recovery state.

A normal connection may briefly show pending candidates, but the count should drain after remote SDP is installed. Rejected candidates or a permanently nonzero pending count indicate an SDP/ICE ordering or compatibility defect.

## Audio routing

- available communication devices;
- selected route;
- Bluetooth permission state;
- microphone recording and playout callbacks.

## Interpreting failures

- **Signaling connected, media disconnected:** room messages work but microphone, shared audio, and screen video cannot flow; inspect SDP, ICE, and candidate counts first.
- **Pending ICE never drains:** remote SDP was not installed or candidate flushing did not complete.
- **Rejected ICE grows:** candidate application failed; inspect peer recreation, stale candidates, and protocol compatibility.
- **Encoder drops:** source/coder thread is not keeping real time.
- **Backpressure drops:** DataChannel queue exceeded its live-audio window; screen/video and network load should be checked.
- **Stale packets:** delayed packets from a publisher that has already stopped; brief counts are acceptable after a stop/start switch but should not grow continuously.
- **Skipped gaps:** packet loss/reordering exceeded the wait deadline.
- **AudioTrack underruns:** receiver device could not maintain playout; inspect route, thermal load, and target delay.
- **Weak connection:** one or more peer connections are disconnected/failed or media setup is incomplete.
