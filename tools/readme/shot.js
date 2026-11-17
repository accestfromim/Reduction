// Renders tools/readme/banner.html to docs/banner.png with Chrome.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from '../ui-preview/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const server = http.createServer((req, res) => {
  const file = path.join(here, (req.url || '/').split('?')[0]);
  if (!file.startsWith(here)) return res.writeHead(403).end();
  fs.readFile(file, (err, body) => err ? res.writeHead(404).end() : res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(body));
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
  args: ['--no-sandbox', '--force-device-scale-factor=1', '--hide-scrollbars'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1600, height: 420, deviceScaleFactor: 2 });
await page.goto(`http://127.0.0.1:${port}/banner.html`, { waitUntil: 'networkidle0' });
await page.screenshot({ path: path.join(here, '../../docs/banner.png'), clip: { x: 0, y: 0, width: 1600, height: 420 } });
await browser.close();
server.close();
console.log('docs/banner.png');
