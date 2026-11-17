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

/** A card's folder is what comes before the last "/" in its name; a card with none is at the top. */
function placeOf(name) {
  const at = name.lastIndexOf('/');
  return at < 0 ? { folder: '', leaf: name } : { folder: name.slice(0, at), leaf: name.slice(at + 1) };
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
  const row = (card, leaf, inside) => {
    const button = document.createElement('button');
    button.className = 'card-row' + (inside ? ' inside' : '') + (card.prompt ? ' prompt' : '');
    button.textContent = leaf;
    button.dataset.name = card.name;
    button.addEventListener('click', () => openCard(card.name));
    return button;
  };
  // Cards at the top first, then each folder with its cards under it.
  const folders = new Map();
  for (const card of state.cards) {
    const { folder, leaf } = placeOf(card.name);
    if (!folder) list.appendChild(row(card, leaf, false));
    else folders.set(folder, (folders.get(folder) || []).concat([{ card, leaf }]));
  }
  const closed = closedFolders();
  for (const folder of Array.from(folders.keys()).sort((a, b) => a.localeCompare(b, 'zh'))) {
    const inside = folders.get(folder);
    const head = document.createElement('button');
    head.className = 'card-folder' + (closed.has(folder) ? ' closed' : '');
    const label = document.createElement('span');
    label.textContent = folder;
    const count = document.createElement('span');
    count.className = 'count';
    count.textContent = String(inside.length);
    head.append(label, count);
    head.addEventListener('click', () => {
      const now = closedFolders();
      if (now.has(folder)) now.delete(folder); else now.add(folder);
      localStorage.setItem(CLOSED_KEY, JSON.stringify(Array.from(now)));
      renderList();
    });
    list.appendChild(head);
    if (!closed.has(folder)) for (const item of inside) list.appendChild(row(item.card, item.leaf, true));
  }
}

function show(next) {
  mode = next;
  const editing = mode === 'edit' || mode === 'new';
  ui.els.cardPage.classList.toggle('hidden', mode === 'closed');
  const plan = state.cardName === PLAN_CARD && mode === 'read';
  ui.els.cardPlan.classList.toggle('hidden', !plan);
  ui.els.cardBody.classList.toggle('hidden', editing || plan);
  ui.els.cardEditor.classList.toggle('hidden', !editing);
  // A card the program writes can be read, copied and floated, not edited.
  const open = state.cards.find((card) => card.name === state.cardName) || {};
  const locked = open.locked === true;
  // A prompt is a card the user has read through and locked: it stays as it is until the user unlocks it.
  const prompt = open.prompt === true;
  ui.els.cardEdit.classList.toggle('hidden', mode !== 'read' || locked || prompt);
  ui.els.cardLock.classList.toggle('hidden', mode !== 'read' || locked);
  ui.els.cardLock.textContent = prompt ? '解锁' : '审核并锁定';
  ui.els.cardTitle.textContent = mode === 'new' ? '新卡片' : (prompt ? '🔒 ' : '') + state.cardName;
  ui.els.cardFloat.classList.toggle('hidden', mode !== 'read');
  ui.els.cardFloat.textContent = callBridge('floatingCard') === state.cardName ? '取消悬浮' : '悬浮';
  ui.els.cardCopyAll.classList.toggle('hidden', mode !== 'read');
  // The name can be changed while editing: that is also how a card is moved into a folder.
  ui.els.cardNameField.classList.toggle('hidden', !editing);
  ui.els.cardCopy.classList.toggle('hidden', mode === 'new');
  ui.els.cardDelete.classList.toggle('hidden', mode === 'new');
}

function renderOpenCard() {
  ui.els.cardTitle.textContent = state.cardName;
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
    if (!/^\/card-media\/.+\.(m4a|mp3|wav|ogg|aac|webm|flac)$/.test(source)) continue;
    const player = document.createElement('audio');
    player.controls = true;
    player.preload = 'metadata';
    player.src = source;
    player.title = image.getAttribute('alt') || '';
    image.replaceWith(player);
  }
  ui.els.cardBody.scrollTop = 0;
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

/** Opens a card over the whole screen, to be read. */
export function openCard(name) {
  if (!exists(name)) return;
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
  ui.els.cardTitle.textContent = '新卡片';
  ui.els.cardNameInput.value = '';
  ui.els.cardPurposeInput.value = '';
  ui.els.cardText.value = '';
  show('new');
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

/** Puts [text] into the editor where the cursor is. */
function insertIntoText(text) {
  const box = ui.els.cardText;
  const at = box.selectionStart ?? box.value.length;
  const before = box.value.slice(0, at);
  const line = (before && !before.endsWith('\n') ? '\n' : '') + text + '\n';
  box.value = before + line + box.value.slice(box.selectionEnd ?? at);
  box.selectionStart = box.selectionEnd = at + line.length;
}

const base64Of = (buffer) => {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(binary);
};

/** A photo straight from a camera is far larger than a card needs: it is kept at 1600 pixels on its long side. */
async function shrunk(file) {
  const image = await createImageBitmap(file);
  const scale = Math.min(1, 1600 / Math.max(image.width, image.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(image.width * scale);
  canvas.height = Math.round(image.height * scale);
  canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.86));
  return { buffer: await blob.arrayBuffer(), extension: 'jpg' };
}

/** The user picked a picture or a recording: it is kept by the app and referred to from the card's text. */
export async function attachMedia(event) {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) return;
  try {
    const isImage = file.type.startsWith('image/') && file.type !== 'image/gif';
    const named = (file.name.match(/\.([A-Za-z0-9]{2,5})$/) || [])[1];
    const kept = isImage ? await shrunk(file) : { buffer: await file.arrayBuffer(), extension: (named || (file.type.startsWith('audio/') ? 'm4a' : '')).toLowerCase() };
    const id = callBridge('saveCardMedia', base64Of(kept.buffer), kept.extension);
    if (!id) {
      ui.toast('放不进去：只收图片和录音，单个不超过 20 MB');
      return;
    }
    insertIntoText('![' + file.name.replace(/\.[^.]*$/, '').replace(/[\[\]]/g, '') + '](media:' + id + ')');
    ui.toast(file.type.startsWith('audio/') ? '录音已放进卡片，保存后生效' : '图片已放进卡片，保存后生效');
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
