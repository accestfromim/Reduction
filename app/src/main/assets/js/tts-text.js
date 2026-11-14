const MODE = Object.freeze({
  NORMAL: 'normal',
  INLINE_MATH: 'inlineMath',
  DISPLAY_MATH: 'displayMath',
  PAREN_MATH: 'parenMath',
  BRACKET_MATH: 'bracketMath',
  FENCE: 'fence',
  INLINE_CODE: 'inlineCode',
  LINK_LABEL: 'linkLabel',
  LINK_URL: 'linkUrl',
  IMAGE: 'image',
  HTML: 'html',
  URL: 'url'
});

export class TtsTextExtractor {
  constructor() {
    this.reset();
  }

  reset() {
    this.mode = MODE.NORMAL;
    this.buffer = '';
    this.pos = 0;
    this.lineStart = true;
  }

  push(token) {
    this.buffer += String(token || '');
    return this.consume(false);
  }

  finish() {
    const output = this.consume(true);
    this.reset();
    return output;
  }

  consume(final) {
    let output = '';

    while (this.pos < this.buffer.length) {
      const rest = this.buffer.slice(this.pos);

      if (this.mode === MODE.NORMAL) {
        if (!final && this.needsMore(rest)) break;

        if (rest.startsWith('```')) {
          this.mode = MODE.FENCE;
          this.pos += 3;
          continue;
        }
        if (rest.startsWith('$$')) {
          this.mode = MODE.DISPLAY_MATH;
          this.pos += 2;
          continue;
        }
        if (rest.startsWith('\\[')) {
          this.mode = MODE.BRACKET_MATH;
          this.pos += 2;
          continue;
        }
        if (rest.startsWith('\\(')) {
          this.mode = MODE.PAREN_MATH;
          this.pos += 2;
          continue;
        }
        if (rest.startsWith('![')) {
          this.mode = MODE.IMAGE;
          this.pos += 2;
          continue;
        }

        const lower = rest.toLowerCase();
        if (lower.startsWith('http://') || lower.startsWith('https://') || lower.startsWith('www.')) {
          this.mode = MODE.URL;
          this.pos += lower.startsWith('www.') ? 4 : (lower.startsWith('https://') ? 8 : 7);
          continue;
        }

        const ch = rest[0];
        if (ch === '$') {
          const next = rest[1];
          if (next && /[A-Za-z\\{(\[]/.test(next)) {
            this.mode = MODE.INLINE_MATH;
            this.pos += 1;
            continue;
          }
          output += '$';
          this.pos += 1;
          this.lineStart = false;
          continue;
        }
        if (ch === '`') {
          this.mode = MODE.INLINE_CODE;
          this.pos += 1;
          continue;
        }
        if (ch === '[') {
          this.mode = MODE.LINK_LABEL;
          this.pos += 1;
          continue;
        }
        if (ch === '<') {
          const next = rest[1];
          if (next && /[A-Za-z/!]/.test(next)) {
            this.mode = MODE.HTML;
            this.pos += 1;
            continue;
          }
          output += '<';
          this.pos += 1;
          this.lineStart = false;
          continue;
        }
        if (ch === '\\') {
          if (rest.length > 1) {
            output += rest[1];
            this.pos += 2;
          } else {
            output += '\\';
            this.pos += 1;
          }
          this.lineStart = false;
          continue;
        }
        if (ch === '\n') {
          output += ' ';
          this.pos += 1;
          this.lineStart = true;
          continue;
        }
        if (ch === '|') {
          output += ' ';
          this.pos += 1;
          this.lineStart = false;
          continue;
        }
        if (this.lineStart) {
          if (ch === '#' || ch === '-' || ch === '*' || ch === '+' || ch === '>') {
            this.pos += 1;
            continue;
          }
          const ordered = rest.match(/^\d+[.)]\s+/);
          if (ordered) {
            this.pos += ordered[0].length;
            continue;
          }
        }
        if (ch === '*' || ch === '_' || ch === '~') {
          this.pos += 1;
          continue;
        }

        output += ch;
        this.pos += 1;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.INLINE_MATH) {
        const end = rest.indexOf('$');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 1;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.DISPLAY_MATH) {
        const end = rest.indexOf('$$');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 2;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.PAREN_MATH) {
        const end = rest.indexOf('\\)');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 2;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.BRACKET_MATH) {
        const end = rest.indexOf('\\]');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 2;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.FENCE) {
        const end = rest.indexOf('```');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 3;
        this.mode = MODE.NORMAL;
        this.lineStart = true;
        continue;
      }

      if (this.mode === MODE.INLINE_CODE) {
        const end = rest.indexOf('`');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 1;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.IMAGE) {
        const end = rest.indexOf(')');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 1;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.LINK_LABEL) {
        const end = rest.indexOf(']');
        if (end < 0) {
          output += rest;
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        output += rest.slice(0, end);
        this.pos += end + 1;
        this.mode = MODE.LINK_URL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.LINK_URL) {
        const end = rest.indexOf(')');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 1;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.URL) {
        const stop = rest.search(/[\s。！？!?；;，,、()（）\[\]【】<>]/);
        if (stop < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += stop;
        if (/\s/.test(rest[stop])) {
          this.pos += 1;
          output += ' ';
        }
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }

      if (this.mode === MODE.HTML) {
        const end = rest.indexOf('>');
        if (end < 0) {
          this.pos = this.buffer.length;
          if (!final) break;
          this.mode = MODE.NORMAL;
          continue;
        }
        this.pos += end + 1;
        this.mode = MODE.NORMAL;
        this.lineStart = false;
        continue;
      }
    }

    if (this.pos > 0) {
      this.buffer = this.buffer.slice(this.pos);
      this.pos = 0;
    }
    return output;
  }

  needsMore(rest) {
    if (rest === '$' || rest === '!' || rest === '\\' || rest === '<') return true;
    if (rest === '`' || rest === '``') return true;
    const lower = rest.toLowerCase();
    if (['http://', 'https://', 'www.'].some((prefix) => prefix.startsWith(lower) && lower.length < prefix.length)) {
      return true;
    }
    return false;
  }
}

export function toSpeechText(raw) {
  const extractor = new TtsTextExtractor();
  return (extractor.push(raw) + extractor.finish())
    .replace(/\s+/g, ' ')
    .replace(/\s+([。！？!?；;，,])/g, '$1')
    .trim();
}
