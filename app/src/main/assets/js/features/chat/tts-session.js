import { callBridge } from '../../core/bridge.js';
import { TtsTextExtractor } from './tts-text.js';

const SENTENCE_END = /[。！？!?；;\n]/g;

export class TtsSession {
  constructor(id) {
    this.id = id;
    this.extractor = new TtsTextExtractor();
    this.buffer = '';
    this.started = false;
    this.finished = false;
  }

  push(text) {
    if (this.finished || !text) return;
    this.buffer += this.extractor.push(text);
    this.flush(false);
  }

  finish() {
    if (this.finished) return this.started;
    this.finished = true;
    this.buffer += this.extractor.finish();
    this.flush(true);
    if (this.started) {
      callBridge('ttsFinish', this.id, '');
    } else {
      const text = this.buffer.trim();
      this.buffer = '';
      if (text) {
        this.emit(text);
        callBridge('ttsFinish', this.id, '');
      }
    }
    return this.started;
  }

  stop() {
    this.finished = true;
    this.buffer = '';
  }

  flush(final) {
    if (!this.buffer) return;
    SENTENCE_END.lastIndex = 0;
    let cut = -1;
    let match;
    while ((match = SENTENCE_END.exec(this.buffer)) !== null) cut = match.index;

    if (cut < 0) {
      if (final) {
        this.emit(this.buffer);
        this.buffer = '';
      }
      return;
    }

    const chunk = this.buffer.slice(0, cut + 1);
    this.buffer = this.buffer.slice(cut + 1);
    this.emit(chunk);
    if (final && this.buffer.trim()) {
      this.emit(this.buffer);
      this.buffer = '';
    }
  }

  emit(text) {
    const chunk = String(text || '').trim();
    if (!chunk) return;
    if (!this.started) {
      this.started = true;
      callBridge('ttsStart', this.id);
    }
    callBridge('ttsAppend', this.id, chunk);
  }
}
