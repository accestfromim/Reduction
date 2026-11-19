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
      name, purpose: card.purpose, chars: card.text.length, updatedAt: 0, prompt: card.prompt === true
    }))),
    readCard: (name) => (window.__cards[name] || {}).text || '',
    cardPurpose: (name) => (window.__cards[name] || {}).purpose || '',
    saveCard: (name, purpose, text) => { window.__cards[name] = { purpose, text }; return true; },
    deleteCard: (name) => { delete window.__cards[name]; return true; },
    renameCard: (from, to) => { if (window.__cards[to] || !window.__cards[from]) return false; window.__cards[to] = window.__cards[from]; delete window.__cards[from]; return true; },
    lockCard: (name, locked) => { if (!window.__cards[name]) return false; window.__cards[name].prompt = locked; return true; },
    floatingCard: () => window.__floating || '',
    floatCard: (name) => { window.__floating = name; return true; },
    loadHistory: () => '[]',
    clearHistory: () => {},
    requestMicPermission: () => setTimeout(() => window.Native.onMicPermission(true), 10),
    startRecording: (id) => setTimeout(() => window.Native.onRecordingStart(id), 10),
    stopAndTranscribe: (id) => setTimeout(() => window.Native.onAsrResult(id, true, '你好'), 10),
    cancelRecording: () => {},
    voiceState: () => JSON.stringify({ state: 'IDLE' }),
    voiceFinish: () => {},
    voiceCancel: () => window.Native.onVoiceState('IDLE'),
    setWakeWordEnabled: () => true,
    submitPictures: (text, pictures) => {
      window.__sentPictures = JSON.parse(pictures);
      const id = 'p' + (++window.__replies);
      window.Native.onReplyBegin(id, text || '请看图。');
      // The reply is brought to its end, as the app would: nothing is left half drawn for what follows.
      window.Native.onChatToken(id, '看到了。');
      window.Native.onChatDone(id, '看到了。');
      return true;
    },
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
        setTimeout(() => window.Native.onToolStarted(id, 'web_search', '搜索荣耀 500 Pro 的参数', JSON.stringify({ summary: '搜索荣耀 500 Pro 的参数', query: '荣耀 500 Pro 参数' })), 350);
        setTimeout(() => window.Native.onToolFinished(id, 'web_search', 1234, true, '1. 荣耀 500 Pro 参数…'), 1500);
        setTimeout(() => window.Native.onToolStarted(id, 'run_agent', '派 sub agent 核对官网参数', JSON.stringify({ summary: '派 sub agent 核对官网参数', action: 'start', task: '核对官网参数' })), 1550);
        setTimeout(() => window.Native.onToolFinished(id, 'run_agent', 96, true, '【完成】官网参数一致'), 1650);
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
    memoView: () => JSON.stringify(window.__plan || {}),
    setMemoDone: (id, done) => {
      const all = ['today', 'future', 'undated'].flatMap((block) => window.__plan[block] || []);
      const item = all.find((each) => each.id === id);
      if (!item) return false;
      item.done = done;
      return true;
    },
    askForExactAlarms: () => { window.__asked = (window.__asked || []).concat(['exact']); return true; },
    askForNotifications: () => { window.__asked = (window.__asked || []).concat(['notify']); return true; },
    updateState: () => 'idle',
    updateVersion: () => ''
  };
});

await page.goto('http://127.0.0.1:8766/index.html', { waitUntil: 'networkidle0' });

