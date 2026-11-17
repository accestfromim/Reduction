import { base64Of, shrunk } from '../../core/pictures.js';
import { callBridge } from '../../core/bridge.js';
import { state } from '../../core/state.js';
import { renderMarkdown } from '../../ui/markdown.js';
import * as ui from '../../ui/ui.js';

// Memo cards: each is a text collecting one kind of information over time. The app keeps
// them. The list shows their names and nothing else; an open card takes the whole screen,
// first to be read, and on request to be edited.

const LEGACY_KEY = 'dsassist_brief_v1';

/** The review card for to-dos: written by the program, drawn by the page from the memo plugin. */
const PLAN_CARD = '计划';

/** What the open card page is doing: 'closed', 'read', 'edit' or 'new'. */
let mode = 'closed';

/** Whether the open card is shown as the article it is published as, rather than as its text. */
let asArticle = false;

function load() {
  try {
    const list = JSON.parse(callBridge('listCards') || '[]');
    state.cards = Array.isArray(list) ? list : [];
  } catch (_) {
    state.cards = [];
  }
}

function exists(name) {
  return state.cards.some((card) => card.name === name);
}

const CLOSED_KEY = 'reduction_closed_folders';

function closedFolders() {
  try { return new Set(JSON.parse(localStorage.getItem(CLOSED_KEY) || '[]')); } catch (_) { return new Set(); }
}

/** A card's name is a path: every "/" in it is a folder, to any depth; a card with none is at the top. */
function placeOf(name) {
  const at = name.lastIndexOf('/');
  return at < 0 ? { folder: '', leaf: name } : { folder: name.slice(0, at), leaf: name.slice(at + 1) };
}

/** The cards as a tree of folders: each folder holds its own cards and the folders inside it. */
function folderTree(cards) {
  const root = { path: '', name: '', cards: [], folders: new Map(), count: 0 };
  for (const card of cards) {
    const parts = card.name.split('/');
    const leaf = parts.pop();
    let at = root;
    at.count += 1;
    for (const part of parts) {
      if (!at.folders.has(part)) {
        at.folders.set(part, { path: at.path ? at.path + '/' + part : part, name: part, cards: [], folders: new Map(), count: 0 });
      }
      at = at.folders.get(part);
      at.count += 1;
    }
    at.cards.push({ card, leaf });
  }
  return root;
}

/**
 * Where the open card is, written beside the tabs: the list, its folders, and then its own name.
 * Each step before the name is a way back to the list — the first to the whole of it, a folder to
 * the list with that folder open. The tabs only change the page: they leave the card as it is.
 * [instead] stands in for a name that is not there yet, as for a card being made.
 */
function setTitle(name, instead = '', locked = false) {
  const crumbs = document.getElementById('cardCrumbs');
  crumbs.innerHTML = '';
  crumbs.title = name || '';
  const mark = () => {
    const sign = document.createElement('span');
    sign.className = 'crumb-mark';
    sign.textContent = '›';
    return sign;
  };
  const parts = instead ? [] : (name || '').split('/');
  const leaf = instead || (locked ? '🔒 ' : '') + (parts.pop() || '');
  // The first step is the list itself: the tabs do not close a card, this does.
  const all = document.createElement('button');
  all.className = 'crumb';
  all.textContent = '全部';
  all.addEventListener('click', () => backToFolder(''));
  crumbs.append(mark(), all);
  let path = '';
  for (const part of parts) {
    path = path ? path + '/' + part : part;
    const here = path;
    const step = document.createElement('button');
    step.className = 'crumb';
    step.textContent = part;
    step.addEventListener('click', () => backToFolder(here));
    crumbs.append(mark(), step);
  }
  const last = document.createElement('span');
  last.className = 'crumb here';
  last.textContent = leaf;
  crumbs.append(mark(), last);
  // The end of a path is what tells one card from another: when it does not all fit, that end is what shows.
  crumbs.scrollLeft = crumbs.scrollWidth;
}

