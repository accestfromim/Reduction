import { drawChart } from './chart-table.js';

const mediaPath = location.origin + '/card-media/';
const policy = "default-src 'none'; style-src 'unsafe-inline'; img-src data: " + mediaPath +
  "; media-src " + mediaPath + "; base-uri 'none'; form-action 'none'";
const styles = `
  .card-text-format:not([hidden]) { display:flex; position:fixed; z-index:5; padding:2px; border:0; border-radius:4px; background:#111; color:#fff; }
  .card-text-format button { background:transparent; color:inherit; border:0; width:26px; height:25px; padding:0; cursor:pointer; }
  .card-text-format button:first-child { font-weight:700; }
  .card-text-format button:last-child { font:italic 16px Georgia,serif; }
  .card-text-format button[aria-pressed="true"] { background:#494949; }

  .card-picture-selected { outline: 2px solid #548bc9; outline-offset: 2px; }
  .card-caption-add { display:block; border:0; background:transparent; color:inherit; opacity:.7; cursor:pointer; font:inherit; font-size:.85em; }
  figcaption { font-size: .875em; text-align: center; }
  figcaption[contenteditable]:focus { outline:none; min-height:1.5em; }
  :is([data-card-privacy], card-private) { border-bottom: 1px dashed currentColor; background: #333; }
  [data-reduction-field][contenteditable]:focus { outline: none; }
  a { color: inherit; }
  body { margin: 0 auto; max-width: 48rem; padding: 1rem; }
  figure.card-paper img, figure.card-paper svg { background: white; }
  img.card-monochrome-logo { background: white; filter: invert(1); }
  html { color-scheme: dark; background: #000; color: #fff; print-color-adjust: exact; }
  body { font: 15px/1.65 system-ui,sans-serif; overflow-wrap: anywhere }
  html[data-card-template="wechat"] { color-scheme: light; background: #fff; color: #222; }
  html[data-card-template="wechat"] :is([data-card-privacy], card-private) { background: #eee; }
  html[data-card-template="wechat"] img.card-monochrome-logo { filter: none; }
  html[data-card-template="wechat"] body { box-sizing: border-box; max-width: 540px; padding: 44px 32px 40px; font-size: 17px; line-height: 1.8; }
  html[data-card-template="wechat"] h1 { font-size: 27px; line-height: 1.4; margin: 0 0 24px; }
  html[data-card-template="wechat"] body > h1:first-of-type::after { content: ""; display: block; width: 36px; height: 2px; margin-top: 14px; background: #222; }
  html[data-card-template="wechat"] p { margin: 0 0 20px; white-space: pre-line; }
  html[data-card-template="wechat"] figure { margin: 24px 0; }
  html[data-card-template="wechat"] figure svg { width: 100%; height: auto; display: block; }
  aside[data-card-routine] { box-sizing: border-box; margin: 0 0 24px; padding: 12px 16px; background: #f4f4f3; color: #555; border-radius: 4px; font-size: 14px; line-height: 1.65; white-space: pre-line; overflow-wrap: anywhere; }
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
    ADD_TAGS: ['card-private', 'card-date'],
    WHOLE_DOCUMENT: wholeDocument,
    RETURN_DOM: true,
    USE_PROFILES: { html: true, svg: true },
    FORBID_TAGS: ['meta', 'base', 'link', 'iframe', 'form', 'foreignObject', 'style']
  });
  for (const el of [fragment, ...fragment.querySelectorAll('[style],[bgcolor],[background],[color],[text],[link],[vlink],[alink],[face]')]) {
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

export function mountHtml(target, html, ready, cardName = '') {
  const frame = document.createElement('iframe');
  frame.title = 'HTML 卡片';
  frame.className = 'card-html-frame';
  frame.setAttribute('sandbox', 'allow-same-origin');
  frame.onload = () => {
    const doc = frame.contentDocument;
    if (!doc || !frame.isConnected) return;
    if (/^Reduction\/公众号文章\/(原文|CARD|模板)(\/|$)/.test(cardName)) {
      doc.documentElement.dataset.cardTemplate = 'wechat';
    }
    renderContents(doc);
    ready?.(doc);
  };
  const head = '<head><meta http-equiv="Content-Security-Policy" content="' + policy + '"><style>' + styles + '</style>';
  frame.srcdoc = '<!doctype html>' + clean(html, true).replace(/<head>/i, head);
  target.replaceChildren(frame);
}