// The opening animation: the logo assembles without taking touches, then shrinks into its box in the top bar.
const splash = await page.evaluate(async () => {
  const el = document.getElementById('splash');
  const logo = document.getElementById('logoGlyph');
  const during = { pointer: getComputedStyle(el).pointerEvents, circles: el.querySelectorAll('circle').length, logoHidden: logo.style.opacity === '0' };
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  // Just before it lands, the drawing must be over the logo's box, at the logo's size.
  let landing = null;
  for (let i = 0; i < 40 && !landing; i++) {
    await wait(50);
    landing = el.querySelector('svg').getAnimations().find((a) => !(a.animationName));
  }
  landing.pause();
  landing.currentTime = landing.effect.getTiming().duration;
  // The logo is the animation's last frame: both are three circles of radius 82, the outer two
  // centred 59 to either side and hidden within 106 of the middle. Measured in the logo's own units.
  const [core, left, right] = Array.from(document.querySelectorAll('#glyph > *')).map((shape) => shape.getBBox());
  const shape = {
    core: [core.x, core.width].map(Math.round).join(),
    left: [left.x, left.x + left.width, left.y, left.y + left.height].map(Math.round).join(),
    right: [right.x, right.x + right.width, right.y, right.y + right.height].map(Math.round).join()
  };
  const drawn = el.querySelector('.splash-core').getBoundingClientRect();
  const target = logo.getBoundingClientRect();
  // The middle circle is 164 of the logo's 312 units wide.
  const fit = {
    dx: Math.abs((drawn.left + drawn.width / 2) - (target.left + target.width / 2)),
    dy: Math.abs((drawn.top + drawn.height / 2) - (target.top + target.height / 2)),
    size: Math.abs(drawn.width - target.width * 164 / 312)
  };
  landing.finish();
  await wait(100);
  return { during, fit, shape, gone: !document.getElementById('splash'), logoShown: logo.style.opacity === '', logoWidth: target.width };
});
console.log('splash:', JSON.stringify(splash));
// Left arc: from the outer circle's edge (197 - 82) to where the two circles cross (x = 188).
const shape = (found) => found.core !== '174,164' || found.left !== '115,188,174,338' || found.right !== '324,397,174,338';
if (splash.during.pointer !== 'none' || splash.during.circles !== 4 || !splash.during.logoHidden ||
    shape(splash.shape) || splash.fit.dx > 0.6 || splash.fit.dy > 0.6 || splash.fit.size > 0.6 || !splash.gone || !splash.logoShown || splash.logoWidth < 20) {
  throw new Error('opening animation did not behave: ' + JSON.stringify(splash));
}

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
// The cards page lists names only; a card opens over the whole screen.
const cardsShown = await page.evaluate(async () => {
  const names = () => Array.from(document.querySelectorAll('#cardList .card-row')).map((row) => row.textContent);
  const hidden = (id) => document.getElementById(id).classList.contains('hidden');
  const streamFirst = !hidden('streamPage') && hidden('cardsPage');
  document.getElementById('tabCards').click();
  const onCards = hidden('streamPage') && !hidden('cardsPage');
  const before = names().slice().sort();
  const listText = document.getElementById('cardList').textContent;

  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.textContent === '科研想法·检索').click();
  const crumbs = document.getElementById('cardCrumbs');
  const top = (id) => Math.round(document.getElementById(id).getBoundingClientRect().top);
  const tabsBefore = [top('tabStream'), top('tabCards')];
  const opened = {
    shown: !hidden('cardPage'),
    // The tabs stay where they were, on show, with the card's name on their line; the card lies under them.
    fills: !hidden('tabStream') && !hidden('tabCards') && hidden('cardsPage') && !hidden('cardCrumbs') &&
      Math.abs(crumbs.getBoundingClientRect().top - top('tabCards')) < 12 &&
      document.getElementById('cardPage').getBoundingClientRect().top >= document.querySelector('.tabs').getBoundingClientRect().bottom - 1,
    title: crumbs.querySelector('.crumb.here').textContent,
    heading: !!document.querySelector('#cardBody h2'),
    text: document.getElementById('cardBody').textContent.includes('用倒排索引做粗筛'),
    reading: hidden('cardEditor') && !hidden('cardBody')
  };
  // One tap locks the card as a prompt: it can no longer be edited, until it is unlocked.
  const lock = document.getElementById('cardLock');
  lock.click();
  const lockedNow = window.__cards['科研想法·检索'].prompt === true && hidden('cardEdit') && lock.textContent === '解锁' && crumbs.querySelector('.crumb.here').textContent.startsWith('🔒');
  lock.click();
  opened.locks = lockedNow && window.__cards['科研想法·检索'].prompt === false && !hidden('cardEdit') && lock.textContent === '审核并锁定';
  // The tabs only change the page. "朗读" goes to speech with the card left open; "卡片" comes back to it; pressed
  // again it changes nothing. What is being typed is in the one input under both pages, and stays.
  document.getElementById('micBtn').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
  window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
  document.getElementById('textInput').value = '写到一半的话';
  const composerOnCard = document.querySelector('.composer').getBoundingClientRect().height > 0 && !hidden('textMode');
  document.getElementById('tabStream').click();
  const atSpeech = !hidden('streamPage') && hidden('cardPage') && hidden('cardCrumbs') && document.getElementById('tabStream').classList.contains('active');
  const keptAtSpeech = document.getElementById('textInput').value === '写到一半的话' && !hidden('textMode');
  document.getElementById('tabCards').click();
  const cameBack = !hidden('cardPage') && !hidden('cardCrumbs') && hidden('streamPage') && crumbs.querySelector('.crumb.here').textContent === '科研想法·检索';
  document.getElementById('tabCards').click();
  const stayed = !hidden('cardPage') && hidden('cardsPage') && document.getElementById('textInput').value === '写到一半的话';
  // The first step of the path is the list.
  crumbs.querySelector('button.crumb').click();
  const atList = hidden('cardPage') && !hidden('cardsPage') && hidden('cardCrumbs') && document.getElementById('textInput').value === '写到一半的话';
  document.getElementById('textInput').value = '';
  document.getElementById('voiceBtn').click();
  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.textContent === '科研想法·检索').click();
  opened.tabs = composerOnCard && atSpeech && keptAtSpeech && cameBack && stayed && atList && !hidden('cardPage') &&
    tabsBefore.join() === [top('tabStream'), top('tabCards')].join();
  // One tap on the reading page copies the whole card.
  window.__copied = '';
  document.getElementById('cardCopyAll').click();
  opened.copied = window.__copied === window.__cards['科研想法·检索'].text.trim() && !hidden('cardCopyAll');
  // The open card can be floated over other apps, and put away again.
  const float = document.getElementById('cardFloat');
  float.click();
  const floated = window.__floating === '科研想法·检索' && float.textContent === '取消悬浮';
  float.click();
  opened.floats = floated && window.__floating === '' && float.textContent === '悬浮';
  return { streamFirst, onCards, before, namesOnly: !listText.includes('向量检索方向') && !listText.includes('倒排'), opened };
});
await shot('12-card-open.png');
const cardsEdited = await page.evaluate(async () => {
  const names = () => Array.from(document.querySelectorAll('#cardList .card-row')).map((row) => row.textContent);
  const hidden = (id) => document.getElementById(id).classList.contains('hidden');
  // The assistant changes the open card: the page follows.
  window.__cards['科研想法·检索'].text += '\n- 新加的一条';
  window.Native.onCardsChanged('科研想法·检索');
  const followed = document.getElementById('cardBody').textContent.includes('新加的一条');
  // Edit by hand.
  document.getElementById('cardEdit').click();
  const editing = !hidden('cardEditor') && hidden('cardBody') && !hidden('cardNameField') && hidden('cardCopyAll');
  document.getElementById('cardText').value = '改过的正文';
  document.getElementById('cardSave').click();
  const saved = hidden('cardEditor') && document.getElementById('cardBody').textContent.includes('改过的正文');
  const backToList = window.onAndroidBack() === true && hidden('cardPage');
  // Create a card by hand.
  document.getElementById('cardNew').click();
  const nameShown = !hidden('cardNameField') && hidden('cardDelete');
  document.getElementById('cardNameInput').value = '科研想法·评测';
  document.getElementById('cardPurposeInput').value = '评测方法与指标';
  document.getElementById('cardSave').click();
  const after = names().slice().sort();
  window.onAndroidBack();
  const backToStream = window.onAndroidBack() === true && !hidden('streamPage');
  return { followed, editing, saved, backToList, nameShown, after, backToStream, nothingLeft: window.onAndroidBack() === false };
});
console.log('cards:', JSON.stringify(cardsShown), JSON.stringify(cardsEdited));
if (!cardsShown.streamFirst || !cardsShown.onCards || cardsShown.before.join() !== '备忘,科研想法·检索' || !cardsShown.namesOnly ||
    Object.values(cardsShown.opened).some((value) => !value) || cardsShown.opened.title !== '科研想法·检索' ||
    !cardsEdited.followed || !cardsEdited.editing || !cardsEdited.saved || !cardsEdited.backToList || !cardsEdited.nameShown ||
    cardsEdited.after.join() !== '备忘,科研想法·检索,科研想法·评测' || !cardsEdited.backToStream || !cardsEdited.nothingLeft) {
  throw new Error('cards did not behave: ' + JSON.stringify(cardsShown) + JSON.stringify(cardsEdited));
}
// A picture in the input box: it waits in the strip, goes out with the message, and shows in its bubble.
const sentPicture = await page.evaluate(async () => {
  const pictures = await import('./js/features/chat/pictures.js');
  document.getElementById('tabChat') && document.getElementById('tabChat').click();
  const canvas = document.createElement('canvas');
  canvas.width = 3200; canvas.height = 1600;
  const ctx = canvas.getContext('2d'); ctx.fillStyle = '#c33'; ctx.fillRect(0, 0, 3200, 1600);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
  await pictures.add([new File([blob], '截图.png', { type: 'image/png' }), new File(['x'], 'a.txt', { type: 'text/plain' })]);
  const waiting = document.querySelectorAll('#pictureStrip .picture-thumb').length;
  const stripShown = !document.getElementById('pictureStrip').classList.contains('hidden');
  const canSend = !document.getElementById('sendBtn').disabled;
  document.getElementById('sendBtn').click();
  await new Promise((resolve) => setTimeout(resolve, 60));
  const sent = window.__sentPictures || [];
  const size = await new Promise((resolve) => { const i = new Image(); i.onload = () => resolve([i.width, i.height]); i.src = 'data:image/jpeg;base64,' + sent[0].base64; });
  const bubbles = document.querySelectorAll('.msg-row.user .bubble-pictures img').length;
  return { waiting, stripShown, canSend, count: sent.length, type: sent[0] && sent[0].mediaType, name: sent[0] && sent[0].name, size,
           after: document.querySelectorAll('#pictureStrip .picture-thumb').length, bubbles };
});
console.log('picture in the input box:', JSON.stringify(sentPicture));
if (sentPicture.waiting !== 1 || !sentPicture.stripShown || !sentPicture.canSend || sentPicture.count !== 1 || sentPicture.type !== 'image/jpeg' ||
    sentPicture.name !== '截图' || sentPicture.size.join() !== '1600,800' || sentPicture.after !== 0 || sentPicture.bubbles !== 1) {
  throw new Error('a picture in the input box did not behave: ' + JSON.stringify(sentPicture));
}
// The other view of a card: the article it is published as. Asked for each time; the card's text is left alone.
const articleView = await page.evaluate(async () => {
  const hidden = (id) => document.getElementById(id).classList.contains('hidden');
  window.__cards['文章'] = { purpose: '稿子', text: '# 标题在这里\n\n第一段，**重点**。\n\n## 小节\n\n- 一\n- 二' };
  window.Native.onCardsChanged('');
  document.getElementById('tabCards').click();
  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.dataset.name === '文章').click();
  const asText = !hidden('cardBody') && hidden('cardArticle') && document.getElementById('cardPreview').textContent === '预览';
  document.getElementById('cardPreview').click();
  const frame = document.getElementById('cardArticleFrame');
  for (let i = 0; i < 100 && !(frame.contentDocument && frame.contentDocument.querySelector('.article-title') && frame.style.height); i++) await new Promise((r) => setTimeout(r, 50));
  const doc = frame.contentDocument;
  const shown = { title: doc.querySelector('.article-title').textContent, h2: doc.querySelector('.article-body h2').textContent, width: frame.style.width,
                  inPreview: hidden('cardBody') && !hidden('cardArticle') && document.getElementById('cardPreview').textContent === '原文' };
  const untouched = window.__cards['文章'].text.startsWith('# 标题在这里');
  document.getElementById('cardPreview').click();
  const back = !hidden('cardBody') && hidden('cardArticle');
  // Opened again, a card is its text.
  document.getElementById('cardPreview').click();
  document.querySelector('#cardCrumbs button.crumb').click();
  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.dataset.name === '文章').click();
  const reopened = !hidden('cardBody') && hidden('cardArticle');
  document.querySelector('#cardCrumbs button.crumb').click();
  delete window.__cards['文章'];
  window.Native.onCardsChanged('');
  return { asText, shown, untouched, back, reopened };
});
console.log('article view:', JSON.stringify(articleView));
if (!articleView.asText || articleView.shown.title !== '标题在这里' || articleView.shown.h2 !== '小节' || articleView.shown.width !== '540px' ||
    !articleView.shown.inPreview || !articleView.untouched || !articleView.back || !articleView.reopened) {
  throw new Error('the article view did not behave: ' + JSON.stringify(articleView));
}
// Folders: a card renamed to "文件夹/名称" moves under that folder, which can be closed and opened.
const folders = await page.evaluate(async () => {
  const hidden = (id) => document.getElementById(id).classList.contains('hidden');
  document.getElementById('tabCards').click();
  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.textContent === '科研想法·检索').click();
  document.getElementById('cardEdit').click();
  document.getElementById('cardNameInput').value = '科研 / 检索';
  document.getElementById('cardSave').click();
  // The page shows the card's own name large and its folders above it; the whole name is on the title itself.
  const title = document.getElementById('cardCrumbs').title;
  const shownAs = Array.from(document.querySelectorAll('#cardCrumbs .crumb')).map((el) => el.textContent).slice(1);
  window.onAndroidBack();
  window.__cards['科研/评测'] = { purpose: '评测', text: '指标' };
  window.Native.onCardsChanged('');
  const rows = () => Array.from(document.querySelectorAll('#cardList > *')).map((el) => (el.classList.contains('card-folder') ? '[' + el.firstChild.textContent + ' ' + el.lastChild.textContent + ']' : (el.classList.contains('inside') ? '  ' : '') + el.textContent));
  const open = rows();
  document.querySelector('#cardList .card-folder').click();
  const closed = rows();
  document.querySelector('#cardList .card-folder').click();
  // Every "/" is a folder: a folder inside a folder is drawn inside it, and closes on its own.
  window.__cards['科研/基准/代码/SWE'] = { purpose: '', text: '' };
  window.__cards['科研/基准/综述'] = { purpose: '', text: '' };
  window.Native.onCardsChanged('');
  const tree = () => Array.from(document.querySelectorAll('#cardList > *')).map((el) => {
    const pad = Math.round((parseFloat(getComputedStyle(el).paddingLeft) || 0) / 18);
    return '>'.repeat(pad) + (el.classList.contains('card-folder') ? '[' + el.firstChild.textContent + ' ' + el.lastChild.textContent + ']' : el.textContent);
  });
  const deep = tree();
  document.querySelector('#cardList .card-folder[data-folder="科研/基准"]').click();
  const deepClosed = tree();
  document.querySelector('#cardList .card-folder[data-folder="科研/基准"]').click();
  delete window.__cards['科研/基准/代码/SWE'];
  delete window.__cards['科研/基准/综述'];
  window.Native.onCardsChanged('');
  // A folder named beside the tabs is a way back to the list, with that folder open and in view.
  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.dataset.name === '科研/检索').click();
  document.querySelector('#cardList .card-folder') && null;
  localStorage.setItem('reduction_closed_folders', JSON.stringify(['科研']));
  document.querySelectorAll('#cardCrumbs button.crumb')[1].click();
  const crumbBack = document.getElementById('cardPage').classList.contains('hidden') && !document.getElementById('cardsPage').classList.contains('hidden') &&
    Array.from(document.querySelectorAll('#cardList .card-row')).some((row) => row.dataset.name === '科研/检索');
  return { title, shownAs, crumbBack, open, closed, deep, deepClosed, kept: !!window.__cards['科研/检索'] && !window.__cards['科研想法·检索'] };
});
console.log('folders:', JSON.stringify(folders));
if (folders.title !== '科研/检索' || folders.shownAs.join('|') !== '科研|检索' || !folders.crumbBack || !folders.kept || !folders.open.includes('[科研 2]') || !folders.open.includes('  检索') || !folders.open.includes('  评测') ||
    folders.closed.includes('  检索') || !folders.closed.includes('[科研 2]') || folders.open.indexOf('备忘') > folders.open.indexOf('[科研 2]')) {
  throw new Error('folders did not behave: ' + JSON.stringify(folders));
}
{
  const at = folders.deep.indexOf('[科研 4]');
  const want = ['[科研 4]', '>检索', '>评测', '>[基准 2]', '>>综述', '>>[代码 1]', '>>>SWE'];
  const got = folders.deep.slice(at, at + want.length);
  if (at < 0 || got.slice().sort().join('|') !== want.slice().sort().join('|') ||
      got.indexOf('>[基准 2]') > got.indexOf('>>综述') || got.indexOf('>>[代码 1]') > got.indexOf('>>>SWE') ||
      !folders.deepClosed.includes('>[基准 2]') || folders.deepClosed.includes('>>综述') || folders.deepClosed.includes('>>>SWE') ||
      !folders.deepClosed.includes('>检索')) {
    throw new Error('nested folders did not behave: ' + JSON.stringify(folders.deep) + JSON.stringify(folders.deepClosed));
  }
}
await shot('13-card-list.png');

