'use strict';

const PROTOCOL_VERSION = 10;
const AUDIO_CHANNEL_ID = 42;
const AUDIO_CHANNEL_LABEL = 'plaincast-audio';
const AUDIO_CHANNEL_PROTOCOL = 'plaincast.audio.opus.v2';
const AUDIO_MAGIC = 0x50434f50;
const AUDIO_PACKET_VERSION = 2;
const AUDIO_HEADER_BYTES = 48;
const MAX_AUDIO_PAYLOAD_BYTES = 8192;
const MAX_PENDING_ICE = 64;
const MAX_SIGNAL_CHARS = 64 * 1024;

const mediaDevices = navigator.mediaDevices || null;
const secureCapture = Boolean(window.isSecureContext && mediaDevices);
const BROWSER_CAPABILITIES = Object.freeze({
  receiveVoice: true,
  sendVoice: Boolean(secureCapture && mediaDevices.getUserMedia),
  receiveScreen: true,
  publishScreen: Boolean(secureCapture && mediaDevices.getDisplayMedia),
  receiveAudio: true,
  publishAudio: false,
});

const $ = selector => document.querySelector(selector);
const ui = Object.freeze({
  joinPanel: $('#joinPanel'), roomPanel: $('#roomPanel'), roomCode: $('#roomCode'),
  displayName: $('#displayName'), joinButton: $('#joinButton'), joinError: $('#joinError'),
  roomStatus: $('#roomStatus'), participantsButton: $('#participantsButton'), participantCount: $('#participantCount'),
  menuButton: $('#menuButton'), mediaStage: $('#mediaStage'), screenVideo: $('#screenVideo'),
  stagePlaceholder: $('#stagePlaceholder'), stageTitle: $('#stageTitle'), stageDetail: $('#stageDetail'),
  fullscreenButton: $('#fullscreenButton'), talkButton: $('#talkButton'),
  audioShareButton: $('#audioShareButton'), videoShareButton: $('#videoShareButton'),
  participantsDialog: $('#participantsDialog'), participantList: $('#participantList'),
  settingsDialog: $('#settingsDialog'), diagnosticsDialog: $('#diagnosticsDialog'), menuDialog: $('#menuDialog'),
  openParticipants: $('#openParticipants'), openSettings: $('#openSettings'), openDiagnostics: $('#openDiagnostics'),
  audioVolume: $('#audioVolume'), audioVolumeValue: $('#audioVolumeValue'), voiceVolume: $('#voiceVolume'),
  voiceVolumeValue: $('#voiceVolumeValue'), muteButton: $('#muteButton'), captureNote: $('#captureNote'),
  detailRoom: $('#detailRoom'), detailPeer: $('#detailPeer'), detailConnection: $('#detailConnection'),
  detailAudio: $('#detailAudio'), detailPeers: $('#detailPeers'), detailSecure: $('#detailSecure'),
  reconnectButton: $('#reconnectButton'), leaveButton: $('#leaveButton'), remoteAudioContainer: $('#remoteAudioContainer'),
});

const invitation = readInvitation();
const state = {
  token: invitation.token,
  roomId: invitation.roomId,
  signalPort: invitation.signalPort,
  peerId: createPeerId(),
  displayName: localStorage.getItem('plaincast.displayName') || 'Browser',
  websocket: null,
  joined: false,
  explicitlyLeft: false,
  reconnectAttempt: 0,
  reconnectTimer: null,
  participants: new Map(),
  peerConnections: new Map(),
  remoteAudio: new Map(),
  activeAudioPublisherId: null,
  audioGeneration: 0n,
  activeScreenSharerId: null,
  audioContext: null,
  sharedAudioPlayer: null,
  audioVolume: 1,
  voiceVolume: 1,
  muted: false,
  talkHeld: false,
  micStream: null,
  micTrack: null,
  displayStream: null,
  sharingVideo: false,
};

initializeUi();

function initializeUi() {
  ui.roomCode.textContent = state.roomId || '—';
  ui.detailRoom.textContent = state.roomId || '—';
  ui.detailPeer.textContent = state.peerId;
  ui.displayName.value = state.displayName;
  ui.detailSecure.textContent = secureCapture ? 'Available' : 'Receiver only on this HTTP link';
  ui.captureNote.textContent = secureCapture
    ? 'Browsers can talk and share video. Device-audio publishing stays Android-only for consistent low-latency playback.'
    : 'Microphone and screen capture require a secure HTTPS page. Receiving voice, device audio, and video still works.';
  if (!invitation.valid) {
    ui.joinButton.disabled = true;
    ui.joinError.textContent = invitation.error;
  }

  ui.joinButton.addEventListener('click', startSession);
  ui.participantsButton.addEventListener('click', () => openDialog(ui.participantsDialog));
  ui.menuButton.addEventListener('click', () => openDialog(ui.menuDialog));
  ui.openParticipants.addEventListener('click', () => swapDialog(ui.menuDialog, ui.participantsDialog));
  ui.openSettings.addEventListener('click', () => swapDialog(ui.menuDialog, ui.settingsDialog));
  ui.openDiagnostics.addEventListener('click', () => swapDialog(ui.menuDialog, ui.diagnosticsDialog));
  for (const button of document.querySelectorAll?.('[data-close]') || []) {
    button.addEventListener('click', () => document.getElementById(button.dataset.close)?.close());
  }
  ui.reconnectButton.addEventListener('click', () => reconnectNow('Reconnecting…'));
  ui.leaveButton.addEventListener('click', leaveRoom);
  ui.fullscreenButton.addEventListener('click', () => {
    if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
    else ui.mediaStage.requestFullscreen?.().catch(() => {});
  });
  ui.audioShareButton.addEventListener('click', () => setStageMessage('Receive-only device audio', 'Share device audio from the Android app. Browsers receive the low-latency Opus stream.'));
  ui.videoShareButton.addEventListener('click', () => state.sharingVideo ? stopVideoShare(true) : startVideoShare());
  installPushToTalkHandlers();

  ui.audioVolume.addEventListener('input', () => {
    state.audioVolume = Number(ui.audioVolume.value) / 100;
    ui.audioVolumeValue.value = `${ui.audioVolume.value}%`;
    applyVolumes();
  });
  ui.voiceVolume.addEventListener('input', () => {
    state.voiceVolume = Number(ui.voiceVolume.value) / 100;
    ui.voiceVolumeValue.value = `${ui.voiceVolume.value}%`;
    applyVolumes();
  });
  ui.muteButton.addEventListener('click', () => {
    state.muted = !state.muted;
    ui.muteButton.textContent = state.muted ? 'Unmute all' : 'Mute all';
    applyVolumes();
  });

  window.addEventListener('beforeunload', () => closeSession(false));
  window.addEventListener('blur', stopTalking);
  document.addEventListener('pointerdown', resumeBlockedMedia, { passive: true });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopTalking();
    else state.audioContext?.resume().catch(() => {});
  });
}

