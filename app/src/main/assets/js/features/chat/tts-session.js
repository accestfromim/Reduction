import { callBridge } from '../../core/bridge.js';
import { TtsTextExtractor } from './tts-text.js';

const SENTENCE_END = /[。！？!?；;\n]/;
const CLAUSE_END = /[，,、：:]/;
const FIRST_CHUNK_MIN = 12;
const FIRST_CHUNK_MAX = 32;

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
    let cut = this.buffer.search(SENTENCE_END);

    if (cut < 0 && !this.started) {
      const clause = this.buffer.search(CLAUSE_END);
      if (clause >= FIRST_CHUNK_MIN) {
        cut = clause;
      } else if (this.buffer.length >= FIRST_CHUNK_MAX) {
        const head = this.buffer.slice(0, FIRST_CHUNK_MAX);
        const at = Math.max(
          head.lastIndexOf(' '),
          head.lastIndexOf('，'),
          head.lastIndexOf(','),
          head.lastIndexOf('、'),
          head.lastIndexOf('：'),
          head.lastIndexOf(':')
        );
        cut = at >= FIRST_CHUNK_MIN ? at : FIRST_CHUNK_MAX - 1;
      }
    }

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