// The review card for to-dos: three blocks, a tap finishes a to-do, and the card says what the
// reminders cannot do instead of promising anything.
await page.evaluate(() => {
  window.__cards['计划'] = { purpose: '待办与时间', text: '由程序写入' };
  window.__plan = {
    today: [
      { id: 4, text: '交周报', done: false, due: '11-16', overdue: true },
      { id: 7, text: '给医生打电话', done: false, due: '今天', overdue: false },
      { id: 2, text: '回邮件', done: true, due: '今天', overdue: false }
    ],
    future: [
      { id: 9, text: '取快递', done: false, due: '明天', overdue: false },
      { id: 10, text: '出差前的准备', done: false, due: '11-21', overdue: false }
    ],
    undated: [
      { id: 3, text: '想想假期去哪', done: false, due: 'empty', empty: true, overdue: false }
    ],
    exact: false,
    notify: false
  };
  window.Native.onCardsChanged('计划');
});
await new Promise((resolve) => setTimeout(resolve, 80));
const plan = await page.evaluate(async () => {
  const hidden = (id) => document.getElementById(id).classList.contains('hidden');
  document.getElementById('tabCards').click();
  Array.from(document.querySelectorAll('#cardList .card-row')).find((row) => row.textContent === '计划').click();
  const heads = Array.from(document.querySelectorAll('#cardPlan .plan-head')).map((el) => el.textContent);
  const rows = Array.from(document.querySelectorAll('#cardPlan .plan-row')).map((row) => ({
    text: row.querySelector('.plan-text').textContent,
    when: row.querySelector('.plan-when').textContent,
    done: row.classList.contains('done'),
    overdue: row.classList.contains('overdue')
  }));
  const degraded = {
    shown: !hidden('cardPlan') && hidden('cardBody'),
    warning: (document.querySelector('#cardPlan .plan-warning') || {}).textContent || '',
    buttons: Array.from(document.querySelectorAll('#cardPlan .plan-btn')).map((b) => b.textContent)
  };
  document.querySelector('#cardPlan .plan-btn').click();
  const tapped = window.__asked && window.__asked.includes('notify');
  // A tap on an open to-do finishes it; a tap again opens it.
  const row = Array.from(document.querySelectorAll('#cardPlan .plan-row')).find((r) => r.querySelector('.plan-text').textContent === '交周报');
  row.click();
  const finished = window.__plan.today.find((item) => item.id === 4).done &&
    Array.from(document.querySelectorAll('#cardPlan .plan-row')).find((r) => r.querySelector('.plan-text').textContent === '交周报').classList.contains('done');
  return { heads, rows, degraded, tapped, finished };
});
await shot('14-plan-card.png');
// With both permissions, the card is only the three blocks.
const planQuiet = await page.evaluate(async () => {
  window.__plan.exact = true;
  window.__plan.notify = true;
  window.Native.onMemosChanged();
  return { warning: !!document.querySelector('#cardPlan .plan-warning'), rows: document.querySelectorAll('#cardPlan .plan-row').length };
});
console.log('plan:', JSON.stringify(plan), JSON.stringify(planQuiet));
if (!plan.degraded.shown || plan.heads.join() !== '今天,未来,没日期' || plan.rows.length !== 6 ||
    plan.rows[0].text !== '交周报' || !plan.rows[0].overdue || plan.rows[2].done !== true ||
    !plan.degraded.warning.includes('通知权限') || !plan.degraded.warning.includes('精确闹钟') ||
    plan.degraded.buttons.join() !== '打开通知权限,允许精确闹钟' || !plan.tapped || !plan.finished ||
    planQuiet.warning || planQuiet.rows !== 6 ||
    plan.rows[5].when !== 'empty') {
  throw new Error('plan card did not behave: ' + JSON.stringify(plan) + JSON.stringify(planQuiet));
}
await page.evaluate(() => window.onAndroidBack());
await page.evaluate(() => document.getElementById('tabStream').click());

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
  window.Native.onVoiceState('PREPARING');
  window.Native.onVoiceState('LISTENING');
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
  window.Native.onVoiceState('TURN_ACK');
  seen.push(['TURN_ACK', status(), barShown()]);
  const before = bubbles();
  window.Native.onVoiceState('REPLYING');
  window.Native.onReplyBegin('spoken-1', '你好');
  window.Native.onChatToken('spoken-1', '你好，');
  window.Native.onChatDone('spoken-1', '你好，我在。');
  await wait(50);
  seen.push(['REPLYING', status(), barShown(), bubbles() - before]);
  window.Native.onVoiceState('IDLE');
  seen.push(['IDLE', status(), barShown()]);
  // Only the wake phrase opens the microphone again.
  window.Native.onVoiceState('PREPARING');
  window.Native.onVoiceState('LISTENING');
  seen.push(['LISTENING', status(), barShown()]);
  window.Native.onVoiceState('IDLE');
  return seen;
});
console.log('hands-free round:', JSON.stringify(round));
const expected = [['TURN_ACK', '好的', false], ['REPLYING', '', false, 2], ['IDLE', '', false], ['LISTENING', '正在听…', true]];
if (JSON.stringify(round) !== JSON.stringify(expected)) errors.push('hands-free round rendered ' + JSON.stringify(round));