/** Back to the list, with [folder] and the folders it lies in open and in view; a blank [folder] is the list as it stands. */
function backToFolder(folder) {
  if (mode === 'edit' || mode === 'new') return;
  const closed = closedFolders();
  let path = '';
  for (const part of folder.split('/').filter(Boolean)) {
    path = path ? path + '/' + part : part;
    closed.delete(path);
  }
  localStorage.setItem(CLOSED_KEY, JSON.stringify(Array.from(closed)));
  show('closed');
  renderList();
  const head = Array.from(document.querySelectorAll('#cardList .card-folder')).find((el) => el.dataset.folder === folder);
  if (head) head.scrollIntoView({ block: 'start' });
}

function renderList() {
  const list = ui.els.cardList;
  list.innerHTML = '';
  if (!state.cards.length) {
    const empty = document.createElement('div');
    empty.className = 'card-empty';
    empty.textContent = '还没有卡片。新建几张，比如“科研想法/某方向”“备忘”，之后口述就会记进去。';
    list.appendChild(empty);
    return;
  }
  const INDENT = 18;
  const row = (card, leaf, depth) => {
    const button = document.createElement('button');
    button.className = 'card-row' + (depth ? ' inside' : '') + (card.prompt ? ' prompt' : '');
    button.textContent = leaf;
    button.dataset.name = card.name;
    if (depth > 1) button.style.paddingLeft = depth * INDENT + 'px';
    button.addEventListener('click', () => openCard(card.name));
    return button;
  };
  const closed = closedFolders();
  // In each folder its own cards first, then the folders inside it, each with what it holds under it.
  const draw = (folder, depth) => {
    for (const item of folder.cards) list.appendChild(row(item.card, item.leaf, depth));
    const names = Array.from(folder.folders.keys()).sort((a, b) => a.localeCompare(b, 'zh'));
    for (const name of names) {
      const inner = folder.folders.get(name);
      const shut = closed.has(inner.path);
      const head = document.createElement('button');
      head.className = 'card-folder' + (shut ? ' closed' : '') + (depth ? ' inside' : '');
      head.dataset.folder = inner.path;
      if (depth) head.style.paddingLeft = depth * INDENT + 'px';
      const label = document.createElement('span');
      label.textContent = inner.name;
      const count = document.createElement('span');
      count.className = 'count';
      count.textContent = String(inner.count);
      head.append(label, count);
      head.addEventListener('click', () => {
        const now = closedFolders();
        if (now.has(inner.path)) now.delete(inner.path); else now.add(inner.path);
        localStorage.setItem(CLOSED_KEY, JSON.stringify(Array.from(now)));
        renderList();
      });
      list.appendChild(head);
      if (!shut) draw(inner, depth + 1);
    }
  };
  draw(folderTree(state.cards), 0);
}

function show(next) {
  mode = next;
  const editing = mode === 'edit' || mode === 'new';
  // An open card takes the place of the list under the tabs; the tabs, and the way back to speech, stay.
  const open_ = mode !== 'closed';
  ui.els.cardPage.classList.toggle('hidden', !open_ || state.page !== 'cards');
  ui.els.cardsPage.classList.toggle('hidden', open_ || state.page !== 'cards');
  document.getElementById('cardCrumbs').classList.toggle('hidden', !open_ || state.page !== 'cards');
  if (open_) document.getElementById('cardCrumbs').scrollLeft = 1e6;
  const plan = state.cardName === PLAN_CARD && mode === 'read';
  ui.els.cardPlan.classList.toggle('hidden', !plan);
  if (mode !== 'read' || plan) asArticle = false;
  ui.els.cardBody.classList.toggle('hidden', editing || plan || asArticle);
  document.getElementById('cardArticle').classList.toggle('hidden', !asArticle);
  const preview = document.getElementById('cardPreview');
  preview.classList.toggle('hidden', mode !== 'read' || plan);
  preview.textContent = asArticle ? '原文' : '预览';
  ui.els.cardEditor.classList.toggle('hidden', !editing);
  // A card the program writes can be read, copied and floated, not edited.
  const open = state.cards.find((card) => card.name === state.cardName) || {};
  const locked = open.locked === true;
  // A prompt is a card the user has read through and locked: it stays as it is until the user unlocks it.
  const prompt = open.prompt === true;
  ui.els.cardEdit.classList.toggle('hidden', mode !== 'read' || locked || prompt);
  ui.els.cardLock.classList.toggle('hidden', mode !== 'read' || locked);
  ui.els.cardLock.textContent = prompt ? '解锁' : '审核并锁定';
  setTitle(mode === 'new' ? '' : state.cardName, mode === 'new' ? '新卡片' : '', prompt);
  ui.els.cardFloat.classList.toggle('hidden', mode !== 'read');
  ui.els.cardFloat.textContent = callBridge('floatingCard') === state.cardName ? '取消悬浮' : '悬浮';
  ui.els.cardCopyAll.classList.toggle('hidden', mode !== 'read');
  // The name can be changed while editing: that is also how a card is moved into a folder.
  ui.els.cardNameField.classList.toggle('hidden', !editing);
  ui.els.cardCopy.classList.toggle('hidden', mode === 'new');
  ui.els.cardDelete.classList.toggle('hidden', mode === 'new');
}

