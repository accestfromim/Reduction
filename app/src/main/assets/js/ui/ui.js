import { callBridge } from '../core/bridge.js';
import { renderMarkdown } from './markdown.js';
import { createMessageMenu } from './message-menu.js';
import { state } from '../core/state.js';

const byId = (id) => document.getElementById(id);

export const els = {
  status: byId('status'),
  messages: byId('messages'),
  emptyState: byId('emptyState'),
  micBtn: byId('micBtn'),
  voiceMode: byId('voiceMode'),
  textMode: byId('textMode'),
  voiceBtn: byId('voiceBtn'),
  textInput: byId('textInput'),
  sendBtn: byId('sendBtn'),
  composerHint: byId('composerHint'),
  recordingBar: byId('recordingBar'),
  recordingTime: byId('recordingTime'),
  recordingHint: byId('recordingHint'),
  cancelRecording: byId('cancelRecording'),
  finishRecording: byId('finishRecording'),
  settingsBtn: byId('settingsBtn'),
  settingsBackdrop: byId('settingsBackdrop'),
  settingsSheet: byId('settingsSheet'),
  closeSettings: byId('closeSettings'),
  apiKeyInput: byId('apiKeyInput'),
  modelSelect: byId('modelSelect'),
  voiceSelect: byId('voiceSelect'),
  autoSpeakInput: byId('autoSpeakInput'),
  webSearchInput: byId('webSearchInput'),
  calculatorInput: byId('calculatorInput'),
  wakeWordInput: byId('wakeWordInput'),
  bargeInInput: byId('bargeInInput'),
  headsetMicInput: byId('headsetMicInput'),
  opencodeEnabledInput: byId('opencodeEnabledInput'),
  opencodeWriteInput: byId('opencodeWriteInput'),
  opencodeSessionInput: byId('opencodeSessionInput'),
  pcProjectInput: byId('pcProjectInput'),
  saveSettings: byId('saveSettings'),
  testTts: byId('testTts'),
  clearAudioCache: byId('clearAudioCache'),
  clearHistory: byId('clearHistory'),
  shareDiagnostics: byId('shareDiagnostics'),
  appInfo: byId('appInfo'),
  pcBtn: byId('pcBtn'),
  pcPill: byId('pcPill'),
  pcPillText: byId('pcPillText'),
  updatePill: byId('updatePill'),
  updatePillText: byId('updatePillText'),
  tabStream: byId('tabStream'),
  tabCards: byId('tabCards'),
  streamPage: byId('streamPage'),
  cardsPage: byId('cardsPage'),
  cardList: byId('cardList'),
  cardNew: byId('cardNew'),
  cardPage: byId('cardPage'),
  cardBack: byId('cardBack'),
  cardTitle: byId('cardTitle'),
  cardCopyAll: byId('cardCopyAll'),
  cardEdit: byId('cardEdit'),
  cardBody: byId('cardBody'),
  cardEditor: byId('cardEditor'),
  cardNameField: byId('cardNameField'),
  cardNameInput: byId('cardNameInput'),
  cardPurposeInput: byId('cardPurposeInput'),
  cardText: byId('cardText'),
  cardSave: byId('cardSave'),
  cardCopy: byId('cardCopy'),
  cardDelete: byId('cardDelete'),
  pcBackdrop: byId('pcBackdrop'),
  pcSheet: byId('pcSheet'),
  closePc: byId('closePc'),
  pcHostInput: byId('pcHostInput'),
  pcPortInput: byId('pcPortInput'),
  pcUserInput: byId('pcUserInput'),
  pcTest: byId('pcTest'),
  pcCopyKey: byId('pcCopyKey'),
  pcCommandInput: byId('pcCommandInput'),
  pcRunCommand: byId('pcRunCommand'),
  pcStatus: byId('pcStatus'),
  pcOutput: byId('pcOutput'),
  toast: byId('toast'),
  messageBackdrop: byId('messageBackdrop'),
  messageMenu: byId('messageMenu'),
  messageCopy: byId('messageCopy'),
  messageRead: byId('messageRead')
};

let toastTimer = null;
let readHandler = () => {};

export function setReadHandler(handler) {
  readHandler = typeof handler === 'function' ? handler : () => {};
}

export function toast(message) {
  els.toast.textContent = message;
  els.toast.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.add('hidden'), 3200);
}

