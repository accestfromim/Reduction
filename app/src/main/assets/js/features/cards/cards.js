import { planMerge } from './merge.js';
import { chooseCard } from './folder-menu.js';
import { mountHtml } from '../../components/html/index.js';
import { htmlFields } from './html-fields.js';
import { base64Of, shrunk } from '../../core/pictures.js';
import { callBridge } from '../../core/bridge.js';
import { state } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

// Memo cards: each is a text collecting one kind of information over time. The app keeps
// them. The list shows their names and nothing else; an open card takes the whole screen,
// first to be read, and on request to be edited.

const LEGACY_KEY = 'dsassist_brief_v1';

/** The review card for to-dos: written by the program, drawn by the page from the memo plugin. */
const PLAN_CARD = '计划';

/** What the open card page is doing: 'closed', 'read', 'edit' or 'new'. */
let mode = 'closed';
const OPEN_CARD_KEY = 'reduction.openCard';
const PAGE_KEY = 'reduction.lastPage';

export function restoreView() {
  const name = localStorage.getItem(OPEN_CARD_KEY);
  if (name && exists(name)) openCard(name);
  else localStorage.removeItem(OPEN_CARD_KEY);
  if (localStorage.getItem(PAGE_KEY) === 'cards') ui.showPage('cards');
}


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
function folderTree(cards, mounts) {
  const root = { path: '', name: '', cards: [], folders: new Map(), count: 0 };
  for (const card of [...cards, ...mounts.map(folder => ({name: folder + '/', mount: true}))]) {
    const parts = card.name.split('/');
    const leaf = parts.pop();
    let at = root;
    if (!card.mount) at.count += 1;
    for (const part of parts) {
      if (!at.folders.has(part)) {
        at.folders.set(part, { path: at.path ? at.path + '/' + part : part, name: part, cards: [], folders: new Map(), count: 0 });
      }
      at = at.folders.get(part);
      if (!card.mount) at.count += 1;
    }
    if (card.mount) at.mount = true; else at.cards.push({ card, leaf });
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
  const mark = (folder) => {
    const sign = document.createElement('button');
    sign.setAttribute('aria-label', '选择' + (folder || '全部') + '下的卡片');
    sign.addEventListener('click', () => browse(sign, folder));
    sign.className = 'crumb crumb-mark';
    sign.textContent = '›';
    return sign;
  };
  function browse(anchor, folder) {
    if (listDirty) load();
    chooseCard(anchor, folder, state.cards.map(card => card.name), name => {
      if (name === state.cardName) return;
      if (editing()) {
        autoSave();
        if (mode === 'new' && localStorage.getItem(draftKey())) return;
      }
      openCard(name);
    });
  }
  const parts = instead ? [] : (name || '').split('/');
  const leaf = instead || (locked ? '🔒 ' : '') + (parts.pop() || '');
  // The first step is the list itself: the tabs do not close a card, this does.
  const all = document.createElement('button');
  all.className = 'crumb';
  all.textContent = '全部';
  all.addEventListener('click', () => browse(all, ''));
  crumbs.append(mark(''), all);
  let path = '';
  for (const part of parts) {
    path = path ? path + '/' + part : part;
    const here = path;
    const step = document.createElement('button');
    step.className = 'crumb';
    step.textContent = part;
    step.addEventListener('click', () => browse(step, here));
    crumbs.append(mark(here), step);
  }
  const card = state.cards.find(item => item.name === name);
  const canRename = card && !card.locked && !card.prompt && name !== PLAN_CARD && !instead;
  const last = document.createElement(canRename ? 'button' : 'span');
  if (canRename) {
    last.title = '编辑卡片路径';
    last.addEventListener('click', () => {
      const input = document.createElement('input');
      input.className = 'crumb-rename';
      input.setAttribute('aria-label', '卡片路径');
      input.maxLength = ui.els.cardNameInput.maxLength;
      input.value = name;
      last.replaceWith(input);
      input.focus();
      input.setSelectionRange(name.lastIndexOf('/') + 1, name.length);
      input.addEventListener('keydown', event => {
        if (event.isComposing) return;
        if (event.key === 'Escape') input.value = name;
        if (event.key === 'Enter' || event.key === 'Escape') input.blur();
      });
      input.addEventListener('blur', () => {
        const value = input.value.trim();
        input.replaceWith(last);
        if (!value || value === name || state.cardName !== name) return;
        editCard();
        if (mode !== 'edit') return;
        ui.els.cardNameInput.value = value;
        recordInput();
        autoSave();
      }, {once:true});
    });
  }
  last.className = 'crumb here';
  last.textContent = leaf;
  crumbs.append(mark(path), last);
  // The end of a path is what tells one card from another: when it does not all fit, that end is what shows.
  crumbs.scrollLeft = crumbs.scrollWidth;
}

/** Back to the list, with [folder] and the folders it lies in open and in view; a blank [folder] is the list as it stands. */
function backToFolder(folder) {
  if (mode === 'new' || (mode === 'edit' && localStorage.getItem(draftKey()))) return;
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

function sourceDirectory(folder, relative, label, depth) {
  const box = document.createElement('details');
  box.style.paddingInlineStart = depth * 18 + 'px';
  const head = document.createElement('summary');
  head.textContent = label;
  box.append(head);
  box.addEventListener('toggle', () => {
    if (!box.open) return;
    const raw = callBridge('listCardSource', folder, relative);
    box.replaceChildren(head);
    let entries;
    try { entries = JSON.parse(raw); } catch (_) { entries = null; }
    if (!Array.isArray(entries)) { box.append('目录不可读'); return; }
    if (!entries.length) box.append('空目录');
    for (const entry of entries) {
      const path = relative ? relative + '/' + entry.name : entry.name;
      if (entry.directory) { box.append(sourceDirectory(folder, path, entry.name, 1)); continue; }
      const file = document.createElement('button');
      file.className = 'card-row inside';
      file.textContent = entry.name;
      file.onclick = () => callBridge('showCardSource', folder, path);
      box.append(file);
    }
  });
  return box;
}

let listDirty = false;
function refreshListIfNeeded() {
  if (!listDirty) return;
  load();
  renderList();
}

function renderList() {
  listDirty = false;
  const list = ui.els.cardList;
  list.innerHTML = '';
  const shortcuts = JSON.parse(callBridge("cardSourceShortcuts") || "[]");
  const mounts = shortcuts.map(entry => entry.folder);
  for (const {folder, label} of shortcuts) {
    list.append(sourceDirectory(folder, '', label, 0));
  }
  if (!state.cards.length && !mounts.length) {
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
    if (folder.mount) list.append(sourceDirectory(folder.path, '', '原件', depth));
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
        chooseCard(head, inner.path, state.cards.map(card => card.name), openForReview);
      });
      list.appendChild(head);
      if (!shut) draw(inner, depth + 1);
    }
  };
  draw(folderTree(state.cards, mounts), 0);
}