function renderOpenCard() {
  setTitle(state.cardName);
  if (state.cardName === PLAN_CARD) {
    renderPlan();
    return;
  }
  ui.els.cardPlan.classList.add('hidden');
  ui.els.cardBody.classList.remove('hidden');
  const text = String(callBridge('readCard', state.cardName) || '').trim();
  // A picture or a recording is written as ![说明](media:编号); it is fetched from the app by that id.
  ui.els.cardBody.innerHTML = text ? renderMarkdown(text.replace(/\]\(media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})\)/g, '](/card-media/$1)')) : '';
  for (const image of ui.els.cardBody.querySelectorAll('img')) {
    const source = image.getAttribute('src') || '';
    const pdf = (source.match(/^\/card-media\/(m[0-9a-f]{12}\.pdf)$/) || [])[1];
    if (pdf) {
      image.replaceWith(pdfPages(pdf, image.getAttribute('alt') || 'PDF'));
      continue;
    }
    if (/^\/card-media\/.+\.(mp4|mov)$/.test(source)) {
      const film = document.createElement('video');
      film.controls = true;
      film.preload = 'metadata';
      film.playsInline = true;
      film.src = source;
      image.replaceWith(film);
      continue;
    }
    if (!/^\/card-media\/.+\.(m4a|mp3|wav|ogg|aac|webm|flac)$/.test(source)) continue;
    const player = document.createElement('audio');
    player.controls = true;
    player.preload = 'metadata';
    player.src = source;
    player.title = image.getAttribute('alt') || '';
    image.replaceWith(player);
  }
  ui.els.cardBody.scrollTop = 0;
  if (asArticle) drawArticle();
}

/** The other view of the open card: the article it is published as. The card's text is read and nothing more. */
export function togglePreview() {
  if (mode !== 'read') return;
  asArticle = !asArticle;
  show('read');
  if (asArticle) drawArticle();
}

/** Sets the open card in the article template and fits the page to the width there is. */
async function drawArticle() {
  const name = state.cardName;
  const box = document.getElementById('cardArticle');
  const frame = document.getElementById('cardArticleFrame');
  try {
    const article = await import('../article/article.js');
    const { height, problem } = await article.show(frame, String(callBridge('readCard', name) || ''));
    if (name !== state.cardName || !asArticle) return;
    if (problem) ui.toast(problem);
    // The page is laid out at its own width, the one it is published at, and scaled as a whole: what is
    // seen is the published picture, smaller or larger, never a different layout.
    const fit = () => {
      const scale = Math.min(1, (box.clientWidth - 24) / article.WIDTH);
      frame.style.width = article.WIDTH + 'px';
      frame.style.height = height + 'px';
      frame.style.transform = 'scale(' + scale + ')';
      frame.style.marginBottom = -(height * (1 - scale)) + 'px';
      frame.style.marginRight = -(article.WIDTH * (1 - scale)) + 'px';
    };
    fit();
    box.scrollTop = 0;
    window.onresize = () => { if (asArticle) fit(); };
  } catch (error) {
    ui.toast('预览不了：' + (error && error.message ? error.message : '渲染失败'));
  }
}