// A spoken exchange that happened with no page showing appears once the page hears of it.
const missed = await page.evaluate(async () => {
  window.Android.loadHistory = () => JSON.stringify([{ role: 'user', content: '熄屏时说的话' }, { role: 'assistant', content: '熄屏时的回答' }]);
  window.Native.onChatDone('unknown-reply', '熄屏时的回答');
  const text = document.getElementById('messages').textContent;
  return { shown: text.includes('熄屏时说的话') && text.includes('熄屏时的回答') };
});
console.log('missed reply:', JSON.stringify(missed));
if (!missed.shown) errors.push('a reply made without the page was not shown');


// A reply is drawn as it is spoken: one block of text.
const reply = await page.evaluate(async () => {
  const ui = await import('/js/ui/ui.js');
  const node = document.createElement('div');
  ui.renderAssistantContent(node, '结论是可以。\n\n证据放在**备忘**卡片里。');
  return { blocks: node.querySelectorAll('.reply-part').length, text: node.textContent.trim(), bold: !!node.querySelector('strong') };
});
console.log('reply:', JSON.stringify(reply));
if (reply.blocks !== 0 || !reply.text.startsWith('结论是可以。') || !reply.bold) errors.push('reply rendered ' + JSON.stringify(reply));

// A tool call is one line: its summary, when it finished, how it went. A tap opens the rest.
const line = await page.evaluate(async () => {
  const ui = await import('/js/ui/ui.js');
  const trace = document.createElement('div');
  trace.className = 'tool-trace';
  document.querySelector('.messages').appendChild(trace);
  const longInput = 'x'.repeat(900);
  ui.addToolTrace(trace, 'run_agent', '派 sub agent 查检索综述', JSON.stringify({ task: longInput }));
  const el = trace.querySelector('.tool-line');
  const running = el.querySelector('.tool-time').textContent;
  ui.finishToolTrace(trace, 'run_agent', 4200, true, 'y'.repeat(3000));
  ui.addToolTrace(trace, 'web_search', '', '{}');
  ui.finishToolTrace(trace, 'web_search', 300, false, '工具执行失败：超时');
  const failed = trace.querySelectorAll('.tool-line')[1];
  ui.addToolTrace(trace, 'read_card', '读备忘卡片', '{}');
  ui.settleToolTrace(trace);
  const cut = trace.querySelectorAll('.tool-line')[2];
  const body = el.querySelector('.tool-body');
  const closed = { bodyShown: getComputedStyle(body).display !== 'none', height: el.getBoundingClientRect().height };
  el.querySelector('.tool-head').click();
  const open = { bodyShown: getComputedStyle(body).display !== 'none', overflow: getComputedStyle(body).overflowY, scrollable: body.scrollHeight > body.clientHeight };
  const text = body.textContent;
  const result = {
    running, closed, open,
    summary: el.querySelector('.tool-summary').textContent,
    time: el.querySelector('.tool-time').textContent,
    done: el.classList.contains('done'),
    failedTime: failed.querySelector('.tool-time').textContent,
    failedClass: failed.classList.contains('failed'),
    cut: cut.querySelector('.tool-time').textContent + (cut.classList.contains('running') ? ' running' : ''),
    fallbackName: failed.querySelector('.tool-summary').textContent,
    fullInput: text.includes(longInput), fullOutput: text.includes('y'.repeat(3000)), duration: text.includes('4.2 秒')
  };
  trace.remove();
  return result;
});
console.log('tool line:', JSON.stringify(line));
if (line.running !== '进行中' || line.closed.bodyShown || line.closed.height > 30 || !line.open.bodyShown || line.open.overflow !== 'auto' ||
    !line.open.scrollable || line.summary !== '派 sub agent 查检索综述' || !/^\d\d:\d\d$/.test(line.time) || !line.done ||
    !/^失败 \d\d:\d\d$/.test(line.failedTime) || !line.failedClass || line.fallbackName !== 'web_search' || line.cut !== '已中断' ||
    !line.fullInput || !line.fullOutput || !line.duration) {
  errors.push('tool line ' + JSON.stringify(line));
}

if (errors.length) {
  console.error('errors:', errors);
  process.exitCode = 1;
} else {
  console.log('no js errors');
}

await browser.close();
server.close();