function openDialog(dialog) {
  if (!dialog?.open) dialog?.showModal?.();
}
function swapDialog(from, to) {
  from?.close?.();
  openDialog(to);
}

async function startSession() {
  ui.joinButton.disabled = true;
  ui.joinError.textContent = '';
  state.displayName = ui.displayName.value.trim().slice(0, 40) || 'Browser';
  localStorage.setItem('plaincast.displayName', state.displayName);
  try {
    await ensureAudioContext();
    state.explicitlyLeft = false;
    connectSignaling();
  } catch (error) {
    ui.joinError.textContent = readableError(error, 'Could not start audio playback.');
    ui.joinButton.disabled = false;
  }
}

async function ensureAudioContext() {
  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  if (!AudioContextClass) throw new Error('This browser does not support Web Audio.');
  if (!state.audioContext) {
    state.audioContext = new AudioContextClass({ latencyHint: 'interactive', sampleRate: 48000 });
    state.sharedAudioPlayer = new SharedAudioPlayer(state.audioContext, updateAudioDiagnostics);
    await state.sharedAudioPlayer.initialize();
  }
  await state.audioContext.resume();
}

function connectSignaling() {
  clearTimeout(state.reconnectTimer);
  const url = location.protocol === 'https:'
    ? `wss://${location.host}/signal`
    : `ws://${location.hostname}:${state.signalPort}`;
  updateConnection('Connecting');
  const socket = new WebSocket(url);
  state.websocket = socket;

  socket.addEventListener('open', () => {
    if (socket !== state.websocket) return socket.close();
    sendEnvelope('join', '*', {
      token: state.token,
      displayName: state.displayName,
      deviceName: browserName(),
      clientType: 'Browser',
      capabilities: BROWSER_CAPABILITIES,
    });
  });
  socket.addEventListener('message', event => {
    if (socket !== state.websocket || typeof event.data !== 'string' || event.data.length > MAX_SIGNAL_CHARS) return;
    handleSignal(event.data);
  });
  socket.addEventListener('close', event => {
    if (socket !== state.websocket) return;
    state.websocket = null;
    const wasJoined = state.joined;
    state.joined = false;
    stopTalking();
    clearPeerConnections();
    if (state.explicitlyLeft) return;
    updateConnection('Reconnecting');
    if (event.code === 4001 || event.code === 1008) {
      ui.joinError.textContent = event.reason || 'The host rejected this connection.';
      showJoinPanel();
      return;
    }
    if (wasJoined || state.reconnectAttempt < 8) scheduleReconnect();
  });
  socket.addEventListener('error', () => updateConnection('Room unavailable'));
}

function handleSignal(raw) {
  let envelope;
  try {
    envelope = JSON.parse(raw);
    validateEnvelope(envelope);
  } catch (error) {
    ui.roomStatus.textContent = readableError(error, 'Invalid room message');
    return;
  }
  if (envelope.roomId !== state.roomId || (envelope.to !== '*' && envelope.to !== state.peerId)) return;
  switch (envelope.type) {
    case 'join_accepted': handleJoinAccepted(envelope.payload); break;
    case 'join_rejected':
      state.explicitlyLeft = true;
      ui.joinError.textContent = joinReason(envelope.payload?.reason);
      state.websocket?.close(1000, 'join rejected');
      showJoinPanel();
      break;
    case 'participants': applyRoomSnapshot(envelope.payload); break;
    case 'participant_left': removeParticipant(envelope.payload?.peerId); break;
    case 'offer': case 'answer':
      queuePeerSignal(envelope.from, () => handleDescription(envelope.from, envelope.payload));
      break;
    case 'ice':
      queuePeerSignal(envelope.from, () => handleIce(envelope.from, envelope.payload));
      break;
    case 'renegotiate_request': negotiate(envelope.from).catch(error => setPeerError(envelope.from, error)); break;
    case 'audio_publish_rejected':
      handleAudioPublishRejected(envelope.payload);
      break;
    case 'audio_publisher_changed':
      setAudioAuthority(envelope.payload?.publisherPeerId ?? null, envelope.payload?.generation ?? 0);
      break;
    case 'screen_share_started': {
      const publisherId = envelope.payload?.peerId || envelope.from;
      if (publisherId !== state.peerId && state.sharingVideo) stopVideoShare(false);
      state.activeScreenSharerId = publisherId;
      renderRoomState();
      break;
    }
    case 'screen_share_stopped':
      if (state.activeScreenSharerId === (envelope.payload?.peerId || envelope.from)) state.activeScreenSharerId = null;
      renderRoomState();
      break;
    case 'track_state': {
      const participant = state.participants.get(envelope.from);
      if (participant) participant.mic = Boolean(envelope.payload?.mic);
      renderRoomState();
      break;
    }
    case 'room_config': break;
    case 'room_ended': case 'removed':
      state.explicitlyLeft = true;
      ui.joinError.textContent = envelope.type === 'removed' ? 'The host removed this browser.' : 'The host ended the room.';
      closeSession(false);
      showJoinPanel();
      break;
    case 'pong': break;
    default: break;
  }
}

function handleJoinAccepted(payload) {
  if (!payload || payload.peerId !== state.peerId) throw new Error('Invalid browser identity.');
  state.joined = true;
  state.reconnectAttempt = 0;
  applyRoomSnapshot(payload);
  showRoomPanel();
  updateConnection('Connected');
}

function applyRoomSnapshot(payload) {
  if (!payload || !Array.isArray(payload.participants)) return;
  const next = new Map();
  for (const participant of payload.participants) {
    if (participant && typeof participant.peerId === 'string') next.set(participant.peerId, participant);
  }
  state.participants = next;
  setAudioAuthority(payload.activeAudioPublisherId ?? null, payload.audioGeneration ?? 0);
  state.activeScreenSharerId = payload.activeScreenSharerId ?? null;

  const peers = [...next.keys()].filter(peerId => peerId !== state.peerId);
  const allowed = new Set(peers);
  for (const peerId of state.peerConnections.keys()) if (!allowed.has(peerId)) closePeer(peerId);
  for (const peerId of peers) getOrCreatePeer(peerId);
  renderRoomState();
  queueMicrotask(() => {
    for (const peerId of peers) {
      if (state.peerId > peerId) negotiate(peerId).catch(error => setPeerError(peerId, error));
    }
  });
}

