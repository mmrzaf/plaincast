import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';

class FakeClassList { add() {} remove() {} contains() { return false; } toggle() {} }

class FakeSender {
  constructor() { this.track = null; this.parameters = { encodings: [{}] }; }
  replaceTrack(track) { this.track = track; return Promise.resolve(); }
  getParameters() { return this.parameters; }
  setParameters(parameters) { this.parameters = parameters; return Promise.resolve(); }
}
class FakeDataChannel {
  constructor() { this.listeners = new Map(); this.state = 'connecting'; this.bufferedAmount = 0; }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  close() { this.state = 'closed'; }
}
class FakePeerConnection {
  constructor(config) {
    this.config = config;
    this.remoteDescription = null;
    this.localDescription = null;
    this.signalingState = 'stable';
    this.connectionState = 'new';
    this.iceConnectionState = 'new';
    this.listeners = new Map();
    this.transceivers = [];
    this.addedIce = [];
  }
  addTransceiver(kind) {
    const transceiver = { kind, sender: new FakeSender() };
    this.transceivers.push(transceiver);
    return transceiver;
  }
  createDataChannel() { return new FakeDataChannel(); }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  addIceCandidate(candidate) { this.addedIce.push(candidate); return Promise.resolve(); }
  setRemoteDescription(description) {
    this.remoteDescription = description;
    this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable';
    return Promise.resolve();
  }
  setLocalDescription(description) {
    this.localDescription = description;
    this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable';
    return Promise.resolve();
  }
  createOffer() { return Promise.resolve({ type: 'offer', sdp: 'offer-sdp' }); }
  createAnswer() { return Promise.resolve({ type: 'answer', sdp: 'answer-sdp' }); }
  restartIce() {}
  close() { this.connectionState = 'closed'; }
}
class FakeElement {
  constructor() {
    this.value = '';
    this.textContent = '';
    this.hidden = false;
    this.disabled = false;
    this.className = '';
    this.classList = new FakeClassList();
    this.srcObject = null;
    this.dataset = {};
    this.open = false;
  }
  addEventListener() {}
  append() {}
  remove() {}
  replaceChildren() {}
  requestFullscreen() { return Promise.resolve(); }
  play() { return Promise.resolve(); }
  showModal() { this.open = true; }
  close() { this.open = false; }
  setPointerCapture() {}
}
const elements = new Map();
const byId = new Map();
const document = {
  querySelector(selector) {
    if (!elements.has(selector)) elements.set(selector, new FakeElement());
    return elements.get(selector);
  },
  querySelectorAll() { return []; },
  getElementById(id) {
    if (!byId.has(id)) byId.set(id, new FakeElement());
    return byId.get(id);
  },
  createElement() { return new FakeElement(); },
  createDocumentFragment() { return new FakeElement(); },
  addEventListener() {},
  fullscreenElement: null,
  exitFullscreen() { return Promise.resolve(); },
  hidden: false,
};
const storage = new Map();
const context = vm.createContext({
  console,
  document,
  window: { addEventListener() {}, AudioContext: null, webkitAudioContext: null, isSecureContext: false },
  navigator: { userAgent: 'PlainCast browser test', mediaDevices: null },
  localStorage: {
    getItem(key) { return storage.get(key) ?? null; },
    setItem(key, value) { storage.set(key, String(value)); },
  },
  sessionStorage: {
    getItem(key) { return storage.get(`session:${key}`) ?? null; },
    setItem(key, value) { storage.set(`session:${key}`, String(value)); },
    removeItem(key) { storage.delete(`session:${key}`); },
  },
  location: {
    pathname: '/join/ABCD', hash: '#token=0123456789abcdef0123456789abcdef&signalPort=7412',
    hostname: '192.168.43.1', host: '192.168.43.1:7413', protocol: 'http:', reload() {},
  },
  history: { replaceState() {} },
  crypto: webcrypto,
  URLSearchParams, TextEncoder, ArrayBuffer, Uint8Array, DataView, BigInt, Map, Set, Math, Date, JSON, Promise,
  setTimeout, clearTimeout, queueMicrotask,
  WebSocket: class { static OPEN = 1; },
  RTCPeerConnection: FakePeerConnection, MediaStream: class { constructor(tracks = []) { this.tracks = tracks; } },
});

const source = fs.readFileSync(new URL('../app/src/main/assets/browser/app.js', import.meta.url), 'utf8');
vm.runInContext(source, context, { filename: 'app.js' });

const packet = vm.runInContext(`(() => {
  const payload = new Uint8Array([1, 2, 3, 4]);
  const raw = new ArrayBuffer(48 + payload.length);
  const view = new DataView(raw);
  view.setUint32(0, 0x50434f50, false); view.setUint8(4, 2);
  view.setBigUint64(5, 9n, false); view.setBigUint64(13, 77n, false); view.setBigUint64(21, 12n, false);
  view.setBigUint64(29, 123456n, false); view.setUint32(37, 48000, false); view.setUint8(41, 2);
  view.setUint16(42, 20, false); view.setUint32(44, payload.length, false);
  new Uint8Array(raw, 48).set(payload);
  return parseSharedAudioPacket(raw);
})()`, context);
assert.equal(packet.generation, 9n);
assert.equal(packet.streamId, 77n);
assert.equal(packet.sequence, 12n);
assert.equal(packet.channels, 2);
assert.deepEqual(Array.from(packet.payload), [1, 2, 3, 4]);

