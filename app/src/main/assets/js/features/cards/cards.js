import { callBridge } from '../../core/bridge.js';
import { state } from '../../core/state.js';
import { renderMarkdown } from '../../ui/markdown.js';
import * as ui from '../../ui/ui.js';

// Memo cards: each is a text collecting one kind of information over time. The app keeps
// them. The list shows their names and nothing else; an open card takes the whole screen,
// first to be read, and on request to be edited.

const LEGACY_KEY = 'dsassist_brief_v1';

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

function renderList() {
  const list = ui.els.cardList;
  list.innerHTML = '';
  if (!state.cards.length) {
    const empty = document.createElement('div');
    empty.className = 'card-empty';
    empty.textContent = '还没有卡片。新建几张，比如“科研想法·某方向”“备忘”，之后口述就会记进去。';
    list.appendChild(empty);
    return;
  }
  for (const card of state.cards) {
    const row = document.createElement('button');
    row.className = 'card-row';
    row.textContent = card.name;
    row.addEventListener('click', () => openCard(card.name));
    list.appendChild(row);
  }
}

function show(next) {
  mode = next;
  const editing = mode === 'edit' || mode === 'new';
  ui.els.cardPage.classList.toggle('hidden', mode === 'closed');
  ui.els.cardBody.classList.toggle('hidden', editing);
  ui.els.cardEditor.classList.toggle('hidden', !editing);
  ui.els.cardEdit.classList.toggle('hidden', mode !== 'read');
  ui.els.cardCopyAll.classList.toggle('hidden', mode !== 'read');
  ui.els.cardNameField.classList.toggle('hidden', mode !== 'new');
  ui.els.cardCopy.classList.toggle('hidden', mode === 'new');
  ui.els.cardDelete.classList.toggle('hidden', mode === 'new');
}

function renderOpenCard() {
  const text = String(callBridge('readCard', state.cardName) || '').trim();
  ui.els.cardTitle.textContent = state.cardName;
  ui.els.cardBody.innerHTML = text ? renderMarkdown(text) : '';
  ui.els.cardBody.scrollTop = 0;
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
  const name = creating ? ui.els.cardNameInput.value.trim() : state.cardName;
  const purpose = ui.els.cardPurposeInput.value.trim();
  const text = ui.els.cardText.value.trim();
  if (!name) {
    ui.toast('先给卡片起个名字');
    return;
  }
  if (creating && exists(name)) {
    ui.toast('已经有一张叫“' + name + '”的卡片');
    return;
  }
  if (creating && !purpose) {
    ui.toast('写一句用途，助手靠它判断什么该记到这里');
    return;
  }
  if (callBridge('saveCard', name, purpose, text) !== true) {
    ui.toast('保存失败：名称最长 40 字');
    return;
  }
  load();
  renderList();
  state.cardName = name;
  renderOpenCard();
  show('read');
  ui.toast('卡片已保存');
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