function handleAudioPublishRejected(payload) {
  const activeName = payload?.displayName || participantName(payload?.activePublisherPeerId) || 'Another participant';
  setStageMessage('Audio is already in use', `${activeName} must stop sharing before another Android device can start.`);
  renderRoomState();
}

function setAudioAuthority(peerId, generation) {
  const nextGeneration = toBigInt(generation);
  const changed = state.activeAudioPublisherId !== peerId || state.audioGeneration !== nextGeneration;
  state.activeAudioPublisherId = peerId;
  state.audioGeneration = nextGeneration;
  if (changed) state.sharedAudioPlayer?.setAuthority(peerId, nextGeneration);
  renderRoomState();
}

function queuePeerSignal(peerId, action) {
  const slot = getOrCreatePeer(peerId);
  const run = () => Promise.resolve().then(action);
  slot.signalChain = slot.signalChain.then(run, run).catch(error => {
    setPeerError(peerId, error);
  });
  return slot.signalChain;
}

async function handleDescription(peerId, payload) {
  if (!payload || !['offer', 'answer'].includes(payload.kind) || typeof payload.sdp !== 'string') throw new Error('Invalid WebRTC description.');
  const slot = getOrCreatePeer(peerId);
  const description = { type: payload.kind, sdp: payload.sdp };
  const readyForOffer = !slot.makingOffer &&
    (slot.pc.signalingState === 'stable' || slot.isSettingRemoteAnswerPending);
  const offerCollision = description.type === 'offer' && !readyForOffer;
  slot.ignoreOffer = !slot.polite && offerCollision;
  if (slot.ignoreOffer) {
    // ICE generated for an ignored glare offer belongs to a different SDP ufrag.
    // Do not let it contaminate the local offer that remains active.
    slot.pendingIce.length = 0;
    return;
  }
  if (offerCollision && slot.polite) await slot.pc.setLocalDescription({ type: 'rollback' });
  slot.isSettingRemoteAnswerPending = description.type === 'answer';
  try {
    await slot.pc.setRemoteDescription(description);
  } finally {
    slot.isSettingRemoteAnswerPending = false;
  }
  await flushPendingIce(slot);
  if (description.type === 'offer') {
    await slot.pc.setLocalDescription(await slot.pc.createAnswer());
    sendEnvelope('answer', peerId, { sdp: slot.pc.localDescription.sdp, kind: 'answer' });
  }
}

async function negotiate(peerId) {
  const slot = getOrCreatePeer(peerId);
  if (!state.joined || slot.makingOffer || slot.pc.signalingState !== 'stable') return;
  try {
    slot.makingOffer = true;
    await attachLocalTracks(slot);
    await slot.pc.setLocalDescription(await slot.pc.createOffer());
    sendEnvelope('offer', peerId, { sdp: slot.pc.localDescription.sdp, kind: 'offer' });
  } finally {
    slot.makingOffer = false;
  }
}

async function handleIce(peerId, payload) {
  if (!payload || typeof payload.candidate !== 'string' || !Number.isInteger(payload.sdpMLineIndex)) throw new Error('Invalid ICE candidate.');
  const slot = getOrCreatePeer(peerId);
  if (slot.ignoreOffer) return;
  const candidate = { candidate: payload.candidate, sdpMid: payload.sdpMid ?? null, sdpMLineIndex: payload.sdpMLineIndex };
  if (slot.pc.remoteDescription) {
    try { await slot.pc.addIceCandidate(candidate); } catch (error) { if (!slot.ignoreOffer) throw error; }
  } else {
    if (slot.pendingIce.length >= MAX_PENDING_ICE) slot.pendingIce.shift();
    slot.pendingIce.push(candidate);
  }
}

async function flushPendingIce(slot) {
  if (!slot.pc.remoteDescription) return;
  const candidates = slot.pendingIce.splice(0);
  for (const candidate of candidates) {
    try { await slot.pc.addIceCandidate(candidate); } catch (_) { /* stale candidate */ }
  }
}

function getOrCreatePeer(peerId) {
  const existing = state.peerConnections.get(peerId);
  if (existing) return existing;
  const pc = new RTCPeerConnection({ iceServers: [], bundlePolicy: 'max-bundle', rtcpMuxPolicy: 'require' });
  const micTransceiver = pc.addTransceiver('audio', { direction: 'sendrecv' });
  const videoTransceiver = pc.addTransceiver('video', { direction: 'sendrecv' });
  const slot = {
    pc,
    pendingIce: [],
    polite: state.peerId < peerId,
    makingOffer: false,
    ignoreOffer: false,
    isSettingRemoteAnswerPending: false,
    signalChain: Promise.resolve(),
    micTransceiver,
    videoTransceiver,
    micSender: micTransceiver.sender,
    videoSender: videoTransceiver.sender,
    remoteVideoTrack: null,
    channel: null,
  };
  state.peerConnections.set(peerId, slot);

  const channel = pc.createDataChannel(AUDIO_CHANNEL_LABEL, {
    negotiated: true,
    id: AUDIO_CHANNEL_ID,
    ordered: false,
    maxRetransmits: 0,
    protocol: AUDIO_CHANNEL_PROTOCOL,
  });
  channel.binaryType = 'arraybuffer';
  channel.addEventListener('message', event => {
    if (event.data instanceof ArrayBuffer) {
      state.sharedAudioPlayer?.accept(peerId, event.data, state.activeAudioPublisherId, state.audioGeneration);
    }
  });
  channel.addEventListener('error', () => updateAudioDiagnostics('Audio channel error'));
  slot.channel = channel;

  pc.addEventListener('icecandidate', event => {
    if (!event.candidate) return;
    sendEnvelope('ice', peerId, {
      candidate: event.candidate.candidate,
      sdpMid: event.candidate.sdpMid,
      sdpMLineIndex: event.candidate.sdpMLineIndex,
    });
  });
  pc.addEventListener('track', event => attachRemoteTrack(peerId, slot, event.track, event.transceiver));
  pc.addEventListener('negotiationneeded', () => negotiate(peerId).catch(error => setPeerError(peerId, error)));
  pc.addEventListener('connectionstatechange', () => {
    if (pc.connectionState === 'failed') {
      try { pc.restartIce(); } catch (_) {}
      negotiate(peerId).catch(error => setPeerError(peerId, error));
    }
    updateConnectionHealth();
  });
  pc.addEventListener('iceconnectionstatechange', updateConnectionHealth);
  attachLocalTracks(slot).catch(error => setPeerError(peerId, error));
  return slot;
}

