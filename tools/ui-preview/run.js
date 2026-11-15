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
  window.__spoken = [];
  window.__replies = 0;
  window.__cards = {};
  window.Android = {
    loadSettings: () => JSON.stringify({
      apiKey: 'preview-key',
      model: 'deepseek-flash',
      voice: 'stella',
      autoSpeak: true,
      pcHost: 'desktop.tailnet.ts.net',
      pcPort: 22,
      pcUser: 'user',
      webSearchEnabled: true,
      calculatorEnabled: true,
      wakeWordEnabled: true
    }),
    saveSettings: () => true,
    listCards: () => JSON.stringify(Object.entries(window.__cards).map(([name, card]) => ({
      name, purpose: card.purpose, chars: card.text.length, updatedAt: 0
    }))),
    readCard: (name) => (window.__cards[name] || {}).text || '',
    cardPurpose: (name) => (window.__cards[name] || {}).purpose || '',
    saveCard: (name, purpose, text) => { window.__cards[name] = { purpose, text }; return true; },
    deleteCard: (name) => { delete window.__cards[name]; return true; },
    loadHistory: () => '[]',
    clearHistory: () => {},
    requestMicPermission: () => setTimeout(() => window.Native.onMicPermission(true), 10),
    startRecording: (id) => setTimeout(() => window.Native.onRecordingStart(id), 10),
    stopAndTranscribe: (id) => setTimeout(() => window.Native.onAsrResult(id, true, '你好'), 10),
    cancelRecording: () => {},
    voiceState: () => JSON.stringify({ state: 'IDLE', followup: false }),
    voiceFinish: () => {},
    voiceCancel: () => window.Native.onVoiceState('IDLE', false),
    setWakeWordEnabled: () => true,
    submitText: (text) => {
      const id = 'r' + (++window.__replies);
      window.Native.onReplyBegin(id, text);
      if (text.includes('公式')) {
        const full = '# 公式\n\n质能方程 $E=mc^2$，如下：\n\n$$\nE=mc^2\n$$\n\n```js\nconsole.log(1)\n```\n完成。';
        window.Native.onChatToken(id, '# 公式\n\n');
        window.Native.onChatToken(id, '质能方程 $E=mc^2$，');
        window.Native.onChatToken(id, '如下：\n\n$$\nE=mc^2\n$$\n\n');
        window.Native.onChatToken(id, '```js\nconsole.log(1)\n```\n');
        window.Native.onChatToken(id, '完成。');
        window.Native.onChatDone(id, full);
      } else if (text.includes('工具')) {
        window.Native.onChatToken(id, '我先查一下。');
        setTimeout(() => window.Native.onToolStarted(id, 'web_search', JSON.stringify({ query: '荣耀 500 Pro 参数' })), 350);
        setTimeout(() => window.Native.onToolFinished(id, 'web_search', 1234, true, '1. 荣耀 500 Pro 参数…'), 1500);
        setTimeout(() => window.Native.onChatDone(id, '查到了，荣耀 500 Pro 的主要参数如下。'), 1700);
      } else {
        window.Native.onChatToken(id, '你好，');
        window.Native.onChatToken(id, '我在。');
        window.Native.onChatDone(id, '你好，我在。');
      }
      return true;
    },
    speak: (id, text) => {
      window.__spoken.push(text);
      setTimeout(() => window.Native.onTtsStart(id), 10);
      setTimeout(() => window.Native.onTtsDone(id), 30);
    },
    copyText: (text) => { window.__copied = text; },
    stopSpeaking: () => {},
    clearAudioCache: () => '已清理 1.2 MB 音频缓存',
    vibrate: () => {},
    getAppInfo: () => JSON.stringify({
      version: '0.9.4',
      android: '15',
      sdk: 35,
      device: 'HONOR 500 Pro',
      hasKey: true,
      pcHost: 'desktop.tailnet.ts.net',
      pcPort: 22,
      pcUser: 'user',
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
    pcPublicKey: () => 'ssh-rsa AAAAB3NzaC1yc2E preview dsassist@android',
    pcConnect: () => {},
    pcConnected: () => true,
    pcState: () => 'connected',
    updateState: () => 'idle',
    updateVersion: () => ''
  };
});

await page.goto('http://127.0.0.1:8766/index.html', { waitUntil: 'networkidle0' });
await new Promise((resolve) => setTimeout(resolve, 300));

async function shot(name) {
  await page.screenshot({ path: path.join(screenshots, name), fullPage: false });
}

await shot('01-empty.png');

