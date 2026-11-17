// Renders tools/readme/mock.html (the README's markdown, laid out the way GitHub lays it out) to
// tools/readme/preview.png, for a look before the README is committed.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from '../ui-preview/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const types = { '.html': 'text/html; charset=utf-8', '.md': 'text/plain; charset=utf-8', '.png': 'image/png', '.gif': 'image/gif', '.svg': 'image/svg+xml' };

const server = http.createServer((req, res) => {
  const url = decodeURIComponent((req.url || '/').split('?')[0]).replace(/^\/+/, '');
  const file = path.join(root, url);
  if (!file.startsWith(root)) return res.writeHead(403).end();
  if (file.endsWith('/README.preview.md')) {
    // GitHub serves README images relative to the repository root, whatever the page's place;
    // here they are made absolute, so the page sees the same files GitHub would.
    const base = `http://127.0.0.1:${server.address().port}/`;
    const text = fs.readFileSync(process.env.README || path.join(root, 'README.md'), 'utf8')
      .replace(/!\[([^\]]*)\]\((?![a-z]+:)([^)\s]+)\)/g, '![$1](' + base + '$2)')
      .replace(/src="(?![a-z]+:|\/)/g, 'src="' + base);
    return res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }).end(text);
  }
  fs.readFile(file, (err, body) => {
    if (err) return res.writeHead(404).end();
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' }).end(body);
  });
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));

const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: 880, height: 1200, deviceScaleFactor: 2 });
await page.goto(`http://127.0.0.1:${server.address().port}/tools/readme/mock.html`, { waitUntil: 'networkidle0' });
await new Promise((r) => setTimeout(r, 300));
await page.screenshot({ path: path.join(here, 'preview.png'), fullPage: true });
await browser.close();
server.close();
console.log('tools/readme/preview.png');
