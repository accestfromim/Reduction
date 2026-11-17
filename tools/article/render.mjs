#!/usr/bin/env node
// Renders an article to the long picture it is published as.
//
//   node tools/article/render.mjs <article.md|.html> [-o out.png] [--media <dir>] [--html out.html]
//
// The article is set by the same code and the same template the app's preview uses
// (app/src/main/assets/js/features/article/article.js, app/src/main/assets/article/), in a browser
// 540 CSS pixels wide drawn at twice that: a picture 1080 pixels wide and as long as the article.
// --media names the folder attached pictures are read from, for "media:<id>" references.
// --html also writes the finished page, which stands on its own and can be opened or pasted elsewhere.
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets');
const args = process.argv.slice(2);
const take = (flag) => { const at = args.indexOf(flag); return at < 0 ? '' : args.splice(at, 2)[1]; };
const out = take('-o'), mediaDir = take('--media'), htmlOut = take('--html');
const source = args[0];
if (!source) { console.error('usage: render.mjs <article.md|.html> [-o out.png] [--media <dir>] [--html out.html]'); process.exit(2); }
const target = out || source.replace(/\.[^.]*$/, '') + '.png';

const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif' };
// The app's own files, served as the app serves them, and the article's pictures beside them.
let finished = '';
const server = http.createServer((request, response) => {
  const asked = decodeURIComponent(new URL(request.url, 'http://x').pathname);
  if (asked === '/__article__') { response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); response.end(finished); return; }
  const file = asked.startsWith('/card-media/')
    ? (mediaDir && /^\/card-media\/[A-Za-z0-9._-]+$/.test(asked) ? path.join(mediaDir, asked.slice(12)) : '')
    : path.join(assets, path.normalize(asked));
  if (!file || (!asked.startsWith('/card-media/') && !file.startsWith(assets)) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404); response.end(); return;
  }
  response.writeHead(200, { 'Content-Type': types[path.extname(file).toLowerCase()] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(response);
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const base = 'http://127.0.0.1:' + server.address().port;

const { default: puppeteer } = await import(path.resolve(here, '../ui-preview/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js'));
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars'] });
try {
  const tab = await browser.newPage();
  await tab.goto(base + '/article/host.html', { waitUntil: 'load' });
  const { html, width, scale } = await tab.evaluate(async (text) => {
    const article = await import('/js/features/article/article.js');
    return { html: await article.page(text), width: article.WIDTH, scale: article.SCALE };
  }, fs.readFileSync(source, 'utf-8'));
  if (htmlOut) fs.writeFileSync(htmlOut, html);
  await tab.setViewport({ width, height: 800, deviceScaleFactor: scale });
  // The finished page is served under an address of its own, so that it loads as a page does anywhere.
  finished = html;
  await tab.goto(base + '/__article__', { waitUntil: 'load' });
  await tab.evaluate(async () => {
    await Promise.all(Array.from(document.images).map((image) => (image.complete ? 0 : new Promise((r) => { image.onload = image.onerror = r; }))));
    if (document.fonts && document.fonts.ready) await document.fonts.ready;
  });
  const broken = await tab.evaluate(() => Array.from(document.images).filter((image) => !image.naturalWidth).map((image) => image.getAttribute('alt') || image.src));
  if (broken.length) console.error('这些图片没有取到，图上是空的：' + broken.join('、'));
  const height = await tab.evaluate(() => Math.ceil(document.documentElement.getBoundingClientRect().height));
  await tab.screenshot({ path: target, clip: { x: 0, y: 0, width, height }, captureBeyondViewport: true });
  console.log(`${target}: ${width * scale} x ${height * scale}`);
} finally {
  await browser.close();
  server.close();
}