const ogg = vm.runInContext(`new Uint8Array(buildOggOpusChunk(
  [1,2,3].map((value, index) => ({ channels: 2, frameMs: 20, payload: new Uint8Array([0xf8, value, index]) })), 1234
))`, context);
let offset = 0;
let pages = 0;
while (offset < ogg.length) {
  assert.equal(String.fromCharCode(...ogg.slice(offset, offset + 4)), 'OggS');
  const segments = ogg[offset + 26];
  const lacing = ogg.slice(offset + 27, offset + 27 + segments);
  const bodyLength = lacing.reduce((sum, value) => sum + value, 0);
  const pageLength = 27 + segments + bodyLength;
  const page = ogg.slice(offset, offset + pageLength);
  const stored = new DataView(page.buffer, page.byteOffset, page.byteLength).getUint32(22, true);
  const zeroed = page.slice(); zeroed.fill(0, 22, 26);
  assert.equal(stored, vm.runInContext('oggCrc', context)(zeroed));
  offset += pageLength; pages += 1;
}
assert.equal(pages, 3);

function extractOggPackets(bytes) {
  const packets = []; let pending = []; let cursor = 0;
  while (cursor < bytes.length) {
    const segments = bytes[cursor + 26];
    const lacing = bytes.slice(cursor + 27, cursor + 27 + segments);
    let bodyOffset = cursor + 27 + segments;
    for (const size of lacing) {
      pending.push(bytes.slice(bodyOffset, bodyOffset + size)); bodyOffset += size;
      if (size < 255) { packets.push(Buffer.concat(pending)); pending = []; }
    }
    cursor = bodyOffset;
  }
  return packets;
}

let ffmpegAvailable = true;
try {
  execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
} catch {
  ffmpegAvailable = false;
}
if (ffmpegAvailable) {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'plaincast-browser-'));
  try {
    const sourceOgg = path.join(tempDir, 'source.ogg');
    const rebuiltOgg = path.join(tempDir, 'rebuilt.ogg');
    execFileSync('ffmpeg', ['-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=440:sample_rate=48000:duration=0.12','-ac','2','-c:a','libopus','-frame_duration','20','-b:a','128k',sourceOgg]);
    const frames = extractOggPackets(new Uint8Array(fs.readFileSync(sourceOgg))).slice(2, 5).map(packet => new Uint8Array(packet));
    context.realOpusFrames = frames;
    const rebuilt = vm.runInContext(`new Uint8Array(buildOggOpusChunk(realOpusFrames.map(payload => ({ channels: 2, frameMs: 20, payload })), 5678))`, context);
    fs.writeFileSync(rebuiltOgg, rebuilt);
    execFileSync('ffmpeg', ['-hide_banner','-loglevel','error','-i',rebuiltOgg,'-f','null','-']);
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
} else {
  console.warn('FFmpeg not installed; real Opus/Ogg decode integration check skipped.');
}


const iceOrdering = await vm.runInContext(`(async () => {
  const peerId = 'peer-z';
  await handleIce(peerId, { candidate: 'candidate:1 1 udp 1 192.168.1.5 5000 typ host', sdpMid: '0', sdpMLineIndex: 0 });
  const slot = state.peerConnections.get(peerId);
  const before = { pending: slot.pendingIce.length, added: slot.pc.addedIce.length, transceivers: slot.pc.transceivers.map(item => item.kind) };
  await handleDescription(peerId, { kind: 'offer', sdp: 'offer-sdp' });
  return { before, pendingAfter: slot.pendingIce.length, addedAfter: slot.pc.addedIce.length };
})()`, context);
assert.equal(iceOrdering.before.pending, 1);
assert.equal(iceOrdering.before.added, 0);
assert.equal(Array.from(iceOrdering.before.transceivers).join(','), 'audio,video');
assert.equal(iceOrdering.pendingAfter, 0);
assert.equal(iceOrdering.addedAfter, 1);

const ignoredGlareIce = await vm.runInContext(`(async () => {
  const peerId = 'peer-ignore';
  const slot = getOrCreatePeer(peerId);
  slot.polite = false;
  slot.makingOffer = true;
  await handleDescription(peerId, { kind: 'offer', sdp: 'colliding-offer' });
  await handleIce(peerId, { candidate: 'candidate:2 1 udp 1 192.168.1.6 5001 typ host', sdpMid: '0', sdpMLineIndex: 0 });
  return { ignored: slot.ignoreOffer, pending: slot.pendingIce.length, added: slot.pc.addedIce.length };
})()`, context);
assert.equal(ignoredGlareIce.ignored, true);
assert.equal(ignoredGlareIce.pending, 0);
assert.equal(ignoredGlareIce.added, 0);

vm.runInContext('renderControls()', context);
assert.equal(vm.runInContext('ui.audioShareButton.disabled', context), true);
assert.match(vm.runInContext('ui.audioShareButton.textContent', context), /Audio/);

assert.equal(vm.runInContext('PROTOCOL_VERSION', context), 10);
assert.equal(vm.runInContext('AUDIO_PACKET_VERSION', context), 2);
assert.equal(vm.runInContext('MAX_PENDING_ICE', context), 64);
assert.equal(vm.runInContext('BROWSER_CAPABILITIES.sendVoice', context), false);
assert.equal(vm.runInContext('BROWSER_CAPABILITIES.publishScreen', context), false);
assert.equal(vm.runInContext('BROWSER_CAPABILITIES.publishAudio', context), false);
assert.match(source, /getUserMedia/);
assert.match(source, /getDisplayMedia/);
assert.match(source, /AudioDecoder/);
assert.doesNotMatch(source, /sharedAudioTransceiver|sharedAudioSender/);
assert.match(source, /if \(slot\.pendingIce\.length >= MAX_PENDING_ICE\) slot\.pendingIce\.shift\(\)/);
console.log('PlainCast browser checks passed.');
