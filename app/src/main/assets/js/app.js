import { callBridge } from './core/bridge.js';
import * as chat from './features/chat/chat.js';
import * as pc from './features/pc/pc.js';
import * as settings from './features/settings/settings.js';
import { state } from './core/state.js';
import { loadHistory } from './features/chat/history-store.js';
import { loadSettings } from './features/settings/settings-store.js';
import * as ui from './ui/ui.js';
import * as voice from './features/voice/voice.js';

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

  onHandsFreeStart(id) {
    voice.handleHandsFreeStart(id);
  },

  onHandsFreeTimeout(id) {
    voice.handleHandsFreeTimeout(id);
  },

  onHandsFreeError(id, message) {
    voice.handleHandsFreeError(id, message);
  },

  onWakeWord() {
    voice.onWakeWord();
  },

  onStatus(id, status) {
    if (status === '识别中') ui.setStatus('识别中…');
    else if (status === '思考中') ui.setStatus('思考中…');
    else ui.setStatus(status);
  },

  onChatToken(id, token) {
    chat.handleToken(id, token);
  },

  onTool(id, name) {
    chat.handleTool(id, name);
  },

  onChatDone(id, full) {
    if (chat.handleDone(id, full)) voice.onChatFinished();
  },

  onChatError(id, message) {
    if (chat.handleError(id, message)) voice.onChatFailed();
  },

  onTtsStart(id) {
    chat.handleTtsStart(id);
  },

  onTtsDone(id) {
    if (chat.handleTtsDone(id)) voice.onTtsFinished();
  },

  onTtsError(id, message) {
    if (chat.handleTtsError(id, message)) voice.onTtsFinished();
  },

  onTtsStopped() {
    if (chat.handleTtsStopped()) voice.onTtsFinished();
  },

  onBargeIn() {
    chat.abandonResponseForBargeIn();
    voice.startListeningAfterBargeIn();
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
  if (state.wakeActive || state.followupActive) {
    ui.setStatus('识别中…');
    callBridge('finishHandsFree');
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
ui.els.closePc.addEventListener('click', ui.closeSheets);
ui.els.pcBackdrop.addEventListener('click', ui.closeSheets);
ui.els.pcTest.addEventListener('click', pc.testPc);
ui.els.pcCopyKey.addEventListener('click', pc.copyPublicKey);
ui.els.pcRunCommand.addEventListener('click', pc.runPcCommand);

function init() {
  state.settings = loadSettings();
  state.messages = loadHistory();
  ui.setReadHandler((text) => chat.speakText(text, true));
  ui.renderAll();
  ui.updateHint();
  ui.updateControls();
  ui.setStatus('');

  callBridge('requestMicPermission');

  if (callBridge('consumeWakePending') === true) {
    setTimeout(() => window.Native.onWakeWord(), 0);
  }

  if (!state.settings.apiKey) {
    setTimeout(settings.openSettings, 350);
  }
}

init();
