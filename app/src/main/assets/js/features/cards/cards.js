import { mountHtml } from '../../components/html/index.js';
import { htmlFields } from './html-fields.js';
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
  ui.els.cardLock.classList.toggle('hidden', mode !== 'read' || locked);
  ui.els.cardLock.textContent = prompt ? '解锁' : '审核并锁定';
  setTitle(mode === 'new' ? '' : state.cardName, mode === 'new' ? '新卡片' : '', prompt);
  ui.els.cardFloat.classList.toggle('hidden', mode !== 'read');
  ui.els.cardFloat.textContent = callBridge('floatingCard') === state.cardName ? '取消悬浮' : '悬浮';
  ui.els.cardAttach.classList.toggle('hidden', locked || prompt || asArticle);
  // The name can be changed while editing: that is also how a card is moved into a folder.
  ui.els.cardNameField.classList.toggle('hidden', !editing);
  ui.els.cardCopy.classList.remove('hidden');
  ui.els.cardDelete.classList.toggle('hidden', mode === 'new' || locked || prompt);
}

function cardText(name) {
  const text = callBridge('readCard', name);
  if (typeof text === 'string') return text;
  ui.toast('未能读取最新卡片，请检查服务器连接');
  return null;
}

function renderOpenCard() {
  setTitle(state.cardName);
  if (state.cardName === PLAN_CARD) {
    renderPlan();
    return;
  }
  ui.els.cardPlan.classList.add('hidden');
  ui.els.cardBody.classList.remove('hidden');
  const text = cardText(state.cardName);
  if (text === null) { ui.els.cardBody.textContent = '未能读取最新卡片'; return; }
  renderCardInto(ui.els.cardBody, text.trim());
  ui.els.cardBody.scrollTop = 0;
  if (asArticle) drawArticle();
}

const livePane = document.getElementById('cardLivePreview');
const sourcePane = document.getElementById('cardText');
let htmlEditor = null;
const expectedScroll = new WeakMap();
function previewScrollBox() {
  const frame = livePane.querySelector('iframe');
  return frame?.contentDocument?.scrollingElement || livePane;
}
function syncScroll(from, to) {
  const range = from.scrollHeight - from.clientHeight;
  if (range <= 0 || !to) return;
  const offset = from.scrollTop / range * Math.max(0, to.scrollHeight - to.clientHeight);
  if (Math.abs(offset - to.scrollTop) <= 1) return;
  to.scrollTop = offset;
  expectedScroll.set(to, to.scrollTop);
}
function followScroll(from, to) {
  const expected = expectedScroll.get(from);
  expectedScroll.delete(from);
  if (expected !== undefined && Math.abs(from.scrollTop - expected) <= 1) return;
  syncScroll(from, to);
}
sourcePane.addEventListener('scroll', () => followScroll(sourcePane, previewScrollBox()));
livePane.addEventListener('scroll', () => followScroll(livePane, sourcePane));

function renderHtmlCard(target, text) {
  mountHtml(target, text, doc => {
    doc.addEventListener('keydown', browseAdjacentCard);
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
    if (target === livePane && doc.scrollingElement) {
      if (htmlEditor) { htmlEditor.bind(doc); return; }
      syncScroll(sourcePane, doc.scrollingElement);
      doc.addEventListener('scroll', () => followScroll(doc.scrollingElement, sourcePane));
    }
  });
}

function renderCardInto(target, text) {
  if (/^\s*(?:<!doctype\s+html\b|<html\b)/i.test(text) && window.DOMPurify) {
    renderHtmlCard(target, text);
    return;
  }
  // A picture or a recording is written as ![说明](media:编号); it is fetched from the app by that id.
  target.innerHTML = text ? renderMarkdown(text.replace(/\]\(media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})\)/g, '](/card-media/$1)')) : '';
  for (const image of target.querySelectorAll('img')) {
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
    const text = cardText(name);
    if (text === null) return;
    const { height, problem } = await article.show(frame, text);
    if (name !== state.cardName || !asArticle) return;
    frame.contentDocument?.addEventListener('keydown', browseAdjacentCard);
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
  ];
  if (Array.isArray(data.undated) && data.undated.length > 0) {
    blocks.push(['没日期', data.undated]);
  }
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
export function onChanged() {
  load();
  renderList();
  if (mode === 'read') {
    if (exists(state.cardName)) renderOpenCard();
    else show('closed');
  }
  if (mode !== 'closed') show(mode);
}

export function openForReview(name) {
  if (mode === 'edit' || mode === 'new') {
    ui.toast('请先退出当前编辑，再打开卡片');
    return;
  }
  load();
  if (!exists(name)) {
    ui.toast('找不到这张卡片');
    return;
  }
  if (mode !== 'read' || state.cardName !== name) openCard(name);
  ui.showPage('cards');
}

/** Opens a card, to be read: as its text. The article view is asked for each time. */
export function openCard(name) {
  if (!exists(name)) return;
  asArticle = false;
  state.cardName = name;
  renderOpenCard();
  show('read');
  if (document.documentElement.classList.contains('desktop-client') && name.startsWith('个人知识库/')) {
    editCard();
  }
}