const messageMenu = createMessageMenu({
  elements: {
    backdrop: els.messageBackdrop,
    menu: els.messageMenu,
    copy: els.messageCopy,
    read: els.messageRead
  },
  copyText: (text) => callBridge('copyText', text),
  showToast: toast,
  readText: (text) => readHandler(text),
  vibrate: (ms) => callBridge('vibrate', ms)
});

/** Shows one of the two pages: 'stream', what is being said, or 'cards', what is kept. */
export function showPage(name) {
  state.page = name === 'cards' ? 'cards' : 'stream';
  const cards = state.page === 'cards';
  els.streamPage.classList.toggle('hidden', cards);
  els.cardsPage.classList.toggle('hidden', !cards);
  els.tabStream.classList.toggle('active', !cards);
  els.tabCards.classList.toggle('active', cards);
  if (!cards) scrollToBottom(true);
}

export function setStatus(text) {
  els.status.textContent = text;
}

export function setPcConnection(state) {
  const next = state === 'connected' ? 'online' : state === 'connecting' ? 'connecting' : 'offline';
  els.pcPill.classList.remove('offline', 'connecting', 'online');
  els.pcPill.classList.add(next);
  els.pcPillText.textContent = next === 'online' ? '已连接' : next === 'connecting' ? '连接中' : '未连接';
}

export function setUpdateState(state, version, progress) {
  const visible = state === 'available' || state === 'downloading' || state === 'ready';
  els.updatePill.classList.toggle('hidden', !visible);
  els.updatePill.classList.remove('available', 'downloading', 'ready');
  if (!visible) return;
  els.updatePill.classList.add(state);
  els.updatePill.disabled = state !== 'ready';
  els.updatePill.title = version ? `Reduction ${version}` : '软件更新';
  els.updatePillText.textContent = state === 'available'
    ? '有新版本'
    : state === 'downloading'
      ? (progress ? `下载中 ${progress}%` : '下载中')
      : '可更新';
}

export function scrollToBottom(force = false) {
  const box = els.messages;
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 120;
  if (!force && !nearBottom) return;
  requestAnimationFrame(() => {
    box.scrollTop = box.scrollHeight;
  });
}

function updateEmptyState() {
  const hasMessages = state.messages.length > 0;
  els.emptyState.classList.toggle('hidden', hasMessages);
  els.messages.style.display = hasMessages ? 'block' : 'none';
}

export function updateHint() {
  let hint = '';
  if (!state.settings.apiKey) hint = '请填写 API Key';
  else if (!state.micGranted) hint = '需要麦克风权限';
  els.composerHint.textContent = hint;
}

export function updateControls() {
  els.sendBtn.disabled = state.busy || !els.textInput.value.trim();
  els.micBtn.classList.toggle('recording', state.recording);
}

export function showTextMode() {
  state.textMode = true;
  els.voiceMode.classList.add('hidden');
  els.textMode.classList.remove('hidden');
  updateControls();
  setTimeout(() => {
    try { els.textInput.focus(); } catch (_) {}
  }, 60);
}

export function showVoiceMode() {
  state.textMode = false;
  try { els.textInput.blur(); } catch (_) {}
  els.textMode.classList.add('hidden');
  els.voiceMode.classList.remove('hidden');
  updateHint();
  updateControls();
}

export function openSheet(sheet, backdrop) {
  closeSheets();
  backdrop.classList.remove('hidden');
  sheet.classList.remove('hidden');
}

export function closeSheets() {
  for (const element of [
    els.settingsSheet,
    els.pcSheet,
    els.settingsBackdrop,
    els.pcBackdrop
  ]) {
    element.classList.add('hidden');
  }
}

export function showRecordingBar(hint) {
  state.recording = true;
  els.recordingBar.classList.remove('hidden');
  els.recordingHint.textContent = hint;
  els.micBtn.classList.add('recording');
}

export function setRecordingTime(text) {
  els.recordingTime.textContent = text;
}

export function hideRecordingBar() {
  state.recording = false;
  clearInterval(state.recTimer);
  state.recTimer = null;
  els.recordingBar.classList.add('hidden');
  els.micBtn.classList.remove('recording');
}

export function setPcStatus(text, kind) {
  els.pcStatus.textContent = text;
  els.pcStatus.classList.remove('ok', 'err');
  if (kind) els.pcStatus.classList.add(kind);
}

export function setPcOutput(text) {
  els.pcOutput.textContent = text || '';
}

export function setAppInfo(text) {
  els.appInfo.textContent = text || '';
}