await page.evaluate(() => {
  window.__cards['科研想法·检索'] = { purpose: '向量检索方向的想法和待验证的假设', text: '## 粗筛\n\n- 用倒排索引做粗筛\n\n## 待验证\n\n- 对比 HNSW 的召回' };
  window.__cards['备忘'] = { purpose: '零碎待办', text: '- 周五前交报告' };
  window.Native.onCardsChanged('科研想法·检索');
});
await new Promise((resolve) => setTimeout(resolve, 80));
await shot('12-brief.png');
const cardsShown = await page.evaluate(async () => {
  const names = () => Array.from(document.querySelectorAll('#cardChips .card-chip:not(.add)')).map((chip) => chip.textContent);
  const before = names().slice().sort();
  const purpose = document.getElementById('cardPurpose').textContent;
  const preview = document.getElementById('cardPreview').textContent;
  Array.from(document.querySelectorAll('#cardChips .card-chip')).find((chip) => chip.textContent === '备忘').click();
  const second = document.getElementById('cardPreview').textContent;

  // Create a card by hand.
  document.querySelector('#cardChips .card-chip.add').click();
  const nameShown = !document.getElementById('cardNameField').classList.contains('hidden');
  document.getElementById('cardNameInput').value = '科研想法·评测';
  document.getElementById('cardPurposeInput').value = '评测方法与指标';
  document.getElementById('cardSave').click();
  const after = names().slice().sort();

  return {
    before, after, nameShown,
    purpose: purpose.includes('向量检索方向') && purpose.includes(' 字'),
    newestShown: preview.includes('用倒排索引做粗筛'),
    second: second.includes('周五前交报告')
  };
});
console.log('cards:', JSON.stringify(cardsShown));
if (cardsShown.before.join() !== '备忘,科研想法·检索' || cardsShown.after.join() !== '备忘,科研想法·检索,科研想法·评测' || !cardsShown.nameShown ||
    !cardsShown.purpose || !cardsShown.newestShown || !cardsShown.second) {
  throw new Error('cards did not behave: ' + JSON.stringify(cardsShown));
}
await page.click('#cardBackdrop').catch(() => {});

await page.evaluate(() => window.Native.onUpdateState('available', '1.0.13', 0));
await shot('09-update-available.png');
await page.evaluate(() => window.Native.onUpdateState('downloading', '1.0.13', 42));
await shot('10-update-downloading.png');
await page.evaluate(() => window.Native.onUpdateState('ready', '1.0.13', 100));
await shot('11-update-ready.png');
await page.evaluate(() => window.Native.onUpdateState('idle', '', 0));

await page.evaluate(() => {
  document.getElementById('micBtn').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
  window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
});
await new Promise((resolve) => setTimeout(resolve, 100));
await page.type('#textInput', '公式');
await page.click('#sendBtn');
await new Promise((resolve) => setTimeout(resolve, 500));
await shot('02-markdown-math.png');

const bubbleCenter = await page.evaluate(() => {
  const bubble = document.querySelector('.msg-row.assistant .bubble');
  const rect = bubble.getBoundingClientRect();
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
});
await page.evaluate(({ x, y }) => {
  const bubble = document.querySelector('.msg-row.assistant .bubble');
  bubble.dispatchEvent(new PointerEvent('pointerdown', {
    bubbles: true, pointerId: 7, pointerType: 'touch', clientX: x, clientY: y
  }));
}, bubbleCenter);
await new Promise((resolve) => setTimeout(resolve, 380));
await shot('06-message-menu.png');

const copyCenter = await page.evaluate(() => {
  const rect = document.getElementById('messageCopy').getBoundingClientRect();
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
});
await page.evaluate(({ x, y }) => {
  window.dispatchEvent(new PointerEvent('pointermove', {
    bubbles: true, pointerId: 7, pointerType: 'touch', clientX: x, clientY: y
  }));
}, copyCenter);
await new Promise((resolve) => setTimeout(resolve, 60));
await shot('07-message-menu-active.png');
await page.evaluate(({ x, y }) => {
  window.dispatchEvent(new PointerEvent('pointerup', {
    bubbles: true, pointerId: 7, pointerType: 'touch', clientX: x, clientY: y
  }));
}, copyCenter);
await new Promise((resolve) => setTimeout(resolve, 80));
const copiedLength = await page.evaluate(() => (window.__copied || '').length);
if (!copiedLength) throw new Error('message copy action did not run');
await page.evaluate(() => {
  document.getElementById('textInput').value = '';
});
await page.type('#textInput', '工具');
await page.click('#sendBtn');
await new Promise((resolve) => setTimeout(resolve, 1800));
await shot('08-tool-trace.png');

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

// Hands-free, drawn from the states the app reports.
await page.evaluate(() => {
  window.Native.onVoiceState('PREPARING', false);
  window.Native.onVoiceState('LISTENING', false);
  window.Native.onVoicePartial('帮我查一下明天的天气');
});
await new Promise((resolve) => setTimeout(resolve, 100));
await shot('05-wake-listening.png');