function browseAdjacentCard(event) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
  if (state.page !== 'cards' || (mode !== 'read' && mode !== 'edit')) return;
  if (event.defaultPrevented || event.isComposing || event.repeat ||
      event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
  if (event.target.isContentEditable || event.target.closest?.('input, textarea, select, video, audio, [role="slider"]') ||
      document.querySelector('.backdrop:not(.hidden), dialog[open]')) return;
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
function refreshDraftPreview() {
  cancelAnimationFrame(previewFrame);
  if (renderedDraft === ui.els.cardText.value) return;
  renderedDraft = ui.els.cardText.value;
  const html = /^\s*(?:<!doctype\s+html\b|<html\b)/i.test(renderedDraft);
  sourcePane.classList.toggle('hidden',html);
  sourcePane.closest('.card-split').classList.toggle('inline-html', html);
  htmlEditor = html ? htmlFields(renderedDraft, value => {
    sourcePane.value=value;
    renderedDraft=value;
    recordInput();
  }) : null;
  renderCardInto(livePane, htmlEditor?.html ?? renderedDraft);
  if (!livePane.querySelector('iframe')) syncScroll(sourcePane, livePane);
}
function beginEditing() {
  clearTimeout(saveTimer);
  original = { text: ui.els.cardText.value };
  saveStatus('');
  renderedDraft = null;
  document.getElementById('cardLivePreview').scrollTop = 0;
  const stored = localStorage.getItem(draftKey());
  if (!stored) { resetUndo(); refreshDraftPreview(); return; }
  const draft = JSON.parse(stored);
  original = { text: draft.original.text };
  ui.els.cardNameInput.value = draft.name;
  ui.els.cardText.value = draft.text;
  resetUndo();
  refreshDraftPreview();
  saveStatus('已恢复本机草稿');
}

function autoSave() {
  clearTimeout(saveTimer);
  if (!editing()) return;
  const creating = mode === 'new';
  const name = ui.els.cardNameInput.value.trim().replace(/\s*\/\s*/g, '/');
  const text = ui.els.cardText.value;
  if (!name) return;
  const key = draftKey();
  if (!localStorage.getItem(key)) return;
  const result = callBridge('autosaveCard', creating ? name : state.cardName, '', text, creating, '', original.text);
  if (result !== 'saved') {
    saveStatus(result === 'pending' ? '待同步，已保留本机草稿' : result === 'conflict' ? '卡片有其他修改，本机草稿已保留' :
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
function recordInput(remember = true) {
  if (!editing()) return;
  clearTimeout(saveTimer);
  if (remember && previousFields) {
    undoStack.push(previousFields);
    if (undoStack.length > 100) undoStack.shift();
  }
  previousFields = fields();
  if (document.documentElement.classList.contains('desktop-client') && mode === 'edit') {
    callBridge('cardDraftSnapshot', state.cardName, original.text, previousFields.text);
  }
  cancelAnimationFrame(previewFrame);
  previewFrame = requestAnimationFrame(refreshDraftPreview);
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

document.getElementById('cardEditor').addEventListener('input', recordInput);
// Capture the completed click so a tap that opens the editor cannot immediately close it.
document.addEventListener('click', (event) => {
  if (!editing() || state.page !== 'cards') return;
  if (event.target.closest('#cardEditor label, #cardEditor input, #cardText, #cardLivePreview, #cardTools button')) return;
  if (ui.els.cardEditor.contains(document.activeElement)) document.activeElement.blur();
  back();
}, true);

window.addEventListener('pagehide', autoSave);
document.addEventListener('visibilitychange', () => { if (document.hidden) autoSave(); });

export function editCard() {
  const card = state.cards.find((item) => item.name === state.cardName);
  if (mode !== 'read' || !card || card.locked || card.prompt) return;
  ui.els.cardNameInput.value = state.cardName;
  const text = cardText(state.cardName);
  if (text === null) return;
  ui.els.cardText.value = text;
  beginEditing();
  show('edit');
}

export function newCard() {
  state.cardName = '';
  setTitle('', '新卡片');
  ui.els.cardNameInput.value = '';
  ui.els.cardText.value = '';
  beginEditing();
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
  if (editing()) autoSave();
  if (mode === 'edit') {
    renderOpenCard();
    show('read');
  } else {
    show('closed');
  }
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

/** Puts [text] into the editor where the cursor is. */
function insertIntoText(text) {
  const box = ui.els.cardText;
  const at = box.selectionStart ?? box.value.length;
  const before = box.value.slice(0, at);
  const line = (before && !before.endsWith('\n') ? '\n' : '') + text + '\n';
  box.value = before + line + box.value.slice(box.selectionEnd ?? at);
  box.selectionStart = box.selectionEnd = at + line.length;
  recordInput();
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
      insertIntoText('![' + label + '](media:' + id + ')');
      ui.toast('PDF 已放进卡片，共 ' + count + ' 页');
      return;
    }
    insertIntoText('![' + label + '](media:' + id + ')');
    ui.toast((file.type.startsWith('audio/') ? '录音' : file.type.startsWith('video/') ? '视频' : '图片') + '已放进卡片');
  } catch (error) {
    ui.toast('放不进去：' + (error && error.message ? error.message : '读不了这个文件'));
  }
}

export function copyCard() {
  const raw = editing() ? ui.els.cardText.value : cardText(state.cardName);
  if (raw === null) return;
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
  if (callBridge('deleteCard', name) !== true) { ui.toast('删除尚未确认，请检查服务器连接'); return; }
  load();
  renderList();
  show('closed');
  ui.toast('已删除卡片“' + name + '”');
}
