import { callBridge } from '../../core/bridge.js';

// An article in the app: the card's text set by article/build.js in the template in use, and shown.
// The same script, with the same template, sets the page the long picture is drawn from, so the preview
// here and the published picture are one page. The card's text is read and nothing more.

export const WIDTH = window.ArticleBuild.WIDTH;
export const SCALE = window.ArticleBuild.SCALE;

const KATEX_CSS = new URL('../../../vendor/katex/katex.min.css', import.meta.url).href;
const BUNDLED = new URL('../../../article/', import.meta.url).href;
let katexStyle = null;

/** KaTeX's stylesheet, its fonts named by their full addresses: the page is shown from wherever it is put. */
async function formulaStyle() {
  if (katexStyle === null) {
    const got = await fetch(KATEX_CSS);
    const base = KATEX_CSS.slice(0, KATEX_CSS.lastIndexOf('/') + 1);
    katexStyle = got.ok ? (await got.text()).replace(/url\(([^)]+)\)/g, (_, file) => 'url(' + base + file + ')') : '';
  }
  return katexStyle;
}

/** The template and stylesheet in use: the user's own cards when there are any, the bundled ones otherwise. */
async function inUse() {
  try {
    const told = JSON.parse(callBridge('articleTemplate') || 'null');
    if (told && told.template && told.style) return told;
  } catch (_) { /* the bundled ones, below */ }
  const text = async (name) => { const got = await fetch(BUNDLED + name); if (!got.ok) throw new Error('取不到 ' + name); return got.text(); };
  return { template: await text('template.html'), style: await text('article.css'), problem: null };
}

/** The whole page of an article. [media] turns the id of something attached into an address to show it from. */
export async function page(source, media = (id) => new URL('/card-media/' + id, location.href).href) {
  const { template, style, problem } = await inUse();
  return { html: window.ArticleBuild.page(source, { template, style, katexStyle: await formulaStyle(), media }), problem };
}

/** Puts an article's page into [frame], an iframe, and waits until its pictures and fonts are in. Returns its height. */
export async function show(frame, source, media) {
  const { html, problem } = await page(source, media);
  await new Promise((resolve) => {
    frame.addEventListener('load', resolve, { once: true });
    frame.srcdoc = html;
  });
  const doc = frame.contentDocument;
  await Promise.all(Array.from(doc.images).map((image) => (image.complete ? 0 : new Promise((r) => { image.onload = image.onerror = r; }))));
  if (doc.fonts && doc.fonts.ready) await doc.fonts.ready;
  return { height: Math.ceil(doc.documentElement.getBoundingClientRect().height), problem };
}
