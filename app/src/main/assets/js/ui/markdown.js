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

function compactUrl(href) {
  try {
    const url = new URL(href);
    const host = url.hostname.replace(/^www\./i, '');
    const first = url.pathname.split('/').filter(Boolean)[0];
    const label = first ? `${host}/${first}` : host;
    return label.length > 34 ? `${label.slice(0, 33)}…` : label;
  } catch (error) {
    return href;
  }
}

function compactLinks(html) {
  if (!html.includes('<a ')) return html;
  try {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    doc.querySelectorAll('a[href]').forEach((anchor) => {
      const href = anchor.getAttribute('href') || '';
      const text = (anchor.textContent || '').trim();
      if (/^https?:\/\//i.test(text) || text === href) {
        anchor.textContent = compactUrl(href);
      }
      anchor.setAttribute('target', '_blank');
      anchor.setAttribute('rel', 'noopener noreferrer');
      anchor.setAttribute('title', href);
    });
    return doc.body.innerHTML;
  } catch (error) {
    return html;
  }
}

export function renderMarkdown(text) {
  const source = String(text || '');
  if (!source) return '';
  if (!window.marked || !window.DOMPurify) {
    return escapeHtml(source).replace(/\n/g, '<br>');
  }

  try {
    // Drawings written into the text — a block of code marked svg, or an <svg> element — are shown as
    // drawings. They are taken out before the Markdown is read and put back after, so that what is inside
    // one is not read as Markdown; scripts in them are cleaned away with everything else.
    const drawings = [];
    const keepDrawing = (svg) => { drawings.push(svg.trim()); return '\n\n\u0000S' + (drawings.length - 1) + '\u0000\n\n'; };
    const withoutDrawings = source
      .replace(/(^|\n)```svg[^\n]*\n([\s\S]*?)\n```(?=\n|$)/g, (_, before, svg) => before + keepDrawing(svg))
      .split(/(\n```[\s\S]*?\n```|^```[\s\S]*?\n```)/)
      .map((part, index) => (index % 2 ? part : part.replace(/<svg[\s\S]*?<\/svg>/gi, keepDrawing)))
      .join('');
    const protectedMath = protectMath(withoutDrawings);
    const parsed = window.marked.parse(protectedMath.text, { gfm: true, breaks: true })
      .replace(/(?:<p>)?\u0000S(\d+)\u0000(?:<\/p>)?/g, (_, index) => '<figure class="drawing">' + drawings[Number(index)] + '</figure>');
    const clean = window.DOMPurify.sanitize(parsed, { USE_PROFILES: { html: true, svg: true, svgFilters: true }, FORBID_TAGS: ['foreignObject'] });
    const pattern = new RegExp(protectedMath.prefix + '(\\d+)', 'g');
    return compactLinks(clean.replace(pattern, (_, index) => renderMathToken(protectedMath.entries[Number(index)])));
  } catch (error) {
    console.warn('markdown render failed', error);
    return escapeHtml(source).replace(/\n/g, '<br>');
  }
}