/**
 * The review card: today, later, no date. Each to-do is a row with its time and state; a
 * tap finishes it, another opens it again. When the reminders cannot work as they should,
 * the card says so and offers the two permissions instead of promising anything.
 */
function renderPlan() {
  const plan = ui.els.cardPlan;
  ui.els.cardBody.classList.add('hidden');
  plan.classList.remove('hidden');
  let data;
  try {
    data = JSON.parse(callBridge('memoView') || '{}');
  } catch (_) {
    data = {};
  }
  plan.innerHTML = '';

  if (data.exact !== true || data.notify !== true) {
    const warning = document.createElement('div');
    warning.className = 'plan-warning';
    const lines = [];
    if (data.notify !== true) lines.push('通知权限没有打开：早上不会弹出当天的提醒。');
    if (data.exact !== true) lines.push('没有精确闹钟权限：早上的提醒可能晚一些，也可能在省电时被推迟。');
    lines.push('这两种情况下，App 被系统杀掉后不能保证还会提醒；计划卡片本身始终准确。');
    warning.textContent = lines.join('');
    const actions = document.createElement('div');
    actions.className = 'plan-actions';
    if (data.notify !== true) actions.appendChild(planButton('打开通知权限', () => callBridge('askForNotifications')));
    if (data.exact !== true) actions.appendChild(planButton('允许精确闹钟', () => callBridge('askForExactAlarms')));
    warning.appendChild(actions);
    plan.appendChild(warning);
  }

  const blocks = [
    ['今天', data.today],
    ['未来', data.future],
    ['没日期', data.undated],
  ];
  for (const [title, items] of blocks) {
    const head = document.createElement('div');
    head.className = 'plan-head';
    head.textContent = title;
    plan.appendChild(head);
    if (!Array.isArray(items) || !items.length) {
      const empty = document.createElement('div');
      empty.className = 'plan-empty';
      empty.textContent = '（没有）';
      plan.appendChild(empty);
      continue;
    }
    for (const item of items) plan.appendChild(planRow(item));
  }
}

function planButton(label, onTap) {
  const button = document.createElement('button');
  button.className = 'secondary-btn plan-btn';
  button.textContent = label;
  button.addEventListener('click', () => {
    onTap();
    ui.toast('设置好后回到这里，状态会自动更新');
  });
  return button;
}

function planRow(item) {
  const row = document.createElement('button');
  row.className = 'plan-row' + (item.done ? ' done' : '') + (item.overdue ? ' overdue' : '');
  const box = document.createElement('span');
  box.className = 'plan-box';
  box.textContent = item.done ? '✓' : '';
  const text = document.createElement('span');
  text.className = 'plan-text';
  text.textContent = item.text;
  const when = document.createElement('span');
  when.className = 'plan-when';
  when.textContent = item.overdue ? (item.due ? item.due + ' · 已过期' : '已过期') : (item.due || 'empty');
  row.append(box, text, when);
  row.addEventListener('click', () => {
    if (callBridge('setMemoDone', item.id, !item.done) !== true) {
      ui.toast('改不了这一条');
      return;
    }
    renderPlan();
  });
  return row;
}

/** The to-dos changed in the app: the plan card follows if it is open. */
export function onMemosChanged() {
  if (mode === 'read' && state.cardName === PLAN_CARD) renderPlan();
}

/** An instruction saved by an earlier version becomes the first card. */
function adoptLegacy() {
  try {
    const old = (localStorage.getItem(LEGACY_KEY) || '').trim();
    if (!old) return;
    if (callBridge('saveCard', '指令', '当前的任务与指令', old) === true) localStorage.removeItem(LEGACY_KEY);
  } catch (_) {}
}

export function loadCards() {
  adoptLegacy();
  load();
  renderList();
}

/** The app changed a card; `name` is the one it touched last, if any. */
export function onChanged(name) {
  load();
  renderList();
  if (mode === 'read') {
    if (exists(state.cardName)) renderOpenCard();
    else show('closed');
  }
  if (mode !== 'closed') show(mode);
  if (name) ui.toast('卡片“' + name + '”已更新');
}

