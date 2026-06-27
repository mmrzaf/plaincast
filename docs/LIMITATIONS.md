# PlainCast Limitations

## Device audio is not universal

Android device-audio capture depends on OS version, app capture policy, audio usage category, and user permission. Some apps intentionally block capture. PlainCast must say "supported device audio", not "all system audio".

## LAN only

v1 only works when devices can reach each other on the same local network. Some routers block peer-to-peer traffic between clients. In that case, use the host phone hotspot.

## Room size

Mesh media scales poorly. v1 targets host + 3 clients. More participants require lower quality, an SFU, or a different relay model.

## Web clients

Web clients are excluded from v1. Browser microphone/camera on LAN requires secure contexts and introduces HTTPS/certificate issues.

## Battery

The host device performs capture and sends media to multiple peers. Battery and heat are expected constraints.

## Recording

Recording is intentionally excluded to avoid privacy/legal complexity and keep the product local-first.
