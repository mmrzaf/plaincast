'use strict';

class PlainCastPcmPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.queuedFrames = 0;
    this.current = null;
    this.offset = 0;
    this.started = false;
    this.targetFrames = Math.round(sampleRate * 0.04);
    this.maxFrames = Math.round(sampleRate * 0.12);
    this.droppedFrames = 0;
    this.underruns = 0;
    this.port.onmessage = event => this.handle(event.data);
  }

  handle(message) {
    if (!message || typeof message.type !== 'string') return;
    if (message.type === 'reset') {
      this.queue.length = 0;
      this.queuedFrames = 0;
      this.current = null;
      this.offset = 0;
      this.started = false;
      return;
    }
    if (message.type !== 'audio' || !Array.isArray(message.planes) || !message.frames) return;
    const item = { planes: message.planes, frames: message.frames };
    this.queue.push(item);
    this.queuedFrames += item.frames;
    while (this.queuedFrames > this.maxFrames && this.queue.length > 1) {
      const removed = this.queue.shift();
      this.queuedFrames -= removed.frames;
      this.droppedFrames += removed.frames;
    }
  }

  process(_inputs, outputs) {
    const output = outputs[0];
    const frames = output[0]?.length || 128;
    for (const channel of output) channel.fill(0);
    if (!this.started && this.queuedFrames >= this.targetFrames) this.started = true;
    if (!this.started) return true;

    let written = 0;
    while (written < frames) {
      if (!this.current) {
        this.current = this.queue.shift() || null;
        this.offset = 0;
        if (!this.current) {
          this.started = false;
          this.underruns += 1;
          this.port.postMessage({ type: 'stats', bufferedMs: 0, droppedFrames: this.droppedFrames, underruns: this.underruns });
          break;
        }
      }
      const available = this.current.frames - this.offset;
      const count = Math.min(available, frames - written);
      for (let channelIndex = 0; channelIndex < output.length; channelIndex += 1) {
        const source = this.current.planes[Math.min(channelIndex, this.current.planes.length - 1)];
        output[channelIndex].set(source.subarray(this.offset, this.offset + count), written);
      }
      this.offset += count;
      written += count;
      this.queuedFrames -= count;
      if (this.offset >= this.current.frames) this.current = null;
    }
    if ((currentFrame & 2047) === 0) {
      this.port.postMessage({
        type: 'stats',
        bufferedMs: Math.round((this.queuedFrames / sampleRate) * 1000),
        droppedFrames: this.droppedFrames,
        underruns: this.underruns,
      });
    }
    return true;
  }
}

registerProcessor('plaincast-pcm-player', PlainCastPcmPlayer);
