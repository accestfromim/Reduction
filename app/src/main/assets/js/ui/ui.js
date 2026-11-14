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
  toast: byId('toast')
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

export function scrollToBottom() {
  requestAnimationFrame(() => {
    els.messages.scrollTop = els.messages.scrollHeight;
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

  if (message.role === 'assistant') {
    const read = document.createElement('button');
    read.className = 'mini-btn';
    read.textContent = '朗读';
    read.addEventListener('click', () => {
      if (message.content.trim()) readHandler(message.content);
    });
    meta.appendChild(read);
  }

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
  scrollToBottom();
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
  scrollToBottom();
}