/** Opens a card, to be read: as its text. The article view is asked for each time. */
export function openCard(name) {
  if (!exists(name)) return;
  asArticle = false;
  state.cardName = name;
  renderOpenCard();
  show('read');
}

export function editCard() {
  if (mode !== 'read') return;
  ui.els.cardNameInput.value = state.cardName;
  ui.els.cardPurposeInput.value = String(callBridge('cardPurpose', state.cardName) || '');
  ui.els.cardText.value = String(callBridge('readCard', state.cardName) || '');
  show('edit');
}

export function newCard() {
  state.cardName = '';
  setTitle('', '新卡片');
  ui.els.cardNameInput.value = '';
  ui.els.cardPurposeInput.value = '';
  ui.els.cardText.value = '';
  show('new');
}

/** Whether a card is open: read, being edited, or being made. */
export function isOpen() {
  return mode !== 'closed';
}

/** The page under the tabs changed: the open card shows only on the cards page, and waits there meanwhile. */
export function onPage() {
  show(mode);
}

/** One step back: from editing to reading, from reading to the list. False when no card is open. */
export function back() {
  if (mode === 'closed') return false;
  if (mode === 'edit') {
    renderOpenCard();
    show('read');
  } else {
    show('closed');
  }
  return true;
}

export function saveCard() {
  const creating = mode === 'new';
  const name = ui.els.cardNameInput.value.trim().replace(/\s*\/\s*/g, '/');
  const renamed = !creating && name !== state.cardName;
  const purpose = ui.els.cardPurposeInput.value.trim();
  const text = ui.els.cardText.value.trim();
  if (!name) {
    ui.toast('先给卡片起个名字');
    return;
  }
  if ((creating || renamed) && exists(name)) {
    ui.toast('已经有一张叫“' + name + '”的卡片');
    return;
  }
  if (creating && !purpose) {
    ui.toast('写一句用途，助手靠它判断什么该记到这里');
    return;
  }
  if (renamed && callBridge('renameCard', state.cardName, name) !== true) {
    ui.toast('改不了名：名称最长 60 字，“/”两边不能是空的');
    return;
  }
  if (callBridge('saveCard', name, purpose, text) !== true) {
    ui.toast('保存失败：名称最长 60 字，“/”两边不能是空的');
    return;
  }
  load();
  renderList();
  state.cardName = name;
  renderOpenCard();
  show('read');
  ui.toast('卡片已保存');
}

/** The user has read the card through: one tap locks it as a prompt. Another tap takes the lock off. */
export function toggleLock() {
  const open = state.cards.find((card) => card.name === state.cardName) || {};
  const lock = open.prompt !== true;
  if (callBridge('lockCard', state.cardName, lock) !== true) {
    ui.toast('锁不了这张卡片');
    return;
  }
  load();
  renderList();
  show(mode);
  ui.toast(lock ? '已锁定为 prompt：之后谁也改不了它，直到你解锁' : '已解锁，可以再改');
}

/** Floats the open card over other apps, or puts it away if it is the one floating. */
export function toggleFloat() {
  if (callBridge('floatingCard') === state.cardName) {
    callBridge('floatCard', '');
    ui.toast('已取消悬浮');
  } else if (callBridge('floatCard', state.cardName) === true) {
    ui.toast('离开应用后，这张卡片会浮在屏幕上');
  } else {
    ui.toast('请在打开的系统页面里允许“显示在其他应用上层”，再回来点一次');
  }
  show(mode);
}

/**
 * A PDF in a card, as it looks: its pages one under another, each a picture. A PDF that has no
 * pictures yet — one that came from another device, say — is drawn now and kept.
 */
