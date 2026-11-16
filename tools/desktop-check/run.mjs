// Drives Reduction's computer version end to end in a headless browser, against a stand-in
// for the model service: no key is used and nothing is spent. Run after ./desktop.sh has built it.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../ui-preview/package.json', import.meta.url));
const puppeteer = require('puppeteer-core');

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'reduction-desktop-'));
const asked = { chat: [], tts: [], asr: 0 };

// The stand-in: answers a chat with a tool call and then words, speech with a tone, recognition with text.
const model = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    if (req.url === '/chat/completions') {
      const request = JSON.parse(body.toString());
      asked.chat.push(request);
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      const last = request.messages[request.messages.length - 1];
      const send = (delta, finish) => res.write('data: ' + JSON.stringify({ choices: [{ delta, finish_reason: finish || null }] }) + '\n\n');
      if (last.role === 'user' && last.content.includes('记')) {
        send({ tool_calls: [{ index: 0, id: 'c1', function: { name: 'patch_cards', arguments: JSON.stringify({ summary: '记到备忘卡片', patch: '*** Add Card: 备忘\nPurpose: 零碎待办\n+- 买电池' }) } }] });
        send({}, 'tool_calls');
      } else {
        send({ content: last.role === 'tool' ? '记好了，放在备忘卡片里。' : '你好，我在电脑上。' });
        send({}, 'stop');
      }
      res.end('data: [DONE]\n\n');
    } else if (req.url === '/v1/tools/tts') {
      asked.tts.push(JSON.parse(body.toString()).text);
      const pcm = Buffer.alloc(24000 * 2 * 0.3);
      for (let i = 0; i < pcm.length / 2; i++) pcm.writeInt16LE(Math.round(6000 * Math.sin(i * 0.11)), i * 2);
      res.writeHead(200, { 'content-type': 'application/octet-stream' });
      res.end(pcm);
    } else if (req.url === '/v1/tools/asr') {
      asked.asr++;
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ text: '这是说出来的话，完毕。' }));
    } else {
      res.writeHead(404); res.end('{}');
    }
  });
}).listen(8791);

const app = spawn(path.join(root, 'desktop/build/install/reduction/bin/reduction'), ['8792'], {
  env: { ...process.env, HOME: home, JAVA_OPTS: '-Duser.home=' + home, REDUCTION_API_BASE: 'http://127.0.0.1:8791', DEEPSEEK_API_KEY: '' }
});
let log = '';
app.stdout.on('data', (d) => { log += d; });
app.stderr.on('data', (d) => { log += d; });
await new Promise((resolve) => { const t = setInterval(() => { if (log.includes('已启动')) { clearInterval(t); resolve(); } }, 100); setTimeout(resolve, 20000); });

const errors = [];
const check = (name, ok, detail) => { console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok ? '' : ' ' + JSON.stringify(detail))); if (!ok) errors.push(name); };
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage();
page.on('pageerror', (e) => errors.push('page error: ' + e));
await page.setViewport({ width: 1280, height: 860 });
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const text = (sel) => page.evaluate((s) => document.querySelector(s)?.textContent || '', sel);

