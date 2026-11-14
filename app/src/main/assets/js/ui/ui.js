import { callBridge } from '../core/bridge.js';
import { renderMarkdown } from './markdown.js';
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
  saveSettings: byId('saveSettings'),
  testTts: byId('testTts'),
  clearAudioCache: byId('clearAudioCache'),
  clearHistory: byId('clearHistory'),
  shareDiagnostics: byId('shareDiagnostics'),
  appInfo: byId('appInfo'),
  pcBtn: byId('pcBtn'),
  pcBackdrop: byId('pcBackdrop'),
  pcSheet: byId('pcSheet'),
  closePc: byId('closePc'),
  pcUrlInput: byId('pcUrlInput'),
  pcTokenInput: byId('pcTokenInput'),
  pcTest: byId('pcTest'),
  pcCheckUpdate: byId('pcCheckUpdate'),
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

export function setStatus(text) {
  els.status.textContent = text;
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
  for (const element of [els.settingsSheet, els.pcSheet, els.settingsBackdrop, els.pcBackdrop]) {
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

let pressedBubble = null;
let pressedTimer = null;
let pressedX = 0;
let pressedY = 0;
let pressedPointerId = null;
let menuOpen = false;
let menuPointerId = null;
let menuActiveAction = null;
let menuContent = '';
let menuCanRead = false;

function setMenuActive(action) {
  if (menuActiveAction === action) return;
  menuActiveAction = action;
  els.messageCopy.classList.toggle('active', action === 'copy');
  els.messageRead.classList.toggle('active', action === 'read');
}

function actionAtPoint(x, y) {
  const target = document.elementFromPoint(x, y);
  const button = target && target.closest ? target.closest('.message-action') : null;
  if (button === els.messageCopy) return 'copy';
  if (button === els.messageRead) return 'read';
  return null;
}

function onMenuPointerMove(event) {
  if (!menuOpen) return;
  if (menuPointerId != null && event.pointerId !== menuPointerId) return;
  event.preventDefault();
  setMenuActive(actionAtPoint(event.clientX, event.clientY));
}

function onMenuPointerUp(event) {
  if (!menuOpen) return;
  if (menuPointerId != null && event.pointerId !== menuPointerId) return;
  const action = menuActiveAction;
  detachMenuGesture();
  if (action) {
    runMessageAction(action);
  } else {
    setMenuActive(null);
  }
}

function onMenuPointerCancel(event) {
  if (!menuOpen) return;
  if (menuPointerId != null && event.pointerId !== menuPointerId) return;
  detachMenuGesture();
  setMenuActive(null);
}

function preventMenuTouch(event) {
  if (menuOpen) event.preventDefault();
}

function detachMenuGesture() {
  window.removeEventListener('pointermove', onMenuPointerMove);
  window.removeEventListener('pointerup', onMenuPointerUp);
  window.removeEventListener('pointercancel', onMenuPointerCancel);
  document.removeEventListener('touchmove', preventMenuTouch);
  menuPointerId = null;
}

function runMessageAction(action) {
  const content = menuContent;
  const canRead = menuCanRead;
  closeMessageMenu();
  if (!content) return;
  if (action === 'copy') {
    callBridge('copyText', content);
    toast('已复制');
  } else if (action === 'read' && canRead) {
    readHandler(content);
  }
}

function openMessageMenu(message, x, y, pointerId) {
  closeMessageMenu();
  menuContent = message.content;
  menuCanRead = message.role === 'assistant';
  els.messageRead.classList.toggle('hidden', !menuCanRead);
  els.messageBackdrop.classList.remove('hidden');

  const menu = els.messageMenu;
  menu.classList.remove('hidden');
  menu.style.visibility = 'hidden';
  menu.style.left = '0px';
  menu.style.top = '0px';

  const rect = menu.getBoundingClientRect();
  const margin = 10;
  const gap = 14;
  let left = x - rect.width / 2;
  left = Math.max(margin, Math.min(left, window.innerWidth - rect.width - margin));
  let top = y - rect.height - gap;
  if (top < margin) top = y + gap;
  top = Math.max(margin, Math.min(top, window.innerHeight - rect.height - margin));

  menu.style.left = Math.round(left) + 'px';
  menu.style.top = Math.round(top) + 'px';
  menu.style.visibility = 'visible';

  menuOpen = true;
  menuPointerId = pointerId;
  setMenuActive(null);
  window.addEventListener('pointermove', onMenuPointerMove, { passive: false });
  window.addEventListener('pointerup', onMenuPointerUp);
  window.addEventListener('pointercancel', onMenuPointerCancel);
  document.addEventListener('touchmove', preventMenuTouch, { passive: false });
  callBridge('vibrate', 15);
}

function closeMessageMenu() {
  detachMenuGesture();
  menuOpen = false;
  menuActiveAction = null;
  menuContent = '';
  menuCanRead = false;
  els.messageBackdrop.classList.add('hidden');
  els.messageMenu.classList.add('hidden');
  els.messageMenu.style.visibility = '';
  els.messageCopy.classList.remove('active');
  els.messageRead.classList.remove('active');
}

function bindLongPress(bubble, message) {
  bubble.addEventListener('pointerdown', (event) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    pressedBubble = bubble;
    pressedX = event.clientX;
    pressedY = event.clientY;
    pressedPointerId = event.pointerId;
    clearTimeout(pressedTimer);
    pressedTimer = setTimeout(() => {
      if (pressedBubble === bubble) {
        openMessageMenu(message, pressedX, pressedY, pressedPointerId);
      }
    }, 320);
  });
  bubble.addEventListener('pointermove', (event) => {
    if (!pressedBubble) return;
    if (Math.abs(event.clientX - pressedX) > 10 || Math.abs(event.clientY - pressedY) > 10) {
      clearTimeout(pressedTimer);
      pressedBubble = null;
    }
  });
  const release = () => {
    clearTimeout(pressedTimer);
    pressedBubble = null;
  };
  bubble.addEventListener('pointerup', release);
  bubble.addEventListener('pointercancel', release);
  bubble.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    openMessageMenu(message, event.clientX, event.clientY, null);
  });
}

els.messageBackdrop.addEventListener('click', closeMessageMenu);
els.messageCopy.addEventListener('click', () => {
  if (menuOpen) runMessageAction('copy');
});
els.messageRead.addEventListener('click', () => {
  if (menuOpen) runMessageAction('read');
});

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

  bindLongPress(bubble, message);
  row.appendChild(bubble);
  row.appendChild(meta);
  return { row, bubble };
}

export function renderAssistantContent(bubble, text) {
  if (!bubble) return;
  bubble.innerHTML = renderMarkdown(text);
}

export function appendMessage(role, content) {
  const message = { role, content };
  state.messages.push(message);
  const node = createMessageNode(message);
  els.messages.appendChild(node.row);
  updateEmptyState();
  scrollToBottom(true);
  return { message, bubble: node.bubble };
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
