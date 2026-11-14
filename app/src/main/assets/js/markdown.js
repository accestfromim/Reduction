function escapeHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function looksLikeMath(tex) {
  return /[A-Za-z\\^_=+\-*/{}()[\]]/.test(tex);
}

function protectMath(source) {
  const entries = [];
  const prefix = 'MATHPLACEHOLDER' + Math.random().toString(36).slice(2) + 'X';
  let text = '';
  let index = 0;

  const add = (tex, display) => {
    const token = prefix + entries.length;
    entries.push({ tex, display });
    return token;
  };

  while (index < source.length) {
    if (source.startsWith('```', index)) {
      const end = source.indexOf('```', index + 3);
      const stop = end < 0 ? source.length : end + 3;
      text += source.slice(index, stop);
      index = stop;
      continue;
    }

    if (source[index] === '`') {
      const end = source.indexOf('`', index + 1);
      const stop = end < 0 ? source.length : end + 1;
      text += source.slice(index, stop);
      index = stop;
      continue;
    }

    if (source.startsWith('$$', index)) {
      const end = source.indexOf('$$', index + 2);
      if (end < 0) {
        text += source.slice(index);
        break;
      }
      text += add(source.slice(index + 2, end), true);
      index = end + 2;
      continue;
    }

    if (source.startsWith('\\[', index)) {
      const end = source.indexOf('\\]', index + 2);
      if (end < 0) {
        text += source.slice(index);
        break;
      }
      text += add(source.slice(index + 2, end), true);
      index = end + 2;
      continue;
    }

    if (source.startsWith('\\(', index)) {
      const end = source.indexOf('\\)', index + 2);
      if (end < 0) {
        text += source.slice(index);
        break;
      }
      text += add(source.slice(index + 2, end), false);
      index = end + 2;
      continue;
    }

    if (source[index] === '$' && source[index - 1] !== '\\') {
      const end = source.indexOf('$', index + 1);
      if (end > index + 1) {
        const tex = source.slice(index + 1, end);
        if (looksLikeMath(tex)) {
          text += add(tex, false);
          index = end + 1;
          continue;
        }
      }
    }

    text += source[index];
    index += 1;
  }

  return { text, entries, prefix };
}

function renderMathToken(entry) {
  if (!window.katex) return escapeHtml(entry.tex);
  try {
    return window.katex.renderToString(entry.tex, {
      displayMode: entry.display,
      throwOnError: false,
      output: 'htmlAndMathml'
    });
  } catch (error) {
    return escapeHtml(entry.tex);
  }
}

export function renderMarkdown(text) {
  const source = String(text || '');
  if (!source) return '';
  if (!window.marked || !window.DOMPurify) {
    return escapeHtml(source).replace(/\n/g, '<br>');
  }

  try {
    const protectedMath = protectMath(source);
    const html = window.marked.parse(protectedMath.text, { gfm: true, breaks: true });
    const clean = window.DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
    const pattern = new RegExp(protectedMath.prefix + '(\\d+)', 'g');
    return clean.replace(pattern, (_, index) => renderMathToken(protectedMath.entries[Number(index)]));
  } catch (error) {
    console.warn('markdown render failed', error);
    return escapeHtml(source).replace(/\n/g, '<br>');
  }
}