try {
  await page.goto('http://127.0.0.1:8792/', { waitUntil: 'networkidle2' });
  await wait(2200);
  // Sound the page is asked to play is counted, to know that speech really reaches it.
  await page.evaluate(() => {
    window.__played = 0;
    const create = AudioContext.prototype.createBufferSource;
    AudioContext.prototype.createBufferSource = function () { window.__played++; return create.call(this); };
  });

  // A request from another site has no way to give orders.
  const foreign = await page.evaluate(async () => (await fetch('/bridge/loadSettings', { method: 'POST', body: '[]' })).status);
  check('orders without the page\'s header are refused', foreign === 403, foreign);

  // No key yet: the settings sheet is offered; saving one writes a file only the owner can read.
  check('settings are offered when there is no key', await page.evaluate(() => !document.getElementById('settingsSheet').classList.contains('hidden')));
  await page.type('#apiKeyInput', 'sk-test-key');
  await page.click('#saveSettings');
  await wait(300);
  const file = path.join(home, '.config/reduction/settings.json');
  check('the key is saved to a private file', fs.existsSync(file) && (fs.statSync(file).mode & 0o777) === 0o600 && fs.readFileSync(file, 'utf8').includes('sk-test-key'));

  // A typed question: answered, shown, and spoken through the browser.
  await page.click('#micBtn');
  await page.type('#textInput', '你好');
  await page.keyboard.press('Enter');
  await wait(2500);
  const bubbles = await page.evaluate(() => Array.from(document.querySelectorAll('.msg-row .bubble')).map((b) => b.textContent.trim()));
  check('a typed question is answered', bubbles.join('|') === '你好|你好，我在电脑上。', bubbles);
  check('the answer is spoken in the browser', asked.tts.some((t) => t.includes('我在电脑上')) && await page.evaluate(() => window.__played) > 0, asked.tts);
  const system = asked.chat[0].messages[0].content;
  check('the assistant knows it is on a computer', system.includes('运行在电脑上') && system.includes('patch_cards'));
  await wait(1200);
  check('the page hears that speaking is over', (await text('#status')).trim() === '', await text('#status'));

  // A tool call: one line in the stream, and the card exists afterwards.
  await page.type('#textInput', '记一下买电池');
  await page.keyboard.press('Enter');
  await wait(3000);
  check('a tool call shows as one line', (await text('.tool-line .tool-summary')) === '记到备忘卡片' && await page.evaluate(() => !!document.querySelector('.tool-line.done')));
  await page.click('#tabCards');
  await wait(300);
  const names = await page.evaluate(() => Array.from(document.querySelectorAll('#cardList .card-row')).map((r) => r.textContent));
  check('the card the assistant wrote is listed, with the task ledger', names.includes('备忘') && names.includes('sub agent 任务'), names);
  check('cards are kept on this computer', fs.readdirSync(path.join(home, '.local/share/reduction/cards')).filter((f) => f.endsWith('.md')).length === 2);
  await page.evaluate(() => Array.from(document.querySelectorAll('#cardList .card-row')).find((r) => r.textContent === 'sub agent 任务').click());
  check('the task ledger cannot be edited', await page.evaluate(() => document.getElementById('cardEdit').classList.contains('hidden') && document.getElementById('cardBody').textContent.includes('程序直接写入')));
  await page.click('#cardBack');
  await page.click('#tabStream');

  // Holding the microphone button: recorded here, recognised, and sent as a question.
  const before = asked.chat.length;
  await page.evaluate(() => document.getElementById('voiceBtn').click());
  const mic = await page.evaluate(() => { const r = document.getElementById('micBtn').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });
  await page.mouse.move(mic.x, mic.y);
  await page.mouse.down();
  await wait(1800);
  await page.mouse.up();
  await wait(3000);
  const said = await page.evaluate(() => Array.from(document.querySelectorAll('.msg-row.user .bubble')).map((b) => b.textContent.trim()).pop());
  check('holding the microphone button asks by voice', asked.asr === 1 && said === '这是说出来的话' && asked.chat.length > before, { asr: asked.asr, said });

  // The wide-screen layout, and what only a phone can do is not offered.
  const layout = await page.evaluate(() => ({ width: document.querySelector('.app').getBoundingClientRect().width, pc: getComputedStyle(document.getElementById('pcBtn')).display }));
  check('the page sits in one column on a wide screen', layout.width <= 822 && layout.pc === 'none', layout);
  await page.screenshot({ path: '/tmp/desktop-check.png' });

  // History survives a restart of the page.
  await page.reload({ waitUntil: 'networkidle2' });
  await wait(2000);
  check('the conversation is still there after reloading', (await page.evaluate(() => document.querySelectorAll('.msg-row').length)) >= 6);
} catch (error) {
  errors.push(String(error));
}
await browser.close();
app.kill();
model.close();
if (errors.length) { console.log('errors:', errors); console.log(log.slice(-1500)); process.exitCode = 1; } else console.log('all checks passed');
