import { callBridge } from '../../core/bridge.js';
import { state } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

// Memo cards: each is a text collecting one kind of information over time. The app keeps them;
// this draws the directory and lets the user create, read, edit, copy and delete one.

const LEGACY_KEY = 'dsassist_brief_v1';

/** True while the sheet is creating a card rather than editing one. */
let creating = false;

function load() {
  try {
    const list = JSON.parse(callBridge('listCards') || '[]');
    state.cards = Array.isArray(list) ? list : [];
  } catch (_) {
    state.cards = [];
  }
  if (!state.cards.some((card) => card.name === state.cardName)) {
    state.cardName = state.cards.length ? state.cards[0].name : '';
  }
}

function current() {
  return state.cards.find((card) => card.name === state.cardName) || null;
}

function render() {
  ui.els.cardChips.innerHTML = '';
  for (const card of state.cards) {
    const chip = document.createElement('button');
    chip.className = 'card-chip' + (card.name === state.cardName ? ' active' : '');
    chip.textContent = card.name;
    chip.addEventListener('click', () => {
      state.cardName = card.name;
      render();
    });
    ui.els.cardChips.appendChild(chip);
  }
  const add = document.createElement('button');
  add.className = 'card-chip add';
  add.textContent = '＋ 新卡片';
  add.addEventListener('click', newCard);
  ui.els.cardChips.appendChild(add);

  const card = current();
  ui.els.cardCopy.classList.toggle('hidden', !card);
  ui.els.cardOpen.classList.toggle('hidden', !card);
  if (!card) {
    ui.els.cardPurpose.textContent = '还没有卡片。新建几张，比如“科研想法·某方向”“备忘”，之后口述就会记进去。';
    ui.els.cardPreview.textContent = '';
    return;
  }
  ui.els.cardPurpose.textContent = (card.purpose || '（没有写用途）') + ' · ' + card.chars + ' 字';
  const text = String(callBridge('readCard', card.name) || '').trim();
  ui.els.cardPreview.textContent = text.length > 260 ? text.slice(0, 260) + '…' : text;
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
  render();
}

/** The app changed a card; `name` is the one to show, if any. */
export function onChanged(name) {
  load();
  if (name && state.cards.some((card) => card.name === name)) state.cardName = name;
  render();
  if (name) ui.toast('卡片“' + name + '”已更新');
}

function openSheet(isNew) {
  creating = isNew;
  const card = isNew ? null : current();
  ui.els.cardSheetTitle.textContent = isNew ? '新卡片' : card.name;
  ui.els.cardNameField.classList.toggle('hidden', !isNew);
  ui.els.cardNameInput.value = '';
  ui.els.cardPurposeInput.value = isNew ? '' : String(callBridge('cardPurpose', card.name) || '');
  ui.els.cardText.value = isNew ? '' : String(callBridge('readCard', card.name) || '');
  ui.els.cardDelete.classList.toggle('hidden', isNew);
  ui.els.cardCopySheet.classList.toggle('hidden', isNew);
  ui.openSheet(ui.els.cardSheet, ui.els.cardBackdrop);
}

export function openCard() {
  if (current()) openSheet(false);
}

export function newCard() {
  openSheet(true);
}

export function saveCard() {
  const name = creating ? ui.els.cardNameInput.value.trim() : state.cardName;
  const purpose = ui.els.cardPurposeInput.value.trim();
  const text = ui.els.cardText.value.trim();
  if (!name) {
    ui.toast('先给卡片起个名字');
    return;
  }
  if (creating && state.cards.some((card) => card.name === name)) {
    ui.toast('已经有一张叫“' + name + '”的卡片');
    return;
  }
  if (creating && !purpose) {
    ui.toast('写一句用途，助手靠它判断什么该记到这里');
    return;
  }
  const ok = callBridge('saveCard', name, purpose, text) === true;
  if (ok) state.cardName = name;
  load();
  render();
  ui.closeSheets();
  ui.toast(ok ? '卡片已保存' : '保存失败：名称最长 40 字');
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
  render();
  ui.closeSheets();
  ui.toast('已删除卡片“' + name + '”');
}