function createMessageNode(message) {
  const row = document.createElement('div');
  row.className = `msg-row ${message.role}`;

  const bubble = document.createElement('div');
  bubble.className = message.role === 'assistant' ? 'bubble markdown' : 'bubble';
  if (message.role === 'assistant') {
    renderAssistantContent(bubble, message.content);
  } else {
    bubble.textContent = message.content;
  }

  const meta = document.createElement('div');
  meta.className = 'meta';
  meta.textContent = message.role === 'user' ? '你' : '助手';

  const trace = document.createElement('div');
  trace.className = 'tool-trace hidden';

  messageMenu.bind(bubble, message);
  if (message.role === 'assistant') row.appendChild(trace);
  row.appendChild(bubble);
  row.appendChild(meta);
  return { row, bubble, trace };
}

/** Draws a reply: the text that is read aloud, as Markdown. */
export function renderAssistantContent(bubble, text) {
  if (bubble) bubble.innerHTML = renderMarkdown(String(text || '').trim());
}

export function appendMessage(role, content) {
  const message = { role, content };
  state.messages.push(message);
  const node = createMessageNode(message);
  els.messages.appendChild(node.row);
  updateEmptyState();
  scrollToBottom(true);
  return { message, bubble: node.bubble, trace: node.trace };
}

function formatToolArgs(raw) {
  try {
    const value = JSON.parse(raw || '{}');
    return Object.entries(value)
      .map(([key, item]) => `${key}: ${String(item)}`)
      .join('\n');
  } catch (_) {
    return String(raw || '');
  }
}

function toolSection(label, text) {
  const section = document.createElement('div');
  section.className = 'tool-section';
  const title = document.createElement('div');
  title.className = 'tool-label';
  title.textContent = label;
  const body = document.createElement('div');
  body.className = 'tool-text';
  body.textContent = text;
  section.append(title, body);
  return section;
}

function clock(date) {
  const two = (value) => String(value).padStart(2, '0');
  return two(date.getHours()) + ':' + two(date.getMinutes());
}

/** A tool call begins: one line with its summary. What went in is behind a tap. */
export function addToolTrace(trace, name, summary, argumentsJson) {
  if (!trace) return;
  trace.classList.remove('hidden');
  const line = document.createElement('div');
  line.className = 'tool-line running';
  line.dataset.name = name;
  const head = document.createElement('div');
  head.className = 'tool-head';
  const dot = document.createElement('span');
  dot.className = 'tool-dot';
  const title = document.createElement('span');
  title.className = 'tool-summary';
  title.textContent = summary || name;
  const time = document.createElement('span');
  time.className = 'tool-time';
  time.textContent = '进行中';
  head.append(dot, title, time);
  const body = document.createElement('div');
  body.className = 'tool-body';
  body.appendChild(toolSection('工具', name));
  body.appendChild(toolSection('输入', formatToolArgs(argumentsJson)));
  line.append(head, body);
  head.addEventListener('click', () => line.classList.toggle('open'));
  trace.appendChild(line);
  scrollToBottom();
}

/** The call is over: the line shows when it finished and whether it worked. */
export function finishToolTrace(trace, name, durationMs, success, result) {
  if (!trace) return;
  const lines = Array.from(trace.querySelectorAll('.tool-line.running'));
  const line = lines.reverse().find((item) => item.dataset.name === name);
  if (!line) return;
  line.classList.remove('running');
  line.classList.add(success ? 'done' : 'failed');
  const time = line.querySelector('.tool-time');
  if (time) time.textContent = (success ? '' : '失败 ') + clock(new Date());
  const body = line.querySelector('.tool-body');
  if (!body) return;
  if (result) body.appendChild(toolSection('输出', String(result)));
  body.appendChild(toolSection('用时', (durationMs / 1000).toFixed(1) + ' 秒'));
}

/** The reply ended: a call still marked as running never finished. */
export function settleToolTrace(trace) {
  if (!trace) return;
  for (const line of trace.querySelectorAll('.tool-line.running')) {
    line.classList.remove('running');
    line.classList.add('failed');
    const time = line.querySelector('.tool-time');
    if (time) time.textContent = '已中断';
  }
}

export function removeMessage(message) {
  const index = state.messages.indexOf(message);
  if (index >= 0) state.messages.splice(index, 1);
  renderAll();
}

export function renderAll() {
  els.messages.innerHTML = '';
  for (const message of state.messages) {
    const node = createMessageNode(message);
    els.messages.appendChild(node.row);
  }
  updateEmptyState();
  scrollToBottom(true);
}
