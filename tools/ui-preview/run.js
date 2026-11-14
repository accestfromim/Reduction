import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../app/src/main/assets');
const screenshots = path.join(here, 'screenshots');
const chrome = process.env.CHROME_PATH || '/usr/bin/google-chrome';
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf'
};

fs.mkdirSync(screenshots, { recursive: true });

const server = http.createServer((req, res) => {
  const requestPath = decodeURIComponent((req.url || '/').split('?')[0]);
  const file = path.resolve(root, '.' + (requestPath === '/' ? '/index.html' : requestPath));
  if (!file.startsWith(root)) {
    res.writeHead(403).end();
    return;
  }
  fs.readFile(file, (error, data) => {
    if (error) {
      res.writeHead(404).end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
});

await new Promise((resolve) => server.listen(8766, '127.0.0.1', resolve));

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
});
const page = await browser.newPage();
await page.setViewport({ width: 412, height: 915, deviceScaleFactor: 2, isMobile: true, hasTouch: true });

const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text());
});

await page.evaluateOnNewDocument(() => {
  window.__ttsChunks = [];
  window.Android = {
    loadSettings: () => JSON.stringify({
      apiKey: 'preview-key',
      model: 'deepseek-flash',
      voice: 'stella',
      autoSpeak: true,
      pcUrl: 'http://192.168.1.10:8765',
      pcToken: 'preview-token',
      webSearchEnabled: true,
      calculatorEnabled: true,
      wakeWordEnabled: true
    }),
    saveSettings: () => true,
    loadHistory: () => '[]',
    saveHistory: () => true,
    clearHistory: () => {},
    requestMicPermission: () => setTimeout(() => window.Native.onMicPermission(true), 10),
    startRecording: (id) => setTimeout(() => window.Native.onRecordingStart(id), 10),
    stopAndTranscribe: (id) => setTimeout(() => window.Native.onAsrResult(id, true, '你好'), 10),
    cancelRecording: () => {},
    startHandsFree: (id, followup) => {
      setTimeout(() => window.Native.onHandsFreeStart(id), 10);
      if (!followup) setTimeout(() => window.Native.onAsrResult(id, true, '你好'), 80);
      else setTimeout(() => window.Native.onHandsFreeTimeout(id), 120);
    },
    cancelHandsFree: () => {},
    resumeWakeWord: () => {},
    consumeWakePending: () => false,
    setWakeWordEnabled: () => true,
    sendChat: (id, json) => {
      const lastUser = (JSON.parse(json).filter((m) => m.role === 'user').pop() || {}).content || '';
      if (lastUser.includes('公式')) {
        const full = '# 公式\n\n质能方程 $E=mc^2$，如下：\n\n$$\nE=mc^2\n$$\n\n```js\nconsole.log(1)\n```\n完成。';
        window.Native.onChatToken(id, '# 公式\n\n');
        window.Native.onChatToken(id, '质能方程 $E=mc^2$，');
        window.Native.onChatToken(id, '如下：\n\n$$\nE=mc^2\n$$\n\n');
        window.Native.onChatToken(id, '```js\nconsole.log(1)\n```\n');
        window.Native.onChatToken(id, '完成。');
        window.Native.onChatDone(id, full);
      } else {
        window.Native.onChatToken(id, '你好，');
        window.Native.onChatToken(id, '我在。');
        window.Native.onChatDone(id, '你好，我在。');
      }
      return true;
    },
    ttsStart: (id) => setTimeout(() => window.Native.onTtsStart(id), 10),
    ttsAppend: (id, text) => window.__ttsChunks.push(text),
    ttsFinish: (id) => setTimeout(() => window.Native.onTtsDone(id), 30),
    speak: (id) => setTimeout(() => window.Native.onTtsDone(id), 30),
    stopSpeaking: () => {},
    clearAudioCache: () => '已清理 1.2 MB 音频缓存',
    vibrate: () => {},
    getAppInfo: () => JSON.stringify({
      version: '0.9.4',
      android: '15',
      sdk: 35,
      device: 'HONOR 500 Pro',
      hasKey: true,
      pcUrl: 'http://192.168.1.10:8765',
      cacheMb: 1.2
    }),
    shareDiagnostics: () => {},
    pcInfo: (id) => setTimeout(() => window.Native.onPcInfo(id, JSON.stringify({
      hostname: 'DESKTOP-MIND',
      os: 'windows/amd64',
      user: 'user',
      ips: ['192.168.1.10'],
      time: '2026-11-15T00:00:00+08:00'
    })), 10),
    pcExec: (id, command) => setTimeout(() => window.Native.onPcExec(id, `ok: ${command}`), 10),
    pcCheckUpdate: (id) => setTimeout(() => window.Native.onUpdateNone(id, '0.6.0'), 10)
  };
});

await page.goto('http://127.0.0.1:8766/index.html', { waitUntil: 'networkidle0' });
await new Promise((resolve) => setTimeout(resolve, 300));

async function shot(name) {
  await page.screenshot({ path: path.join(screenshots, name), fullPage: false });
}

await shot('01-empty.png');

await page.evaluate(() => {
  document.getElementById('micBtn').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
  window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
});
await new Promise((resolve) => setTimeout(resolve, 100));
await page.type('#textInput', '公式');
await page.click('#sendBtn');
await new Promise((resolve) => setTimeout(resolve, 500));
await shot('02-markdown-math.png');

await page.click('#settingsBtn');
await new Promise((resolve) => setTimeout(resolve, 150));
await shot('03-settings.png');
await page.click('#closeSettings');

await page.click('#pcBtn');
await new Promise((resolve) => setTimeout(resolve, 100));
await page.click('#pcTest');
await new Promise((resolve) => setTimeout(resolve, 150));
await shot('04-pc.png');
await page.click('#closePc');

await page.evaluate(() => window.Native.onWakeWord(''));
await new Promise((resolve) => setTimeout(resolve, 100));
await shot('05-wake-listening.png');

console.log('screenshots:', screenshots);
console.log('tts chunks:', await page.evaluate(() => window.__ttsChunks));
if (errors.length) {
  console.error('errors:', errors);
  process.exitCode = 1;
} else {
  console.log('no js errors');
}

await browser.close();
server.close();