function show(next) {
  mode = next;
  if (next === 'closed' && state.page === 'cards') refreshListIfNeeded();
  if (next === 'closed' || next === 'new') localStorage.removeItem(OPEN_CARD_KEY);
  else if (state.cardName) localStorage.setItem(OPEN_CARD_KEY, state.cardName);
  const editing = mode === 'edit' || mode === 'new';
  // An open card takes the place of the list under the tabs; the tabs, and the way back to speech, stay.
  const open_ = mode !== 'closed';
  ui.els.cardPage.classList.toggle('hidden', !open_ || state.page !== 'cards');
  ui.els.cardsPage.classList.toggle('hidden', open_ || state.page !== 'cards');
  document.getElementById('cardCrumbs').classList.toggle('hidden', !open_ || state.page !== 'cards');
  if (open_) document.getElementById('cardCrumbs').scrollLeft = 1e6;
  const plan = state.cardName === PLAN_CARD && mode === 'read';
  ui.els.cardPlan.classList.toggle('hidden', !plan);
  ui.els.cardBody.classList.toggle('hidden', editing || plan);
  ui.els.cardEditor.classList.toggle('hidden', !editing);
  // A card the program writes can be read, copied and floated, not edited.
  const open = state.cards.find((card) => card.name === state.cardName) || {};
  const locked = open.locked === true;
  // A prompt is a card the user has read through and locked: it stays as it is until the user unlocks it.
  const prompt = open.prompt === true;
  ui.els.cardLock.classList.toggle('hidden', mode === 'new' || locked);
  ui.els.cardLock.textContent = prompt ? '解锁' : '审核并锁定';
  setTitle(mode === 'new' ? '' : state.cardName, mode === 'new' ? '新卡片' : '', prompt);
  ui.els.cardFloat.classList.toggle('hidden', mode === 'new');
  ui.els.cardFloat.textContent = callBridge('floatingCard') === state.cardName ? '取消悬浮' : '悬浮';
  ui.els.cardAttach.classList.toggle('hidden', locked || prompt);
  // The name can be changed while editing: that is also how a card is moved into a folder.
  ui.els.cardNameField.classList.toggle('hidden', mode !== 'new');
  if (mode === 'read') ui.els.cardNameInput.value = state.cardName;
  ui.els.cardCopy.classList.remove('hidden');
  ui.els.cardDelete.classList.toggle('hidden', mode === 'new' || locked || prompt);
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
  renderHtmlCard(ui.els.cardBody, text);
  ui.els.cardBody.scrollTop = 0;
}