async function attachLocalTracks(slot) {
  await slot.micSender.replaceTrack(state.micTrack || null);
  await slot.videoSender.replaceTrack(state.sharingVideo ? state.displayStream?.getVideoTracks()[0] || null : null);
  await applyVideoPriority(slot);
}

async function applyVideoPriority(slot) {
  const parameters = slot.videoSender.getParameters();
  if (!parameters.encodings?.length) return;
  const audioBusy = Boolean(state.activeAudioPublisherId || anyParticipantTalking());
  parameters.encodings[0].maxBitrate = (audioBusy ? 400 : 700) * 1000;
  await slot.videoSender.setParameters(parameters).catch(() => {});
}

function updateVideoPriority() {
  for (const slot of state.peerConnections.values()) applyVideoPriority(slot).catch(() => {});
}

async function attachAllLocalTracks() {
  await Promise.all([...state.peerConnections.values()].map(attachLocalTracks));
}

function attachRemoteTrack(peerId, slot, track, transceiver) {
  track.addEventListener('ended', () => detachRemoteTrack(peerId, track));
  if (track.kind === 'audio') {
    const role = 'voice';
    const key = `${peerId}:${track.id}`;
    if (state.remoteAudio.has(key)) return;
    const audio = document.createElement('audio');
    audio.autoplay = true;
    audio.playsInline = true;
    audio.dataset.role = role;
    audio.srcObject = new MediaStream([track]);
    ui.remoteAudioContainer.append(audio);
    state.remoteAudio.set(key, audio);
    applyVolumes();
    audio.play().catch(() => setStageMessage('Tap to enable voice', 'Your browser blocked automatic audio playback. Tap the page, then try again.'));
  } else if (track.kind === 'video') {
    slot.remoteVideoTrack = track;
    if (!state.activeScreenSharerId || state.activeScreenSharerId === peerId) setScreenTrack(peerId, track);
  }
}

function detachRemoteTrack(peerId, track) {
  const key = `${peerId}:${track.id}`;
  const audio = state.remoteAudio.get(key);
  if (audio) {
    audio.srcObject = null;
    audio.remove();
    state.remoteAudio.delete(key);
  }
  const slot = state.peerConnections.get(peerId);
  if (slot?.remoteVideoTrack === track) {
    slot.remoteVideoTrack = null;
    if (state.activeScreenSharerId === peerId) setScreenTrack(null, null);
  }
}

function setScreenTrack(peerId, track) {
  if (track) {
    ui.screenVideo.srcObject = new MediaStream([track]);
    ui.mediaStage.classList.remove('empty');
    ui.stagePlaceholder.classList.add('hidden');
    ui.fullscreenButton.classList.remove('hidden');
    ui.screenVideo.play().catch(() => setStageMessage('Tap to start video', 'Your browser blocked automatic video playback.'));
    return;
  }
  if (state.sharingVideo && state.displayStream?.getVideoTracks()[0]) {
    ui.screenVideo.srcObject = new MediaStream([state.displayStream.getVideoTracks()[0]]);
    ui.mediaStage.classList.remove('empty');
    ui.stagePlaceholder.classList.add('hidden');
    ui.fullscreenButton.classList.remove('hidden');
  } else {
    ui.screenVideo.srcObject = null;
    ui.mediaStage.classList.add('empty');
    ui.stagePlaceholder.classList.remove('hidden');
    ui.fullscreenButton.classList.add('hidden');
  }
}

async function resumeBlockedMedia() {
  await state.audioContext?.resume().catch(() => {});
  for (const audio of state.remoteAudio.values()) {
    if (audio.paused) await audio.play().catch(() => {});
  }
  if (ui.screenVideo.srcObject && ui.screenVideo.paused) await ui.screenVideo.play().catch(() => {});
}

function installPushToTalkHandlers() {
  const begin = event => {
    event.preventDefault();
    state.talkHeld = true;
    ui.talkButton.setPointerCapture?.(event.pointerId);
    startTalking().catch(error => {
      state.talkHeld = false;
      setStageMessage('Microphone unavailable', readableError(error, 'Microphone permission was not granted.'));
      renderControls();
    });
  };
  ui.talkButton.addEventListener('pointerdown', begin);
  ui.talkButton.addEventListener('pointerup', stopTalking);
  ui.talkButton.addEventListener('pointercancel', stopTalking);
  ui.talkButton.addEventListener('lostpointercapture', stopTalking);
  ui.talkButton.addEventListener('contextmenu', event => event.preventDefault());
}

async function ensureMicTrack() {
  if (state.micTrack?.readyState === 'live') return state.micTrack;
  if (!BROWSER_CAPABILITIES.sendVoice) throw new Error('Open this room over HTTPS to use the microphone.');
  state.micStream = await mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
    video: false,
  });
  state.micTrack = state.micStream.getAudioTracks()[0] || null;
  if (!state.micTrack) throw new Error('No microphone track was provided.');
  state.micTrack.enabled = false;
  state.micTrack.addEventListener('ended', () => {
    state.micTrack = null;
    stopTalking();
    attachAllLocalTracks().catch(() => {});
  });
  await attachAllLocalTracks();
  return state.micTrack;
}

async function startTalking() {
  if (!state.joined || !state.talkHeld) return;
  const track = await ensureMicTrack();
  if (!state.talkHeld) return;
  track.enabled = true;
  sendEnvelope('track_state', '*', { mic: true });
  renderControls();
}

function stopTalking() {
  state.talkHeld = false;
  if (state.micTrack) state.micTrack.enabled = false;
  if (state.joined) sendEnvelope('track_state', '*', { mic: false });
  renderControls();
}

async function ensureDisplayCapture() {
  const existingVideo = state.displayStream?.getVideoTracks()[0];
  if (existingVideo?.readyState === 'live') return state.displayStream;
  if (!BROWSER_CAPABILITIES.publishScreen) throw new Error('Open this room over HTTPS to share video.');
  state.displayStream = await mediaDevices.getDisplayMedia({
    video: { frameRate: { ideal: 12, max: 15 }, width: { ideal: 1280, max: 1920 }, height: { ideal: 720, max: 1080 } },
    audio: false,
    preferCurrentTab: true,
    systemAudio: 'include',
    selfBrowserSurface: 'exclude',
  });
  let ended = false;
  const end = () => {
    if (ended) return;
    ended = true;
    state.sharingVideo = false;
    if (state.joined) {
      sendEnvelope('screen_share_stopped', '*', { peerId: state.peerId, displayName: state.displayName, active: false });
    }
    attachAllLocalTracks().catch(() => {});
    state.displayStream = null;
    setScreenTrack(null, null);
    renderRoomState();
  };
  for (const track of state.displayStream.getTracks()) track.addEventListener('ended', end, { once: true });
  return state.displayStream;
}


