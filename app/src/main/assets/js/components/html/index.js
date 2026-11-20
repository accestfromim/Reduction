import { drawChart } from './chart-table.js';

const mediaPath = location.origin + '/card-media/';
const policy = "default-src 'none'; style-src 'unsafe-inline'; img-src data: " + mediaPath +
  "; media-src " + mediaPath + "; base-uri 'none'; form-action 'none'";
const styles = `
  a { color: inherit; }
  body { margin: 0 auto; max-width: 48rem; padding: 1rem; }
  img.card-monochrome-logo { background: white; filter: invert(1); }
  html { color-scheme: dark; background: #000; color: #fff; print-color-adjust: exact; }
  body { font: 15px/1.65 system-ui,sans-serif; overflow-wrap: anywhere }
  :where(table) { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums }
  :where(caption) { text-align: start; font-weight: 650; margin-bottom: .4em }
  :where(td, th) { text-align: start; padding: .25em; border-bottom: 1px solid #8886 }
  blockquote[cite] { margin: 1em 0; padding-inline-start: 1em; border-inline-start: 2px solid #888; }
  blockquote[cite] p { white-space: pre-wrap; }
  blockquote[cite] footer { font-size: .85em; }
  ol[data-timeline] { list-style: none; padding-inline-start: .75em; }
  ol[data-timeline] > li { position: relative; padding: 0 0 .8em 1.1em; border-inline-start: 1px solid currentColor; break-inside: avoid; }
  ol[data-timeline] > li:last-child { border-color: transparent; padding-bottom: 0; }
  ol[data-timeline] > li::before { content: ""; position: absolute; inset-inline-start: -.24em; top: .6em; width: .42em; height: .42em; border-radius: 50%; background: currentColor; }
  img, svg, audio, video { max-width: 100% }
  audio, video { display: block; width: 100% }
`;

function clean(html, wholeDocument) {
  const fragment = window.DOMPurify.sanitize(html, {
    WHOLE_DOCUMENT: wholeDocument,
    RETURN_DOM: true,
    USE_PROFILES: { html: true, svg: true },
    FORBID_TAGS: ['meta', 'base', 'link', 'iframe', 'form', 'foreignObject', 'style']
  });
  for (const el of [fragment, ...fragment.querySelectorAll('*')]) {
    // Preserve intrinsic media presentation while keeping the document theme host-owned.
    const logo = el.tagName === 'IMG' && el.style?.filter === 'invert(1)';
    const width = el.tagName === 'IMG' ? el.style?.width : '';
    for (const attr of ['style','bgcolor','background','color','text','link','vlink','alink','face']) el.removeAttribute(attr);
    if (width && /^\d+(?:\.\d+)?(?:%|px|em|rem)$/.test(width)) el.style.width = width;
    if (logo) el.classList.add('card-monochrome-logo');
  }
  return wholeDocument ? fragment.outerHTML : fragment.innerHTML;
}

function renderContents(root) {
  for (const link of root.querySelectorAll('a[data-path]')) {
    link.href = '#';
    link.title = link.dataset.path;
    if (!link.textContent.trim()) link.textContent = link.dataset.path;
  }
  for (const node of root.querySelectorAll('img[data-attachment], audio[data-attachment], video[data-attachment]')) {
    const match = /^media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})$/.exec(node.dataset.attachment);
    if (match) node.src = mediaPath + match[1];
    const file = /^media:([0-9a-f]{32})$/.exec(node.dataset.attachment);
    if (file) node.src = mediaPath + 'file/' + file[1];
  }
  root.querySelectorAll('table[data-chart]').forEach(drawChart);
}

// The editor supplies only the block whose source changed. Keep the surrounding frame and players alive.
export function updateHtmlBlock(block, html) {
  block.innerHTML = clean(html, false);
  renderContents(block);
  const table = block.closest('table[data-chart]');
  if (table) drawChart(table);
}

export function mountHtml(target, html, ready) {
  const frame = document.createElement('iframe');
  frame.title = 'HTML 卡片';
  frame.className = 'card-html-frame';
  frame.setAttribute('sandbox', 'allow-same-origin');
  frame.onload = () => {
    const doc = frame.contentDocument;
    if (!doc || !frame.isConnected) return;
    renderContents(doc);
    ready?.(doc);
  };
  const head = '<head><meta http-equiv="Content-Security-Policy" content="' + policy + '"><style>' + styles + '</style>';
  frame.srcdoc = '<!doctype html>' + clean(html, true).replace(/<head>/i, head);
  target.replaceChildren(frame);
}