const livePane = document.getElementById('cardLivePreview');
const sourcePane = document.getElementById('cardText');
let htmlEditor = null;
function renderHtmlCard(target, text, pictureIndex) {
  mountHtml(target, text, doc => {
    bindFileDrop(doc);
    doc.addEventListener('keydown', browseAdjacentCard);
    for (const image of doc.querySelectorAll('img[data-attachment]')) {
      const match = /^media:(m[0-9a-f]{12}\.pdf)$/.exec(image.dataset.attachment);
      if (match) image.replaceWith(pdfPages(match[1], image.alt || 'PDF'));
    }
    doc.addEventListener('click', event => {
      const link = event.target.closest('a');
      if (link) {
        event.preventDefault();
        if (link.hasAttribute('data-path')) {
          if (event.isTrusted && !callBridge('revealCardPath', link.dataset.path)) window.alert('此设备无法定位该路径');
          return;
        }
        if (event.isTrusted && /^https:\/\//i.test(link.getAttribute('href') || '')) window.open(link.href, '_blank', 'noopener,noreferrer');
      } else if (event.target.closest('audio,video,img')) return;
      else if (target === ui.els.cardBody && !doc.getSelection()?.toString()) editCard();
    });
    if (target === livePane) {
      htmlEditor.bind(doc);
      if (pictureIndex && typeof pictureIndex === "object") {
        doc.scrollingElement.scrollTop = pictureIndex.scroll;
        doc.defaultView.focus();
      } else if (pictureIndex !== undefined) {
        const picture = doc.querySelector('[data-card-picture="' + pictureIndex + '"]');
        picture?.scrollIntoView({block:'nearest'});
        picture?.dispatchEvent(new MouseEvent('click',{bubbles:true}));
      }
    }
  }, state.cardName);
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

/** Refreshes the list and the card already being read. */
let changeFrame = 0;
const pendingChanges = new Set();
export function onChanged(name = '') {
  pendingChanges.add(name);
  listDirty = true;
  if (changeFrame) return;
  changeFrame = requestAnimationFrame(() => {
    changeFrame = 0;
    const currentChanged = pendingChanges.has('') || pendingChanges.has(state.cardName);
    pendingChanges.clear();
    if (mode === 'closed') {
      if (state.page === 'cards') refreshListIfNeeded();
    } else if (currentChanged) refreshOpenCard();
  });
}

function refreshOpenCard() {
  load();
  if (mode === 'edit' && !exists(state.cardName) && !localStorage.getItem(draftKey())) {
    show('closed');
    return;
  }
  if (mode === 'read') {
    if (exists(state.cardName)) renderOpenCard();
    else show('closed');
  }
  if (mode === 'edit' && exists(state.cardName)) {
    const latest = callBridge('readCard', state.cardName);
    const pending = localStorage.getItem(draftKey());
    const draftIncluded = pending && ui.els.cardNameInput.value === state.cardName &&
      typeof latest === 'string' && includesDraftWithFigures(latest, sourcePane.value);
    if (typeof latest === 'string' && latest !== original.text && (!pending || draftIncluded)) {
      if (draftIncluded) {
        localStorage.removeItem(draftKey());
        activeReview = null;
        saveStatus('已保存');
      }
      ui.els.cardText.value = latest;
      original = { text: latest };
      resetUndo();
      refreshDraftPreview();
    }
  }
  if (mode !== 'closed') show(mode);
}

export function openForReview(name) {
  if (mode === 'new' || (mode === 'edit' && localStorage.getItem(draftKey()))) {
    ui.toast('请先退出当前编辑，再打开卡片');
    return;
  }
  if (listDirty || !exists(name)) load();
  if (!exists(name)) {
    ui.toast('找不到这张卡片');
    return;
  }
  if (mode !== 'read' || state.cardName !== name) openCard(name);
  ui.showPage('cards');
}

/** Opens a card, to be read: as its text. */
export function openCard(name) {
  if (!exists(name)) return;
  state.cardName = name;
  show('read');
  if (name !== PLAN_CARD) editCard();
  if (mode === 'read') renderOpenCard();
}

function browseAdjacentCard(event) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
  if (event.defaultPrevented || event.isComposing || event.repeat ||
      event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
  if (event.target.isContentEditable || event.target.closest?.('input, textarea, select, video, audio, [role="slider"]') ||
      document.querySelector('.backdrop:not(.hidden), dialog[open]')) return;
  const onTab = event.target === ui.els.tabStream || event.target === ui.els.tabCards;
  if (state.page === 'stream' || mode === 'closed' || onTab) {
    const page = event.key === 'ArrowRight' ? 'cards' : 'stream';
    if (state.page !== page) {
      event.preventDefault();
      ui.showPage(page);
      if (onTab) (page === 'cards' ? ui.els.tabCards : ui.els.tabStream).focus();
    }
    return;
  }
  if (mode !== 'read' && mode !== 'edit') return;
  refreshListIfNeeded();
  const rows = Array.from(ui.els.cardList.querySelectorAll('.card-row'));
  const index = rows.findIndex(row => row.dataset.name === state.cardName);
  const next = rows[index + (event.key === 'ArrowRight' ? 1 : -1)];
  if (index < 0 || !next) return;
  event.preventDefault();
  if (mode === 'edit') {
    autoSave();
    if (localStorage.getItem(draftKey())) return;
  }
  openCard(next.dataset.name);
}
document.addEventListener('keydown', browseAdjacentCard);

let saveTimer;
let original = { text: '' };
const editing = () => mode === 'edit' || mode === 'new';
const draftKey = () => 'reduction.card.draft:' + state.cardName;
const saveStatus = (message) => { document.getElementById('cardSaveState').textContent = message; };

let previewFrame = 0;
let renderedDraft = null;
function refreshDraftPreview(pictureIndex) {
  cancelAnimationFrame(previewFrame);
  if (renderedDraft === ui.els.cardText.value) return;
  renderedDraft = ui.els.cardText.value;
  htmlEditor = htmlFields(renderedDraft, (value, pictureIndex) => {
    renderedDraft = sourcePane.value = value;
    recordInput();
    if (pictureIndex !== undefined) {
      renderedDraft = null;
      refreshDraftPreview(pictureIndex);
    }
  }, undoEdit);
  renderHtmlCard(livePane, htmlEditor.html, pictureIndex);
}
// A saved document may already contain the entire draft plus newly inserted figures.
function includesDraftWithFigures(saved, draft) {
  if (saved === draft) return true;
  const doc = new DOMParser().parseFromString(saved, 'text/html');
  const before = new DOMParser().parseFromString(draft, 'text/html');
  const nodes = [...before.body.childNodes];
  let index = 0;
  for (const node of [...doc.body.childNodes]) {
    if (nodes[index]?.isEqualNode(node)) index++;
    else if (node.nodeName === 'FIGURE') node.remove();
    else return false;
  }
  return index === nodes.length && doc.documentElement.isEqualNode(before.documentElement);
}

function beginEditing() {
  flushSnapshot();
  clearTimeout(saveTimer);
  activeReview = null;
  original = { text: ui.els.cardText.value };
  saveStatus('');
  renderedDraft = null;
  document.getElementById('cardLivePreview').scrollTop = 0;
  const storedDocument = original.text;
  const stored = localStorage.getItem(draftKey());
  if (!stored) { resetUndo(); refreshDraftPreview(); return; }
  const draft = JSON.parse(stored);
  if (draft.name === state.cardName && includesDraftWithFigures(storedDocument, draft.text)) {
    localStorage.removeItem(draftKey());
    resetUndo();
    refreshDraftPreview();
    return;
  }
  original = { text: draft.original.text };
  ui.els.cardNameInput.value = draft.name;
  ui.els.cardText.value = callBridge('importCard', draft.text);
  resetUndo();
  refreshDraftPreview();
  saveStatus('已恢复本机草稿');
  if (mode !== 'new' && original.text !== storedDocument) saveTimer = setTimeout(autoSave, 700);
}

let activeReview = null;
function resolveConcurrentEdit(base, draft, current) {
  if (typeof current !== 'string') return false;
  const plan = planMerge(base,draft,current);
  if (!plan) {
    saveStatus('段落位置或结构已改变，请比较两个版本');
    const status = document.getElementById('cardSaveState');
    const compare = document.createElement('button');
    compare.textContent = '查看分歧';
    status.append(compare);
    compare.onclick = () => {
      const d = document.createElement('dialog');
      for (const [label,value] of [['我的草稿',draft],['已保存版本',current]]) {
        const box=document.createElement('details'), title=document.createElement('summary'), body=document.createElement('pre');
        title.textContent=label; body.textContent=value; box.append(title,body);d.append(box);
      }
      for (const [label,value] of [['采用我的完整草稿',draft],['采用已保存版本',current]]) {
        const choice=document.createElement('button');
        choice.textContent=label;
        choice.onclick=()=>{
          d.close();
          if(sourcePane.value!==draft || callBridge('readCard',state.cardName)!==current) {autoSave();return;}
          sourcePane.value=value;
          original={text:current};
          recordInput();
          autoSave();
          refreshDraftPreview();
        };
        d.append(choice);
      }
      const close=document.createElement('button'); close.textContent='继续编辑'; close.onclick=()=>d.close();
      d.append(close); document.body.append(d);d.onclose=()=>d.remove();d.showModal();
    };
    return true;
  }
  const name = state.cardName;
  function save(choices) {
    if (state.cardName !== name || sourcePane.value !== draft) return false;
    const merged = plan.finish(choices);
    if (callBridge('autosaveCard',name,'',merged,false,'',current) !== 'saved') return false;
    original = {text:merged};
    sourcePane.value = merged;
    localStorage.removeItem(draftKey());
    resetUndo();
    refreshDraftPreview();
    saveStatus('已合并并保存');
    return true;
  }
  if (!plan.conflicts.length) return save([]);
  if (activeReview?.name===name && activeReview.draft===draft && activeReview.current===current) return true;
  const id = crypto.randomUUID();
  const review = activeReview = {id,name,draft,current};
  const status = document.getElementById('cardSaveState');
  const button = document.createElement('button');
  button.textContent = '比较冲突修改';
  status.textContent = '正在合并';
  let explanation = '';
  review.report = text => {
    if (name !== state.cardName || sourcePane.value !== draft) return;
    let response;
    try { response = JSON.parse(text); } catch (_) {}
    const signals = value => (value.match(/\d+(?:[.,]\d+)*|不|没|禁止|必须|仅|never|must|not/gi) || []).join('|');
    const proposed = response?.merged;
    if (Array.isArray(proposed) && proposed.length===plan.conflicts.length &&
        plan.conflicts.every((e,i)=>!e.private && typeof proposed[i]==='string' && proposed[i].trim() &&
          signals(e.draft)===signals(e.current) && signals(proposed[i])===signals(e.draft))) {
      activeReview=null;
      if (save(proposed)) return;
      autoSave();
      return;
    }
    explanation = typeof response?.ask==='string' ? response.ask : '两处修改可能影响原意，请选择要保留的文字。';
    button.textContent=explanation;
    status.replaceChildren(button);
    if(review.summary) review.summary.textContent = explanation;
  };
  button.onclick = () => {
    const dialog = document.createElement('dialog');
    const summary = document.createElement('p');
    review.summary = summary;
    summary.textContent = explanation || '正在比较分歧';
    dialog.append(summary);
    const choices = plan.conflicts.map(entry => {
      const field=document.createElement('fieldset');
      const prior=document.createElement('legend'); prior.textContent='原文 · '+entry.original; field.append(prior);
      const group=crypto.randomUUID();
      for(const [label,value] of [['我的修改',entry.draft],['已保存的修改',entry.current]]) {
        const option=document.createElement('label'), radio=document.createElement('input');
        radio.type='radio'; radio.name=group; radio.value=value;
        option.append(radio,document.createTextNode(label+' · '+value)); field.append(option);
      }
      dialog.append(field); return field;
    });
    const apply=document.createElement('button'); apply.textContent='确认合并';
    apply.onclick=()=>{const selected=choices.map(f=>f.querySelector(':checked'));if(selected.some(x=>!x))return;
      dialog.close();activeReview=null;if(!save(selected.map(x=>x.value)))autoSave();};
    const later=document.createElement('button');later.textContent='稍后';later.onclick=()=>dialog.close();
    dialog.append(apply,later);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.showModal();
  };
  const publicChanges = plan.conflicts.filter(e=>!e.private).map(({original,draft,current})=>({original,draft,current}));
  if (publicChanges.length === plan.conflicts.length) callBridge('reviewCardConflict',id,JSON.stringify(publicChanges));
  else review.report('私人标注有分歧，请在本机选择。');
  return true;
}
export function conflictReviewed(id,text) {
  if(activeReview?.id===id) activeReview.report(text);
}

function autoSave() {
  flushSnapshot();
  clearTimeout(saveTimer);
  if (!editing()) return;
  const creating = mode === 'new';
  const name = ui.els.cardNameInput.value.trim().replace(/\s*\/\s*/g, '/');
  const text = ui.els.cardText.value;
  if (!name) return;
  const key = draftKey();
  if (!localStorage.getItem(key)) return;
  const result = callBridge('autosaveCard', creating ? name : state.cardName, '', text, creating, '', original.text);
  if (result === 'conflict' && !creating && name === state.cardName && resolveConcurrentEdit(original.text,text,callBridge('readCard',name))) return;
  if (result !== 'saved') {
    saveStatus(result === 'conflict' ? (creating ? '同名卡片已存在，请修改卡名' : '卡片路径已变化，请核对保存位置') :
      result === 'limit' ? '内容超过卡片上限，本机草稿已保留' : '写入失败，本机草稿已保留');
    return;
  }
  original = { text };
  if (!creating && name !== state.cardName && callBridge('renameCard', state.cardName, name) !== true) {
    localStorage.setItem(key, JSON.stringify({ ...fields(), original }));
    saveStatus('名称不可用，正文已保存');
    return;
  }
  localStorage.removeItem(key);
  if (creating || name !== state.cardName) {
    state.cardName = name;
    load();
    renderList();
    show('edit');
  }
  saveStatus('已自动保存');
}

let previousFields;
const undoStack = [];
function fields() {
  return { name: ui.els.cardNameInput.value, text: ui.els.cardText.value };
}
function resetUndo() {
  previousFields = fields();
  undoStack.length = 0;
}
export function undoEdit() {
  const previous = undoStack.pop();
  if (!previous || !editing()) return;
  ui.els.cardNameInput.value = previous.name;
  ui.els.cardText.value = previous.text;
  recordInput(false);
  autoSave();
}
// Report the first edit immediately. During key repeat the desktop needs only the latest snapshot.
let snapshotWindow;
let queuedSnapshot;
function flushSnapshot() {
  clearTimeout(snapshotWindow);
  snapshotWindow = undefined;
  if (queuedSnapshot) {
    const args = queuedSnapshot;
    queuedSnapshot = null;
    callBridge('cardDraftSnapshot', ...args);
  }
}
function notifyDesktopDraft(name, base, text) {
  if (queuedSnapshot && queuedSnapshot[0] !== name) flushSnapshot();
  if (snapshotWindow === undefined) {
    callBridge('cardDraftSnapshot', name, base, text);
    snapshotWindow = setTimeout(flushSnapshot, 120);
  } else {
    queuedSnapshot = [name, base, text];
  }
}

function recordInput(remember = true) {
  if (!editing()) return;
  clearTimeout(saveTimer);
  if (remember && previousFields) {
    undoStack.push(previousFields);
    if (undoStack.length > 100) undoStack.shift();
  }
  previousFields = fields();
  if (document.documentElement.classList.contains('desktop-client') && mode === 'edit') {
    notifyDesktopDraft(state.cardName, original.text, previousFields.text);
  }
  cancelAnimationFrame(previewFrame);
  if (renderedDraft !== sourcePane.value) previewFrame = requestAnimationFrame(() => refreshDraftPreview());
  const draft = { original, ...previousFields };
  try {
    localStorage.setItem(draftKey(), JSON.stringify(draft));
  } catch (_) {
    saveStatus('本机草稿保存失败，请复制当前内容');
    return;
  }
  saveStatus('已保存本机草稿');
  saveTimer = setTimeout(autoSave, 700);
}

ui.els.cardNameInput.addEventListener('input', () => {
  if (mode === 'read') {
    const value = ui.els.cardNameInput.value;
    editCard();
    ui.els.cardNameInput.value = value;
  }
  recordInput();
});
document.getElementById('cardEditor').addEventListener('input', recordInput);
// Capture the completed click so a tap that opens the editor cannot immediately close it.
document.addEventListener('click', (event) => {
  if (!editing() || state.page !== 'cards') return;
  if (event.target.closest('dialog, #cardSaveState, .card-folder-menu, .crumb, .crumb-rename, #cardNameField, #cardEditor label, #cardEditor input, #cardText, #cardLivePreview, #cardTools button')) return;
  if (ui.els.cardEditor.contains(document.activeElement)) document.activeElement.blur();
  if (mode === 'new') back();
  else autoSave();
}, true);

window.addEventListener('pagehide', autoSave);
document.addEventListener('visibilitychange', () => { if (document.hidden) autoSave(); });

export function editCard() {
  const card = state.cards.find((item) => item.name === state.cardName);
  if (mode !== 'read' || !card || card.locked || card.prompt) return;
  ui.els.cardNameInput.value = state.cardName;
  ui.els.cardText.value = String(callBridge('readCard', state.cardName) || '');
  const scroll = ui.els.cardBody.scrollTop;
  beginEditing();
  show('edit');
  sourcePane.scrollTop = scroll;
}

export function newCard() {
  state.cardName = '';
  setTitle('', '新卡片');
  ui.els.cardNameInput.value = '';
  ui.els.cardText.value = '<!doctype html><html><body><p></p></body></html>';
  beginEditing();
  show('new');
}

/** Whether a card is open: read, being edited, or being made. */
export function isOpen() {
  return mode !== 'closed';
}

/** The page under the tabs changed: the open card shows only on the cards page, and waits there meanwhile. */
export function onPage() {
  localStorage.setItem(PAGE_KEY, state.page);
  show(mode);
}

/** One step back: from editing to reading, from reading to the list. False when no card is open. */
export function back() {
  if (mode === 'closed') return false;
  if (editing()) autoSave();
  if (editing() && localStorage.getItem(draftKey())) return true;
  show('closed');
  return true;
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

function appendMedia(id, label, type, position) {
  const doc = new DOMParser().parseFromString(sourcePane.value, 'text/html');
  const figure = doc.createElement('figure');
  const tag = type.startsWith('audio/') ? 'audio' : type.startsWith('video/') ? 'video' : 'img';
  const media = doc.createElement(tag);
  media.dataset.attachment = 'media:' + id;
  if (tag === 'img') media.alt = label;
  else media.controls = true;
  figure.append(media);
  const next = position && doc.body.children[position.index++];
  if (next) next.before(figure); else doc.body.append(figure);
  sourcePane.value = '<!doctype html>\n' + doc.documentElement.outerHTML;
  recordInput();
}

/**
 * The user picked something to attach — a picture, a recording, a video, a PDF: the app keeps it
 * and the card's text refers to it by the id it was given. A PDF is also turned into pictures of
 * its pages there and then, which is how it is shown and how the assistant sees it.
 */
export async function attachMedia(event) {
  const files = [...(event.target.files || [])];
  event.target.value = '';
  await attachFiles(files);
}

function bindFileDrop(target) {
  target.addEventListener('dragover', event => {
    if (!event.dataTransfer.types.includes('Files')) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = editing() ? 'copy' : 'none';
  });
  target.addEventListener('drop', event => {
    if (!event.dataTransfer.types.includes('Files')) return;
    event.preventDefault();
    if (!editing()) return;
    const files = [...event.dataTransfer.files];
    const body = target.body;
    let position;
    if (body) {
      const blocks = [...body.children].filter(el => !el.matches('.card-text-format,.card-caption-add'));
      const index = blocks.findIndex(el => {
        const rect = el.getBoundingClientRect();
        return event.clientY < rect.top + rect.height / 2;
      });
      position = {index: index < 0 ? blocks.length : index};
    }
    void attachFiles(files,position);
  });
}

bindFileDrop(ui.els.cardPage);

async function attachFiles(files,position) {
  const name = state.cardName;
  for (const file of files) {
    if (!editing() || state.cardName !== name) return;
    await attachOne(file,name,position);
  }
}
async function attachOne(file,name,position) {
  try {
    const media = /^(image|audio|video)\//.test(file.type) || file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
    if (!media) {
      if (!name || mode === 'new') { ui.toast('请先保存卡片'); return; }
      if (file.size > 20 * 1024 * 1024) { ui.toast('附件超过 20 MB'); return; }
      const data = base64Of(await file.arrayBuffer());
      if (callBridge('attachCardFile',name,file.name,data) !== true) throw Error('保存附件失败');
      ui.toast('已挂载 ' + file.name);
      return;
    }
    const named = ((file.name.match(/\.([A-Za-z0-9]{2,5})$/) || [])[1] || '').toLowerCase();
    const isPdf = file.type === 'application/pdf' || named === 'pdf';
    const isDrawing = file.type === 'image/svg+xml' || named === 'svg';
    // A photograph is made smaller; an animation and a drawing are kept as they are.
    const isImage = file.type.startsWith('image/') && file.type !== 'image/gif' && !isDrawing;
    const fallback = isDrawing ? 'svg' : file.type.startsWith('audio/') ? 'm4a' : file.type.startsWith('video/') ? 'mp4' : file.type === 'image/gif' ? 'gif' : '';
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
      if (!editing() || state.cardName !== name) return;
      appendMedia(id, label, file.type, position);
      ui.toast('PDF 已放进卡片，共 ' + count + ' 页');
      return;
    }
    if (!editing() || state.cardName !== name) return;
    appendMedia(id, label, file.type, position);
    ui.toast((file.type.startsWith('audio/') ? '录音' : file.type.startsWith('video/') ? '视频' : '图片') + '已放进卡片');
  } catch (error) {
    ui.toast('放不进去：' + (error && error.message ? error.message : '读不了这个文件'));
  }
}

document.getElementById('cardExport').addEventListener('change', event => {
  const format = event.target.value;
  event.target.value = '';
  if (!format || !state.cardName) return;
  if (editing()) {
    autoSave();
    if (localStorage.getItem(draftKey())) { ui.toast('请先保存当前草稿'); return; }
  }
  const result = callBridge('exportCard', state.cardName, format);
  if (result?.startsWith('已复制到剪贴板：')) ui.toast(format + '到达剪切板！');
  else if (result?.startsWith('已导出到 ')) ui.toast(format + '已导出！');
  else ui.toast(result || '导出失败');
});

export async function copyCard() {
  if (callBridge('canShareCard',state.cardName) !== true) { ui.toast('私人卡片仅供作者查看'); return; }
  let raw = editing() ? ui.els.cardText.value : String(callBridge('readCard', state.cardName) || '').trim();
  if (/data-card-privacy|<\/?card-private/i.test(raw)) {
    try {
      const { publicSource } = await import('../article/article.js');
      raw = await publicSource(raw);
    } catch (error) { ui.toast(error.message); return; }
  }
  const doc = /^\s*(?:<!doctype\s+html\b|<html\b)/i.test(raw) ? new DOMParser().parseFromString(raw, 'text/html') : null;
  doc?.querySelectorAll('style,script,template,[hidden]').forEach(el => el.remove());
  const text = doc ? doc.body.textContent.trim() : raw;
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