async function startVideoShare() {
  try {
    const stream = await ensureDisplayCapture();
    if (!stream.getVideoTracks()[0]) throw new Error('No screen video was selected.');
    state.sharingVideo = true;
    await attachAllLocalTracks();
    sendEnvelope('screen_share_started', '*', { peerId: state.peerId, displayName: state.displayName, active: true });
    setScreenTrack(state.peerId, stream.getVideoTracks()[0]);
    renderRoomState();
  } catch (error) {
    stopUnusedDisplayCapture();
    setStageMessage('Could not share video', readableError(error, 'Screen sharing was cancelled.'));
    renderControls();
  }
}

async function stopVideoShare(announce) {
  if (!state.sharingVideo) return;
  state.sharingVideo = false;
  await attachAllLocalTracks().catch(() => {});
  if (announce && state.joined) {
    sendEnvelope('screen_share_stopped', '*', { peerId: state.peerId, displayName: state.displayName, active: false });
  }
  stopUnusedDisplayCapture();
  setScreenTrack(null, null);
  renderRoomState();
}

function stopUnusedDisplayCapture() {
  if (state.sharingVideo || !state.displayStream) return;
  for (const track of state.displayStream.getTracks()) track.stop();
  state.displayStream = null;
}

function removeParticipant(peerId) {
  if (!peerId) return;
  state.participants.delete(peerId);
  closePeer(peerId);
  if (state.activeAudioPublisherId === peerId) setAudioAuthority(null, state.audioGeneration);
  if (state.activeScreenSharerId === peerId) state.activeScreenSharerId = null;
  renderRoomState();
}

function closePeer(peerId) {
  const slot = state.peerConnections.get(peerId);
  if (slot) {
    try { slot.channel?.close(); } catch (_) {}
    try { slot.pc.close(); } catch (_) {}
    state.peerConnections.delete(peerId);
  }
  for (const [key, audio] of state.remoteAudio) {
    if (key.startsWith(`${peerId}:`)) {
      audio.srcObject = null;
      audio.remove();
      state.remoteAudio.delete(key);
    }
  }
  if (state.activeScreenSharerId === peerId) setScreenTrack(null, null);
  updateConnectionHealth();
}

function clearPeerConnections() {
  for (const peerId of [...state.peerConnections.keys()]) closePeer(peerId);
  state.sharedAudioPlayer?.reset('Disconnected');
  setScreenTrack(null, null);
}