// One full hands-free round, driven the way the app drives it: the page never sends the words itself.
const round = await page.evaluate(async () => {
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const status = () => document.getElementById('status').textContent;
  const barShown = () => !document.getElementById('recordingBar').classList.contains('hidden');
  const bubbles = () => document.querySelectorAll('.bubble').length;
  const seen = [];
  window.Native.onVoiceState('TURN_ACK', false);
  seen.push(['TURN_ACK', status(), barShown()]);
  const before = bubbles();
  window.Native.onVoiceState('REPLYING', false);
  window.Native.onReplyBegin('spoken-1', '你好');
  window.Native.onChatToken('spoken-1', '你好，');
  window.Native.onChatDone('spoken-1', '你好，我在。');
  await wait(50);
  seen.push(['REPLYING', status(), barShown(), bubbles() - before]);
  window.Native.onVoiceState('PREPARING', true);
  window.Native.onVoiceState('LISTENING', true);
  seen.push(['LISTENING', status(), barShown()]);
  window.Native.onVoiceState('IDLE', false);
  seen.push(['IDLE', status(), barShown()]);
  return seen;
});
console.log('hands-free round:', JSON.stringify(round));
const expected = [['TURN_ACK', '好的', false], ['REPLYING', '', false, 2], ['LISTENING', '继续说话…', true], ['IDLE', '', false]];
if (JSON.stringify(round) !== JSON.stringify(expected)) errors.push('hands-free round rendered ' + JSON.stringify(round));

// A spoken exchange that happened with no page showing appears once the page hears of it.
const missed = await page.evaluate(async () => {
  window.Android.loadHistory = () => JSON.stringify([{ role: 'user', content: '熄屏时说的话' }, { role: 'assistant', content: '【朗读】\n熄屏时的回答' }]);
  window.Native.onChatDone('unknown-reply', '【朗读】\n熄屏时的回答');
  const text = document.getElementById('messages').textContent;
  return { shown: text.includes('熄屏时说的话') && text.includes('熄屏时的回答') };
});
console.log('missed reply:', JSON.stringify(missed));
if (!missed.shown) errors.push('a reply made without the page was not shown');


// A reply is drawn as two blocks: what is read aloud, and what is only shown.
const reply = await page.evaluate(async () => {
  const ui = await import('/js/ui/ui.js');
  const node = document.createElement('div');
  ui.renderAssistantContent(node, '【朗读】\n结论是可以。\n【详情】\n**依据**：原文');
  const plain = document.createElement('div');
  ui.renderAssistantContent(plain, '【朗读】\n已更新');
  const streaming = document.createElement('div');
  ui.renderAssistantContent(streaming, '【朗读】\n结论是可以。\n【详');
  return {
    blocks: node.querySelectorAll('.reply-part').length,
    spoken: node.querySelector('.reply-part.spoken')?.textContent.includes('结论是可以'),
    detail: node.querySelector('.reply-part.detail')?.textContent.includes('原文'),
    tagsShown: /【朗读】|【详情】/.test(node.textContent + plain.textContent),
    plain: plain.textContent.trim(),
    halfTagShown: streaming.textContent.includes('【')
  };
});
console.log('reply blocks:', JSON.stringify(reply));
if (reply.blocks !== 2 || !reply.spoken || !reply.detail || reply.tagsShown || reply.plain !== '已更新' || reply.halfTagShown) {
  errors.push('reply rendered ' + JSON.stringify(reply));
}

// A tool card shows its whole input and output and can be opened.
const card = await page.evaluate(async () => {
  const ui = await import('/js/ui/ui.js');
  const trace = document.createElement('div');
  document.body.appendChild(trace);
  const longInput = 'x'.repeat(900);
  ui.addToolTrace(trace, 'run_agent', JSON.stringify({ prompt: longInput }));
  ui.finishToolTrace(trace, 'run_agent', 4200, true, 'y'.repeat(3000));
  const el = trace.querySelector('.tool-card');
  const body = el.querySelector('.tool-body');
  const before = { height: body.clientHeight, scrollable: body.scrollHeight > body.clientHeight, overflow: getComputedStyle(body).overflowY };
  el.querySelector('.tool-head').click();
  const after = { height: body.clientHeight, open: el.classList.contains('open') };
  const text = body.textContent;
  trace.remove();
  return { before, after, fullInput: text.includes(longInput), fullOutput: text.includes('y'.repeat(3000)) };
});
console.log('tool card:', JSON.stringify(card));
if (!card.fullInput || !card.fullOutput || card.before.overflow !== 'auto' || !card.before.scrollable || !card.after.open || card.after.height <= card.before.height) {
  errors.push('tool card ' + JSON.stringify(card));
}

if (errors.length) {
  console.error('errors:', errors);
  process.exitCode = 1;
} else {
  console.log('no js errors');
}

await browser.close();
server.close();
