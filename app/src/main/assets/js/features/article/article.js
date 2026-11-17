// An article, set in the article template. One source — the Markdown (or HTML) of a card — and one way of
// setting it: page() below. The preview in a card and the long picture made for publishing both show the
// page that page() builds, at the same width, so they are the same picture.
//
// The Markdown is never written to from here: rendering reads it and nothing more.

/** The width an article is laid out at, in CSS pixels, and how many pixels of picture each of them becomes. */
export const WIDTH = 540;
export const SCALE = 2;

const HERE = new URL('../../../article/', import.meta.url).href;
const KATEX_CSS = new URL('../../../vendor/katex/katex.min.css', import.meta.url).href;
let parts = null;

async function templateParts() {
  if (!parts) {
    const text = async (url) => { const got = await fetch(url); if (!got.ok) throw new Error('取不到 ' + url); return got.text(); };
    const [template, style] = await Promise.all([text(HERE + 'template.html'), text(HERE + 'article.css')]);
    parts = { template, style };
  }
  return parts;
}

const escape = (text) => String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** Formulas are set by KaTeX before the Markdown is read, so that their signs are not taken for Markdown. */
function withMath(source) {
  const kept = [];
  const keep = (tex, display) => {
    let html;
    try { html = window.katex.renderToString(tex.trim(), { displayMode: display, throwOnError: false, output: 'html' }); }
    catch (_) { html = escape(tex); }
    kept.push(html);
    return (display ? '\n\n' : '') + '\u0000M' + (kept.length - 1) + '\u0000' + (display ? '\n\n' : '');
  };
  if (!window.katex) return { text: source, restore: (html) => html };
  const text = source
    .replace(/\$\$([\s\S]+?)\$\$/g, (_, tex) => keep(tex, true))
    .replace(/(^|[^\\$\w])\$([^\s$][^$\n]*?[^\s$\\]|[^\s$])\$(?![\w$])/g, (_, before, tex) => before + keep(tex, false));
  return { text, restore: (html) => html.replace(/(?:<p>)?\u0000M(\d+)\u0000(?:<\/p>)?/g, (whole, index) => kept[Number(index)]) };
}

/**
 * The article's source as a title and a body of HTML. The title is the first "# " heading, taken out of the
 * body. Source that begins with a tag is HTML and is used as it is, cleaned; anything else is Markdown.
 * [media] turns the id of something attached to the card into an address it can be fetched from.
 */
export function articleParts(source, media = (id) => new URL('/card-media/' + id, location.href).href) {
  let text = String(source || '').replace(/\r\n/g, '\n').trim();
  let title = '';
  const heading = text.match(/^#\s+(.+?)\s*#*\s*(?:\n|$)/);
  if (heading) {
    title = heading[1].trim();
    text = text.slice(heading[0].length).trim();
  }
  let html;
  if (/^<[a-zA-Z!]/.test(text)) {
    html = text;
  } else {
    const math = withMath(text);
    html = math.restore(window.marked.parse(math.text, { gfm: true, breaks: false }));
  }
  // What is attached to the card is named "media:<id>": told apart here, before the cleaning below, which
  // lets through only addresses of kinds it knows.
  const loose = new DOMParser().parseFromString('<body>' + html + '</body>', 'text/html');
  for (const image of Array.from(loose.body.querySelectorAll('img'))) {
    const id = ((image.getAttribute('src') || '').match(/^media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})$/) || [])[1];
    if (!id) continue;
    image.removeAttribute('src');
    image.setAttribute('data-media', id);
  }
  const clean = window.DOMPurify.sanitize(loose.body.innerHTML, { USE_PROFILES: { html: true, mathMl: true }, ADD_ATTR: ['style', 'data-media'] });
  const doc = new DOMParser().parseFromString('<body>' + clean + '</body>', 'text/html');
  if (!title) {
    const first = doc.body.querySelector('h1');
    if (first && first === doc.body.firstElementChild) { title = first.textContent.trim(); first.remove(); }
  }
  // What is attached to the card: pictures are shown; a PDF is shown as its pages; the rest is named.
  for (const image of Array.from(doc.body.querySelectorAll('img[data-media]'))) {
    const id = image.getAttribute('data-media');
    image.removeAttribute('data-media');
    if (!/^m[0-9a-f]{12}\.[a-z0-9]{2,5}$/.test(id)) continue;
    const kind = id.split('.').pop();
    if (/^(jpg|jpeg|png|webp|gif)$/.test(kind)) { image.setAttribute('src', media(id)); continue; }
    const note = doc.createElement('div');
    note.className = 'attachment';
    note.textContent = (kind === 'pdf' ? 'PDF：' : /^(mp4|mov)$/.test(kind) ? '视频：' : '录音：') + (image.getAttribute('alt') || id);
    const block = image.parentElement && image.parentElement.tagName === 'P' && image.parentElement.childNodes.length === 1 ? image.parentElement : image;
    block.replaceWith(note);
  }
  for (const link of doc.body.querySelectorAll('a')) link.removeAttribute('target');
  return { title, body: doc.body.innerHTML };
}

/** The whole page of an article: the template with its style, title and body filled in. */
export async function page(source, media) {
  const { template, style } = await templateParts();
  // Each place is in the template exactly as often as it is meant to be: a template that names one in passing would have it filled there.
  for (const [place, times] of [['{{style}}', 1], ['{{body}}', 1], ['{{title}}', 2]]) {
    if (template.split(place).length - 1 !== times) throw new Error('模板里 ' + place + ' 应该出现 ' + times + ' 次');
  }
  const { title, body } = articleParts(source, media);
  // KaTeX's own stylesheet is linked by its full address: the page is shown from wherever it is put.
  const head = '<link rel="stylesheet" href="' + KATEX_CSS + '" />\n<style>' + style;
  return template
    .replace('<style>{{style}}', () => head)
    .split('{{title}}').join(escape(title))
    .replace('{{body}}', () => body);
}

/** Puts an article's page into [frame], an iframe, and waits until its pictures and fonts are in. Returns its height. */
export async function show(frame, source, media) {
  const html = await page(source, media);
  await new Promise((resolve) => {
    frame.addEventListener('load', resolve, { once: true });
    frame.srcdoc = html;
  });
  const doc = frame.contentDocument;
  await Promise.all(Array.from(doc.images).map((image) => (image.complete ? 0 : new Promise((r) => { image.onload = image.onerror = r; }))));
  if (doc.fonts && doc.fonts.ready) await doc.fonts.ready;
  return Math.ceil(doc.documentElement.getBoundingClientRect().height);
}