function sendEnvelope(type, to, payload) {
  const socket = state.websocket;
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify({
    protocolVersion: PROTOCOL_VERSION,
    type,
    roomId: state.roomId,
    from: state.peerId,
    to,
    id: crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`,
    timestamp: Date.now(),
    payload,
  }));
  return true;
}

function scheduleReconnect() {
  clearTimeout(state.reconnectTimer);
  state.reconnectAttempt += 1;
  const delay = Math.min(12000, 500 * (2 ** Math.min(state.reconnectAttempt - 1, 4))) + Math.floor(Math.random() * 250);
  state.reconnectTimer = setTimeout(connectSignaling, delay);
}

function reconnectNow(message) {
  if (state.explicitlyLeft) return;
  state.reconnectAttempt = 0;
  updateConnection(message);
  state.websocket?.close(1012, 'reconnect');
  if (!state.websocket) connectSignaling();
}

function leaveRoom() {
  state.explicitlyLeft = true;
  sendEnvelope('leave', '*', {});
  closeSession(true);
  location.reload();
}

function closeSession(closeAudio = true) {
  clearTimeout(state.reconnectTimer);
  stopTalking();
  if (state.sharingVideo) sendEnvelope('screen_share_stopped', '*', { peerId: state.peerId, displayName: state.displayName, active: false });
  clearPeerConnections();
  const socket = state.websocket;
  state.websocket = null;
  try { socket?.close(1000, 'left'); } catch (_) {}
  state.joined = false;
  for (const track of state.micStream?.getTracks() || []) track.stop();
  for (const track of state.displayStream?.getTracks() || []) track.stop();
  state.micStream = null;
  state.micTrack = null;
  state.displayStream = null;
  state.sharingVideo = false;
  if (closeAudio) {
    state.sharedAudioPlayer?.close();
    state.sharedAudioPlayer = null;
    state.audioContext?.close().catch(() => {});
    state.audioContext = null;
  }
}

function renderRoomState() {
  renderParticipants();
  renderControls();
  updateVideoPriority();
  const localScreen = state.sharingVideo && state.displayStream?.getVideoTracks()[0];
  const remoteSlot = state.activeScreenSharerId ? state.peerConnections.get(state.activeScreenSharerId) : null;
  const remoteScreen = remoteSlot?.remoteVideoTrack || null;
  if (localScreen) setScreenTrack(state.peerId, localScreen);
  else if (remoteScreen) setScreenTrack(state.activeScreenSharerId, remoteScreen);
  else setScreenTrack(null, null);

  if (!localScreen && !remoteScreen) {
    const audioName = participantName(state.activeAudioPublisherId);
    if (audioName) setStageMessage(`${audioName} is sharing audio`, anyParticipantTalking() ? 'Someone is talking' : 'Live audio');
    else setStageMessage('Ready to share audio', anyParticipantTalking() ? 'Someone is talking' : 'Connected devices appear here.');
  }
}

function renderControls() {
  const connected = state.joined;
  ui.talkButton.disabled = !connected || !BROWSER_CAPABILITIES.sendVoice;
  ui.audioShareButton.disabled = true;
  ui.videoShareButton.disabled = !connected || !BROWSER_CAPABILITIES.publishScreen;
  ui.talkButton.classList.toggle('active', Boolean(state.micTrack?.enabled));
  ui.talkButton.textContent = state.micTrack?.enabled ? 'TALKING' : 'HOLD TO TALK';
  ui.audioShareButton.classList.remove('active');
  ui.audioShareButton.textContent = state.activeAudioPublisherId ? 'Receiving Audio' : 'Audio: Android Only';
  ui.videoShareButton.classList.toggle('active', state.sharingVideo);
  ui.videoShareButton.textContent = state.sharingVideo ? 'Stop Video' : 'Share Video';
  if (!secureCapture) {
    const title = 'Publishing requires HTTPS. Receiving works on this local HTTP page.';
    ui.talkButton.title = title;
    ui.audioShareButton.title = 'Device-audio publishing is available from Android; browser receiving works here.';
    ui.videoShareButton.title = title;
  }
}

function renderParticipants() {
  ui.participantCount.textContent = String(state.participants.size);
  const fragment = document.createDocumentFragment?.();
  const target = fragment || ui.participantList;
  if (!fragment) ui.participantList.replaceChildren();
  for (const participant of [...state.participants.values()].sort((a, b) => a.displayName.localeCompare(b.displayName))) {
    const item = document.createElement('li');
    const text = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = `${participant.displayName}${participant.peerId === state.peerId ? ' (you)' : ''}`;
    const details = document.createElement('small');
    const badges = [];
    if (participant.role === 'HOST') badges.push('host');
    if (participant.clientType === 'Browser') badges.push('web');
    if (participant.mic) badges.push('talking');
    if (participant.audio) badges.push('audio');
    if (participant.screen) badges.push('video');
    details.textContent = badges.join(' · ') || 'connected';
    text.append(name, details);
    item.append(text);
    target.append(item);
  }
  if (fragment) ui.participantList.replaceChildren(fragment);
}

function setStageMessage(title, detail) {
  ui.stageTitle.textContent = title;
  ui.stageDetail.textContent = detail;
}

function participantName(peerId) {
  if (!peerId) return null;
  return state.participants.get(peerId)?.displayName || (peerId === state.peerId ? state.displayName : null);
}

function anyParticipantTalking() {
  return [...state.participants.values()].some(participant => participant.mic);
}

function applyVolumes() {
  const mute = state.muted ? 0 : 1;
  state.sharedAudioPlayer?.setVolume(state.audioVolume * mute);
  for (const audio of state.remoteAudio.values()) {
    audio.volume = (audio.dataset.role === 'shared' ? state.audioVolume : state.voiceVolume) * mute;
  }
}

function showRoomPanel() {
  ui.joinPanel.classList.add('hidden');
  ui.roomPanel.classList.remove('hidden');
  renderRoomState();
}

function showJoinPanel() {
  ui.roomPanel.classList.add('hidden');
  ui.joinPanel.classList.remove('hidden');
  ui.joinButton.disabled = !invitation.valid;
}

function updateConnection(label) {
  ui.roomStatus.textContent = state.roomId ? `Room ${state.roomId} · ${label}` : label;
  ui.detailConnection.textContent = label;
}

function updateConnectionHealth() {
  const slots = [...state.peerConnections.values()];
  const connected = slots.filter(slot => slot.pc.connectionState === 'connected').length;
  ui.detailPeers.textContent = `${connected}/${slots.length}`;
  if (!state.joined) updateConnection('Offline');
  else if (slots.length === 0 || connected === slots.length) updateConnection('Connected');
  else if (slots.some(slot => ['failed', 'disconnected'].includes(slot.pc.connectionState))) updateConnection('Weak connection');
  else updateConnection('Connecting media');
}

function updateAudioDiagnostics(message) {
  ui.detailAudio.textContent = message;
}

function setPeerError(peerId, error) {
  console.error(`PlainCast peer ${peerId}`, error);
  updateConnection('Media reconnecting');
}

function validateEnvelope(envelope) {
  if (!envelope || typeof envelope !== 'object') throw new Error('Invalid message');
  if (envelope.protocolVersion !== PROTOCOL_VERSION) throw new Error('This room uses a different PlainCast version.');
  for (const field of ['type', 'roomId', 'from', 'to', 'id']) {
    if (typeof envelope[field] !== 'string') throw new Error('Invalid message field');
  }
  if (!envelope.payload || typeof envelope.payload !== 'object') throw new Error('Invalid message payload');
}

function readInvitation() {
  const roomId = location.pathname.match(/^\/join\/([A-Z2-9]{4})\/?$/i)?.[1]?.toUpperCase() || '';
  const hash = new URLSearchParams(location.hash.replace(/^#/, ''));
  const storageKey = `plaincast.invite.${roomId}`;
  const token = hash.get('token') || sessionStorage.getItem(storageKey) || '';
  const signalPortValue = hash.get('signalPort') || sessionStorage.getItem(`${storageKey}.port`) || '7412';
  const signalPort = Number(signalPortValue);
  if (token) sessionStorage.setItem(storageKey, token);
  if (Number.isInteger(signalPort)) sessionStorage.setItem(`${storageKey}.port`, String(signalPort));
  if (location.hash) history.replaceState(null, '', location.pathname);
  const valid = /^[A-Z2-9]{4}$/.test(roomId) && /^[0-9a-f]{32}$/i.test(token) && Number.isInteger(signalPort) && signalPort >= 1 && signalPort <= 65535;
  return { roomId, token, signalPort, storageKey, valid, error: valid ? '' : 'This room link is incomplete or invalid.' };
}

function createPeerId() {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return `web-${[...bytes].map(value => value.toString(16).padStart(2, '0')).join('')}`;
}

function browserName() {
  const ua = navigator.userAgent || 'Browser';
  if (/Edg\//.test(ua)) return 'Edge';
  if (/Chrome\//.test(ua)) return 'Chrome';
  if (/Firefox\//.test(ua)) return 'Firefox';
  if (/Safari\//.test(ua)) return 'Safari';
  return 'Browser';
}

function readableError(error, fallback) {
  return error instanceof Error && error.message ? error.message : fallback;
}
function joinReason(reason) {
  const map = { unauthorized: 'The room link is no longer valid.', room_full: 'This room is full.', invalid_join: 'The join request was invalid.', protocol_mismatch: 'Update PlainCast and try again.' };
  return map[reason] || 'The host rejected this connection.';
}
function toBigInt(value) {
  try { return BigInt(value ?? 0); } catch (_) { return 0n; }
}

class SharedAudioPlayer {
  constructor(audioContext, onStatus) {
    this.audioContext = audioContext;
    this.onStatus = onStatus;
    this.authorizedPeerId = null;
    this.authorizedGeneration = 0n;
    this.decoder = null;
    this.worklet = null;
    this.fallback = null;
    this.pending = new Map();
    this.expectedSequence = null;
    this.streamId = null;
    this.missingTimer = null;
    this.droppedPackets = 0;
    this.volume = 1;
    this.gain = audioContext.createGain();
    this.gain.connect(audioContext.destination);
  }

  async initialize() {
    if ('AudioDecoder' in window && this.audioContext.audioWorklet) {
      try {
        const support = await AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 });
        if (support.supported) {
          await this.audioContext.audioWorklet.addModule('/audio-worklet.js');
          this.worklet = new AudioWorkletNode(this.audioContext, 'plaincast-pcm-player', { numberOfOutputs: 1, outputChannelCount: [2] });
          this.worklet.connect(this.gain);
          this.worklet.port.onmessage = event => {
            if (event.data?.type === 'stats') this.onStatus(`${event.data.bufferedMs} ms buffered · ${this.droppedPackets} packets dropped · ${event.data.underruns} underruns`);
          };
          this.decoder = new AudioDecoder({
            output: audioData => this.handleDecodedAudio(audioData),
            error: error => { console.error('Opus decoder error', error); this.reset('Decoder reset'); },
          });
          this.decoder.configure(support.config);
          this.onStatus('Streaming Opus decoder ready');
          return;
        }
      } catch (error) {
        console.warn('Streaming Opus unavailable; using compatibility decoder', error);
      }
    }
    this.fallback = new OggOpusFallbackPlayer(this.audioContext, this.gain, this.onStatus);
    this.onStatus('Compatibility Opus decoder ready');
  }

  setAuthority(peerId, generation) {
    this.authorizedPeerId = peerId;
    this.authorizedGeneration = generation;
    this.reset(peerId ? 'Waiting for audio' : 'Idle', false);
  }

  setVolume(value) {
    this.volume = Math.max(0, Math.min(1, value));
    this.gain.gain.setTargetAtTime(this.volume, this.audioContext.currentTime, 0.01);
  }

  accept(peerId, raw, activePeerId, generation) {
    if (peerId !== activePeerId || peerId !== this.authorizedPeerId || generation !== this.authorizedGeneration) {
      this.droppedPackets += 1;
      return;
    }
    let packet;
    try { packet = parseSharedAudioPacket(raw); }
    catch (_) { this.droppedPackets += 1; return; }
    if (packet.generation !== generation) { this.droppedPackets += 1; return; }
    if (this.streamId !== packet.streamId) {
      this.reset('Audio stream started', false);
      this.streamId = packet.streamId;
      this.expectedSequence = packet.sequence;
    }
    if (this.expectedSequence !== null && packet.sequence < this.expectedSequence) {
      this.droppedPackets += 1;
      return;
    }
    const key = packet.sequence.toString();
    if (this.pending.has(key)) return;
    this.pending.set(key, packet);
    while (this.pending.size > 8) {
      const minimum = this.minimumPendingSequence();
      if (minimum === null) break;
      this.pending.delete(minimum.toString());
      this.expectedSequence = minimum + 1n;
      this.droppedPackets += 1;
    }
    this.drainOrdered();
  }

  drainOrdered() {
    if (this.expectedSequence === null) return;
    let progressed = false;
    while (true) {
      const packet = this.pending.get(this.expectedSequence.toString());
      if (!packet) break;
      this.pending.delete(this.expectedSequence.toString());
      this.expectedSequence += 1n;
      progressed = true;
      if (this.decoder && this.decoder.state === 'configured') {
        if (this.decoder.decodeQueueSize > 5) {
          this.droppedPackets += 1;
        } else {
          this.decoder.decode(new EncodedAudioChunk({
            type: 'key',
            timestamp: Number(packet.captureTimestampUs),
            duration: packet.frameMs * 1000,
            data: packet.payload,
          }));
        }
      } else {
        this.fallback?.accept(packet);
      }
    }
    if (progressed) clearTimeout(this.missingTimer);
    if (this.pending.size && !this.missingTimer) {
      this.missingTimer = setTimeout(() => {
        this.missingTimer = null;
        const next = this.minimumPendingSequence();
        if (next !== null && this.expectedSequence !== null && next > this.expectedSequence) {
          this.droppedPackets += Number(next - this.expectedSequence);
          this.expectedSequence = next;
          this.drainOrdered();
        }
      }, 45);
    }
  }

  handleDecodedAudio(audioData) {
    try {
      const planes = [];
      const channels = Math.min(2, audioData.numberOfChannels);
      for (let channel = 0; channel < channels; channel += 1) {
        const plane = new Float32Array(audioData.numberOfFrames);
        audioData.copyTo(plane, { planeIndex: channel, format: 'f32-planar' });
        planes.push(plane);
      }
      if (planes.length === 1) planes.push(planes[0].slice());
      this.worklet?.port.postMessage({ type: 'audio', planes, frames: audioData.numberOfFrames }, planes.map(plane => plane.buffer));
    } finally {
      audioData.close();
    }
  }

  minimumPendingSequence() {
    let minimum = null;
    for (const packet of this.pending.values()) if (minimum === null || packet.sequence < minimum) minimum = packet.sequence;
    return minimum;
  }

  reset(status = 'Idle', clearAuthority = false) {
    clearTimeout(this.missingTimer);
    this.missingTimer = null;
    this.streamId = null;
    this.expectedSequence = null;
    this.pending.clear();
    this.worklet?.port.postMessage({ type: 'reset' });
    if (this.decoder?.state === 'configured') this.decoder.reset();
    if (this.decoder?.state === 'unconfigured') this.decoder.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 });
    this.fallback?.reset(status);
    if (clearAuthority) {
      this.authorizedPeerId = null;
      this.authorizedGeneration = 0n;
    }
    this.onStatus(status);
  }

  close() {
    this.reset('Closed', true);
    try { this.decoder?.close(); } catch (_) {}
    this.worklet?.disconnect();
    this.fallback?.close();
    this.gain.disconnect();
  }
}

class OggOpusFallbackPlayer {
  constructor(audioContext, gain, onStatus) {
    this.audioContext = audioContext;
    this.gain = gain;
    this.onStatus = onStatus;
    this.batch = [];
    this.queue = [];
    this.decoding = false;
    this.nextStartTime = 0;
    this.serial = Math.floor(Math.random() * 0xffffffff) >>> 0;
    this.epoch = 0;
    this.sources = new Set();
    this.dropped = 0;
  }

  accept(packet) {
    this.batch.push(packet);
    const targetFrames = Math.max(2, Math.ceil(40 / packet.frameMs));
    if (this.batch.length >= targetFrames) this.enqueue(this.batch.splice(0, targetFrames));
  }

  enqueue(batch) {
    while (this.queue.length >= 2) {
      const removed = this.queue.shift();
      this.dropped += removed?.batch.length || 0;
    }
    this.queue.push({ batch, epoch: this.epoch });
    this.process();
  }

  async process() {
    if (this.decoding) return;
    this.decoding = true;
    while (this.queue.length) {
      const item = this.queue.shift();
      if (item.epoch !== this.epoch) continue;
      try {
        const ogg = buildOggOpusChunk(item.batch, this.serial++ >>> 0);
        const buffer = await this.audioContext.decodeAudioData(ogg.slice(0));
        if (item.epoch === this.epoch) this.schedule(buffer);
      } catch (error) {
        console.error('PlainCast Opus fallback decode failed', error);
        this.reset('Opus decode failed');
        break;
      }
    }
    this.decoding = false;
  }

  schedule(buffer) {
    const now = this.audioContext.currentTime;
    if (this.nextStartTime < now - 0.02 || this.nextStartTime > now + 0.12 || this.nextStartTime === 0) {
      this.nextStartTime = now + 0.055;
    }
    const source = this.audioContext.createBufferSource();
    source.buffer = buffer;
    source.connect(this.gain);
    this.sources.add(source);
    source.addEventListener('ended', () => this.sources.delete(source), { once: true });
    source.start(this.nextStartTime);
    this.nextStartTime += buffer.duration;
    this.onStatus(`${Math.max(0, Math.round((this.nextStartTime - now) * 1000))} ms scheduled · ${this.dropped} dropped`);
  }

  reset(status = 'Idle') {
    this.epoch += 1;
    this.batch.length = 0;
    this.queue.length = 0;
    for (const source of this.sources) {
      try { source.stop(); } catch (_) {}
      try { source.disconnect(); } catch (_) {}
    }
    this.sources.clear();
    this.nextStartTime = 0;
    this.onStatus(status);
  }

  close() { this.reset('Closed'); }
}

function parseSharedAudioPacket(raw) {
  if (!(raw instanceof ArrayBuffer) || raw.byteLength < AUDIO_HEADER_BYTES) throw new Error('Short audio packet');
  const view = new DataView(raw);
  if (view.getUint32(0, false) !== AUDIO_MAGIC) throw new Error('Bad audio packet magic');
  if (view.getUint8(4) !== AUDIO_PACKET_VERSION) throw new Error('Bad audio packet version');
  const generation = view.getBigUint64(5, false);
  const streamId = view.getBigUint64(13, false);
  const sequence = view.getBigUint64(21, false);
  const captureTimestampUs = view.getBigUint64(29, false);
  const sampleRate = view.getUint32(37, false);
  const channels = view.getUint8(41);
  const frameMs = view.getUint16(42, false);
  const payloadSize = view.getUint32(44, false);
  if (sampleRate !== 48000 || channels < 1 || channels > 2 || frameMs < 10 || frameMs > 40) throw new Error('Unsupported shared-audio format');
  if (payloadSize < 1 || payloadSize > MAX_AUDIO_PAYLOAD_BYTES || raw.byteLength !== AUDIO_HEADER_BYTES + payloadSize) throw new Error('Invalid shared-audio payload');
  return {
    generation, streamId, sequence, captureTimestampUs, sampleRate, channels, frameMs,
    payload: new Uint8Array(raw, AUDIO_HEADER_BYTES, payloadSize).slice(),
  };
}

function buildOggOpusChunk(batch, serial) {
  if (!Array.isArray(batch) || batch.length === 0) throw new Error('Empty Opus batch');
  const first = batch[0];
  if (!batch.every(packet => packet.channels === first.channels && packet.frameMs === first.frameMs)) throw new Error('Mixed Opus batch');
  const head = new Uint8Array(19);
  writeAscii(head, 0, 'OpusHead');
  head[8] = 1;
  head[9] = first.channels;
  writeUint16LE(head, 10, 0);
  writeUint32LE(head, 12, 48000);
  writeUint16LE(head, 16, 0);
  head[18] = 0;
  const vendor = new TextEncoder().encode('PlainCast');
  const tags = new Uint8Array(8 + 4 + vendor.length + 4);
  writeAscii(tags, 0, 'OpusTags');
  writeUint32LE(tags, 8, vendor.length);
  tags.set(vendor, 12);
  writeUint32LE(tags, 12 + vendor.length, 0);
  const samplesPerFrame = BigInt((48000 * first.frameMs) / 1000);
  const granule = samplesPerFrame * BigInt(batch.length);
  return concatBytes([
    buildOggPage([head], 0n, serial, 0, 0x02),
    buildOggPage([tags], 0n, serial, 1, 0x00),
    buildOggPage(batch.map(packet => packet.payload), granule, serial, 2, 0x04),
  ]).buffer;
}

function buildOggPage(packets, granulePosition, serial, sequence, headerType) {
  const lacing = [];
  let bodyLength = 0;
  for (const packet of packets) {
    bodyLength += packet.length;
    let remaining = packet.length;
    while (remaining >= 255) { lacing.push(255); remaining -= 255; }
    lacing.push(remaining);
  }
  if (lacing.length > 255) throw new Error('Ogg page is too large');
  const page = new Uint8Array(27 + lacing.length + bodyLength);
  writeAscii(page, 0, 'OggS');
  page[4] = 0;
  page[5] = headerType;
  writeUint64LE(page, 6, granulePosition);
  writeUint32LE(page, 14, serial);
  writeUint32LE(page, 18, sequence);
  writeUint32LE(page, 22, 0);
  page[26] = lacing.length;
  page.set(lacing, 27);
  let offset = 27 + lacing.length;
  for (const packet of packets) { page.set(packet, offset); offset += packet.length; }
  writeUint32LE(page, 22, oggCrc(page));
  return page;
}

function oggCrc(bytes) {
  let crc = 0;
  for (const byte of bytes) {
    crc ^= byte << 24;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc & 0x80000000) !== 0 ? ((crc << 1) ^ 0x04c11db7) : (crc << 1);
      crc >>>= 0;
    }
  }
  return crc >>> 0;
}
function concatBytes(parts) {
  const output = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) { output.set(part, offset); offset += part.length; }
  return output;
}
function writeAscii(target, offset, text) { for (let i = 0; i < text.length; i += 1) target[offset + i] = text.charCodeAt(i); }
function writeUint16LE(target, offset, value) { target[offset] = value & 0xff; target[offset + 1] = (value >>> 8) & 0xff; }
function writeUint32LE(target, offset, value) {
  target[offset] = value & 0xff; target[offset + 1] = (value >>> 8) & 0xff;
  target[offset + 2] = (value >>> 16) & 0xff; target[offset + 3] = (value >>> 24) & 0xff;
}
function writeUint64LE(target, offset, value) {
  let remaining = BigInt(value);
  for (let i = 0; i < 8; i += 1) { target[offset + i] = Number(remaining & 0xffn); remaining >>= 8n; }
}
