# PlainCast HTTPS publishing bridge

The Android host serves a zero-install receiver over local HTTP. Modern browsers require a trusted HTTPS origin for microphone and display capture, and an HTTPS page cannot connect to the host's plain `ws://` signaling port directly. This dependency-free Node bridge solves both requirements:

- serves the bundled PlainCast web client over HTTPS;
- terminates `wss://.../signal` and proxies it to the Android host's local WebSocket port;
- enables browser Hold to Talk, Share Audio, and Share Video when the certificate is trusted by the browser.

## Run

Node.js 20 or newer is recommended.

```bash
TLS_CERT=/path/fullchain.pem \
TLS_KEY=/path/privkey.pem \
PLAINCAST_HOST=192.168.1.50 \
PLAINCAST_SIGNAL_PORT=7412 \
PORT=8443 \
node web-bridge/plaincast-https-bridge.mjs
```

The certificate must be valid and trusted for the hostname users open. Put the bridge and Android host on the same trusted LAN. Open the Android QR modal, select **Use HTTPS publishing bridge**, and enter the bridge base URL, for example `https://plaincast.example:8443`.

The bridge is intentionally stateless. Restart it with a different `PLAINCAST_HOST` when the Android host address changes.
