#!/usr/bin/env node
import fs from 'node:fs';
import https from 'node:https';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', 'app', 'src', 'main', 'assets', 'browser');
const listenHost = process.env.LISTEN_HOST || '0.0.0.0';
const listenPort = integerEnv('PORT', 8443, 1, 65535);
const signalHost = requiredEnv('PLAINCAST_HOST');
const signalPort = integerEnv('PLAINCAST_SIGNAL_PORT', 7412, 1, 65535);
const certPath = requiredEnv('TLS_CERT');
const keyPath = requiredEnv('TLS_KEY');

const contentTypes = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.webmanifest', 'application/manifest+json; charset=utf-8'],
]);
const assetMap = new Map([
  ['/styles.css', 'styles.css'],
  ['/app.js', 'app.js'],
  ['/audio-worklet.js', 'audio-worklet.js'],
  ['/manifest.webmanifest', 'manifest.webmanifest'],
]);

const server = https.createServer({ cert: fs.readFileSync(certPath), key: fs.readFileSync(keyPath) }, (request, response) => {
  const pathname = new URL(request.url || '/', 'https://plaincast.invalid').pathname;
  if (request.method !== 'GET' && request.method !== 'HEAD') return send(response, 405, 'Method not allowed');
  const file = assetMap.get(pathname) || (pathname === '/' || pathname.startsWith('/join/') ? 'index.html' : null);
  if (!file) return send(response, 404, 'Not found');
  const fullPath = path.join(root, file);
  fs.readFile(fullPath, (error, body) => {
    if (error) return send(response, 500, 'Could not read PlainCast web assets');
    response.writeHead(200, securityHeaders(contentTypes.get(path.extname(file)) || 'application/octet-stream', body.length));
    response.end(request.method === 'HEAD' ? undefined : body);
  });
});

server.on('upgrade', (request, clientSocket, head) => {
  const pathname = new URL(request.url || '/', 'https://plaincast.invalid').pathname;
  if (pathname !== '/signal') return rejectUpgrade(clientSocket, 404, 'Not found');
  const upstream = net.connect({ host: signalHost, port: signalPort });
  let settled = false;
  upstream.setTimeout(10_000, () => upstream.destroy(new Error('Signaling connection timed out')));
  upstream.once('connect', () => {
    settled = true;
    upstream.setTimeout(0);
    const headers = { ...request.headers, host: `${signalHost}:${signalPort}` };
    const raw = [`${request.method} /signal HTTP/${request.httpVersion}`];
    for (const [name, value] of Object.entries(headers)) {
      if (value === undefined) continue;
      raw.push(`${name}: ${Array.isArray(value) ? value.join(', ') : value}`);
    }
    upstream.write(`${raw.join('\r\n')}\r\n\r\n`);
    if (head.length) upstream.write(head);
    clientSocket.pipe(upstream).pipe(clientSocket);
  });
  upstream.once('error', error => {
    console.error(`Signaling proxy error: ${error.message}`);
    if (!settled) rejectUpgrade(clientSocket, 502, 'Signaling unavailable');
    else clientSocket.destroy();
  });
  clientSocket.once('error', () => upstream.destroy());
});

server.on('clientError', (_, socket) => rejectUpgrade(socket, 400, 'Bad request'));
server.listen(listenPort, listenHost, () => {
  console.log(`PlainCast HTTPS bridge listening on https://${listenHost}:${listenPort}`);
  console.log(`Proxying /signal to ws://${signalHost}:${signalPort}`);
});

function securityHeaders(contentType, length) {
  return {
    'Content-Type': contentType,
    'Content-Length': String(length),
    'Cache-Control': contentType.startsWith('text/html') ? 'no-store' : 'public, max-age=3600',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self' wss:; worker-src 'self' blob:;",
    'Permissions-Policy': 'microphone=(self), camera=(self), display-capture=(self)',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  };
}
function send(response, status, message) {
  const body = Buffer.from(`${message}\n`);
  response.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Content-Length': String(body.length), 'Cache-Control': 'no-store' });
  response.end(body);
}
function rejectUpgrade(socket, status, message) {
  if (socket.destroyed) return;
  const body = `${message}\n`;
  socket.end(`HTTP/1.1 ${status} ${message}\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
}
function requiredEnv(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}
function integerEnv(name, fallback, min, max) {
  const raw = process.env[name];
  const value = raw === undefined ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name} must be an integer from ${min} to ${max}.`);
  return value;
}
