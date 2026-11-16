import { callBridge } from './core/bridge.js';
import * as cards from './features/cards/cards.js';
import * as chat from './features/chat/chat.js';
import * as pc from './features/pc/pc.js';
import * as settings from './features/settings/settings.js';
import { state } from './core/state.js';
import { loadHistory } from './features/chat/history-store.js';
import { loadSettings } from './features/settings/settings-store.js';
import * as ui from './ui/ui.js';
import * as voice from './features/voice/voice.js';
import { playSplash } from './ui/splash.js';

window.Native = {
  onMicPermission(granted) {
    voice.handleMicPermission(granted);
  },

  onRecordingStart(id) {
    voice.handleRecordingStart(id);
  },

  onRecordingLimit(id) {
    voice.handleRecordingLimit(id);
  },

  onAsrResult(id, ok, text) {
    voice.handleAsrResult(id, ok, text);
  },

  onVoiceState(name) {
    voice.applyVoiceState(name);
  },

  onVoicePartial(text) {
    voice.showPartial(text);
  },

  onVoiceError(message) {
    voice.showVoiceError(message);
  },

  onStatus(id, status) {
    if (status === '识别中') ui.setStatus('识别中…');
    else if (status === '思考中') ui.setStatus('思考中…');
    else ui.setStatus(status);
  },

  onReplyBegin(id, text) {
    chat.handleBegin(id, text);
  },

  onChatToken(id, token) {
    chat.handleToken(id, token);
  },

  onToolStarted(id, name, summary, argumentsJson) {
    chat.handleToolStarted(id, name, summary, argumentsJson);
  },

  onToolFinished(id, name, durationMs, success, result) {
    chat.handleToolFinished(id, name, durationMs, success, result);
  },

  onChatDone(id, full) {
    chat.handleDone(id, full);
  },

  onChatError(id, message) {
    chat.handleError(id, message);
  },

  onChatAborted(id) {
    chat.handleAborted(id);
  },

  onTtsStart() {
    chat.handleTtsStart();
  },

  onTtsDone() {
    chat.handleTtsDone();
  },

  onTtsError(id, message) {
    chat.handleTtsError(message);
  },

  onTtsStopped() {
    chat.handleTtsStopped();
  },

  onUpdateState(state, version, progress) {
    ui.setUpdateState(state, version, progress);
  },

  onCardsChanged(name) {
    cards.onChanged(name);
  },

  onAssetsUpdated(version) {
    ui.toast('界面资源已更新，重启 App 生效');
  },

  onPcConnection(state) {
    ui.setPcConnection(state);
  },

  onUpdateError(message) {
    ui.toast(message || '自动更新失败');
  },

  onPcInfo(id, json) {
    pc.handlePcInfo(id, json);
  },

  onPcStatus(id, text) {
    pc.handlePcStatus(id, text);
  },

  onPcExec(id, output) {
    pc.handlePcExec(id, output);
  },

  onPcError(id, message) {
    pc.handlePcError(id, message);
  },

  onError(id, message) {
    if (state.recording) voice.cancelRecording();
    ui.toast(message || '出错了');
    ui.setStatus('');
  }
};

window.onAndroidBack = function () {
  if (!ui.els.pcSheet.classList.contains('hidden') ||
      !ui.els.settingsSheet.classList.contains('hidden')) {
    ui.closeSheets();
    return true;
  }
  if (cards.back()) return true;
  if (state.page === 'cards') {
    ui.showPage('stream');
    return true;
  }
  return false;
};

ui.els.sendBtn.addEventListener('click', () => {
  const text = ui.els.textInput.value;
  ui.els.textInput.value = '';
  ui.updateControls();
  chat.sendText(text);
});

ui.els.textInput.addEventListener('input', ui.updateControls);
ui.els.textInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault();
    const text = ui.els.textInput.value;
    ui.els.textInput.value = '';
    ui.updateControls();
    chat.sendText(text);
  }
});

ui.els.micBtn.addEventListener('pointerdown', voice.onMicDown);
window.addEventListener('pointerup', voice.onMicUp);
window.addEventListener('pointercancel', voice.onMicCancel);
ui.els.micBtn.addEventListener('click', (event) => event.preventDefault());
ui.els.micBtn.addEventListener('contextmenu', (event) => event.preventDefault());
ui.els.voiceBtn.addEventListener('click', ui.showVoiceMode);

ui.els.finishRecording.addEventListener('click', (event) => {
  event.preventDefault();
  if (voice.isHandsFree()) {
    voice.finishTurn();
  } else if (state.recording) {
    voice.endRecording();
  }
});

ui.els.cancelRecording.addEventListener('click', (event) => {
  event.preventDefault();
  voice.cancelRecording();
});

ui.els.settingsBtn.addEventListener('click', settings.openSettings);
window.addEventListener('open-settings', settings.openSettings);
ui.els.closeSettings.addEventListener('click', ui.closeSheets);
ui.els.settingsBackdrop.addEventListener('click', ui.closeSheets);
ui.els.saveSettings.addEventListener('click', settings.saveSettingsForm);
ui.els.testTts.addEventListener('click', settings.testTts);
ui.els.clearAudioCache.addEventListener('click', settings.clearAudioCache);
ui.els.clearHistory.addEventListener('click', settings.clearChatHistory);
ui.els.shareDiagnostics.addEventListener('click', settings.shareDiagnostics);

ui.els.pcBtn.addEventListener('click', pc.openPc);
ui.els.pcPill.addEventListener('click', pc.openPc);
ui.els.updatePill.addEventListener('click', () => callBridge('installUpdate'));
ui.els.closePc.addEventListener('click', ui.closeSheets);
ui.els.pcBackdrop.addEventListener('click', ui.closeSheets);
ui.els.pcTest.addEventListener('click', pc.testPc);
ui.els.pcCopyKey.addEventListener('click', pc.copyPublicKey);
ui.els.pcRunCommand.addEventListener('click', pc.runPcCommand);
ui.els.tabStream.addEventListener('click', () => ui.showPage('stream'));
ui.els.tabCards.addEventListener('click', () => ui.showPage('cards'));
ui.els.cardNew.addEventListener('click', cards.newCard);
ui.els.cardBack.addEventListener('click', cards.back);
ui.els.cardFloat.addEventListener('click', cards.toggleFloat);
ui.els.cardCopyAll.addEventListener('click', cards.copyCard);
ui.els.cardEdit.addEventListener('click', cards.editCard);
ui.els.cardSave.addEventListener('click', cards.saveCard);
ui.els.cardCopy.addEventListener('click', cards.copyCard);
ui.els.cardDelete.addEventListener('click', cards.deleteCard);

playSplash();

function init() {
  state.settings = loadSettings();
  state.messages = loadHistory();
  cards.loadCards();
  ui.setReadHandler((text) => chat.speakText(text));
  ui.renderAll();
  ui.updateHint();
  ui.updateControls();
  ui.setStatus('');

  callBridge('requestMicPermission');

  try {
    const voiceNow = JSON.parse(callBridge('voiceState') || '{}');
    if (voiceNow.state) voice.applyVoiceState(voiceNow.state);
  } catch (_) {}

  if (!state.settings.apiKey) {
    setTimeout(settings.openSettings, 350);
  }

  ui.setPcConnection(callBridge('pcState'));
  ui.setUpdateState(callBridge('updateState'), callBridge('updateVersion'), 0);
}

init();