function pdfPages(id, label) {
  const box = document.createElement('div');
  box.className = 'pdf-pages';
  box.dataset.pdf = id;
  const head = document.createElement('div');
  head.className = 'pdf-head';
  box.appendChild(head);
  const fill = (count) => {
    head.textContent = label + ' · ' + count + ' 页';
    for (let page = 1; page <= count; page++) {
      const image = document.createElement('img');
      image.loading = 'lazy';
      image.alt = label + ' 第 ' + page + ' 页';
      image.src = '/card-media/' + id + '.p' + page + '.jpg';
      box.appendChild(image);
    }
  };
  const kept = Number(callBridge('cardPages', id) || 0);
  if (kept > 0) {
    fill(kept);
    return box;
  }
  head.textContent = label + ' · 正在渲染…';
  fetch('/card-media/' + id)
    .then((response) => { if (!response.ok) throw new Error('文件不在这台设备上'); return response.arrayBuffer(); })
    .then((data) => keepPages(id, data, (page, count) => { head.textContent = label + ' · 正在渲染 ' + page + '/' + count; }))
    .then((count) => fill(count))
    .catch((error) => { head.textContent = label + ' · 渲染不了：' + (error && error.message ? error.message : '读不了这个文件'); });
  return box;
}

/** Draws the pages of the PDF kept as [id] and has the app keep each as a picture. Returns how many. */
async function keepPages(id, data, progress) {
  const { drawPages } = await import('../../core/pdf.js');
  return drawPages(data, (page, jpeg, count) => {
    if (callBridge('saveCardPage', id, page, jpeg) !== true) throw new Error('第 ' + page + ' 页存不下');
    if (progress) progress(page, count);
  });
}

/** Puts [text] into the editor where the cursor is. */
function insertIntoText(text) {
  const box = ui.els.cardText;
  const at = box.selectionStart ?? box.value.length;
  const before = box.value.slice(0, at);
  const line = (before && !before.endsWith('\n') ? '\n' : '') + text + '\n';
  box.value = before + line + box.value.slice(box.selectionEnd ?? at);
  box.selectionStart = box.selectionEnd = at + line.length;
}

/**
 * The user picked something to attach — a picture, a recording, a video, a PDF: the app keeps it
 * and the card's text refers to it by the id it was given. A PDF is also turned into pictures of
 * its pages there and then, which is how it is shown and how the assistant sees it.
 */
export async function attachMedia(event) {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) return;
  try {
    const named = ((file.name.match(/\.([A-Za-z0-9]{2,5})$/) || [])[1] || '').toLowerCase();
    const isPdf = file.type === 'application/pdf' || named === 'pdf';
    const isImage = file.type.startsWith('image/') && file.type !== 'image/gif';
    const fallback = file.type.startsWith('audio/') ? 'm4a' : file.type.startsWith('video/') ? 'mp4' : file.type === 'image/gif' ? 'gif' : '';
    const kept = isImage ? await shrunk(file) : { buffer: await file.arrayBuffer(), extension: isPdf ? 'pdf' : (named || fallback) };
    const id = callBridge('saveCardMedia', base64Of(kept.buffer), kept.extension);
    if (!id) {
      ui.toast('放不进去：收图片、录音、视频和 PDF，单个不超过 20 MB');
      return;
    }
    const label = file.name.replace(/\.[^.]*$/, '').replace(/[\[\]]/g, '');
    if (isPdf) {
      ui.toast('正在把 PDF 渲染成页面…');
      // The pages are drawn from a copy: the first was handed to the app above.
      const count = await keepPages(id, kept.buffer.slice(0), (page, total) => ui.setStatus('PDF 第 ' + page + '/' + total + ' 页'));
      ui.setStatus('');
      insertIntoText('![' + label + '](media:' + id + ')');
      ui.toast('PDF 已放进卡片，共 ' + count + ' 页，保存后生效');
      return;
    }
    insertIntoText('![' + label + '](media:' + id + ')');
    ui.toast((file.type.startsWith('audio/') ? '录音' : file.type.startsWith('video/') ? '视频' : '图片') + '已放进卡片，保存后生效');
  } catch (error) {
    ui.toast('放不进去：' + (error && error.message ? error.message : '读不了这个文件'));
  }
}

export function copyCard() {
  const text = String(callBridge('readCard', state.cardName) || '').trim();
  if (!text) {
    ui.toast('卡片是空的');
    return;
  }
  callBridge('copyText', text);
  ui.toast('卡片已复制');
}

export function deleteCard() {
  if (!state.cardName) return;
  const name = state.cardName;
  callBridge('deleteCard', name);
  load();
  renderList();
  show('closed');
  ui.toast('已删除卡片“' + name + '”');
}
