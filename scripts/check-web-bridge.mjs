import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import https from 'node:https';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import tls from 'node:tls';

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'plaincast-bridge-'));
const cert = path.join(temp, 'cert.pem');
const key = path.join(temp, 'key.pem');
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-keyout', key, '-out', cert], { stdio: 'ignore' });

const upstream = net.createServer();
await new Promise((resolve, reject) => { upstream.once('error', reject); upstream.listen(0, '127.0.0.1', resolve); });
const upstreamPort = upstream.address().port;
let upstreamRequest = '';
upstream.on('connection', socket => {
  socket.on('data', chunk => {
    upstreamRequest += chunk.toString('latin1');
    if (!upstreamRequest.includes('\r\n\r\n')) return;
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: test\r\n\r\n');
  });
});

const bridgePort = await freePort();
const bridge = spawn(process.execPath, ['web-bridge/plaincast-https-bridge.mjs'], {
  cwd: path.resolve('.'),
  env: {
    ...process.env,
    TLS_CERT: cert,
    TLS_KEY: key,
    PLAINCAST_HOST: '127.0.0.1',
    PLAINCAST_SIGNAL_PORT: String(upstreamPort),
    LISTEN_HOST: '127.0.0.1',
    PORT: String(bridgePort),
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

try {
  await waitForOutput(bridge, 'PlainCast HTTPS bridge listening');
  const html = await get(`https://127.0.0.1:${bridgePort}/join/ABCD`);
  assert.match(html, /HOLD TO TALK/);
  assert.match(html, /Audio: Android Only/);

  const response = await rawUpgrade(bridgePort);
  assert.match(response, /^HTTP\/1\.1 101 /);
  assert.match(upstreamRequest, /^GET \/signal HTTP\/1\.1/m);
  assert.match(upstreamRequest, new RegExp(`host: 127\\.0\\.0\\.1:${upstreamPort}`, 'i'));
} finally {
  bridge.kill('SIGTERM');
  upstream.close();
  fs.rmSync(temp, { recursive: true, force: true });
}

console.log('PlainCast HTTPS bridge checks passed.');

function get(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { rejectUnauthorized: false }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => response.statusCode === 200 ? resolve(Buffer.concat(chunks).toString('utf8')) : reject(new Error(`HTTP ${response.statusCode}`)));
    }).on('error', reject);
  });
}
function rawUpgrade(port) {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({ host: '127.0.0.1', port, rejectUnauthorized: false }, () => {
      socket.write('GET /signal HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGVzdHRlc3R0ZXN0dGVzdA==\r\n\r\n');
    });
    let data = '';
    const timer = setTimeout(() => { socket.destroy(); reject(new Error('Upgrade timed out')); }, 5000);
    socket.on('data', chunk => {
      data += chunk.toString('latin1');
      if (data.includes('\r\n\r\n')) { clearTimeout(timer); socket.destroy(); resolve(data); }
    });
    socket.on('error', error => { clearTimeout(timer); reject(error); });
  });
}
function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(error => error ? reject(error) : resolve(port));
    });
  });
}
function waitForOutput(child, text) {
  return new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error(`Bridge did not start. Output: ${output}`)), 5000);
    const onData = chunk => {
      output += chunk.toString();
      if (output.includes(text)) { clearTimeout(timer); child.stdout.off('data', onData); resolve(); }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', chunk => { output += chunk.toString(); });
    child.once('exit', code => { clearTimeout(timer); reject(new Error(`Bridge exited with ${code}. Output: ${output}`)); });
  });
}
