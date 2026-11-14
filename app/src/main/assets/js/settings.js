import { callBridge } from './bridge.js';
import { clearHistory as clearStoredHistory, saveSettings as persistSettings } from './storage.js';
import { state, uid } from './state.js';
import * as ui from './ui.js';

let clearArmed = false;
let clearTimer = null;

export function openSettings() {
  ui.els.apiKeyInput.value = state.settings.apiKey || '';
  ui.els.modelSelect.value = state.settings.model;
  ui.els.voiceSelect.value = state.settings.voice;
  ui.els.autoSpeakInput.checked = !!state.settings.autoSpeak;
  ui.els.webSearchInput.checked = !!state.settings.webSearchEnabled;
  ui.els.calculatorInput.checked = !!state.settings.calculatorEnabled;
  ui.els.wakeWordInput.checked = !!state.settings.wakeWordEnabled;
  ui.els.bargeInInput.checked = state.settings.bargeInEnabled !== false;

  const raw = callBridge('getAppInfo');
  if (typeof raw === 'string' && raw) {
    try {
      const info = JSON.parse(raw);
      ui.setAppInfo(
        `版本 ${info.version} · Android ${info.android} (API ${info.sdk})\n` +
        `设备 ${info.device}\n` +
        `Key：${info.hasKey ? '已设置' : '未设置'}\n` +
        `电脑：${info.pcUrl || '未设置'}\n` +
        `音频缓存：${Number(info.cacheMb || 0).toFixed(1)} MB`
      );
    } catch (error) {
      ui.setAppInfo('');
    }
  }
  ui.openSheet(ui.els.settingsSheet, ui.els.settingsBackdrop);
}

function currentSettingsFromForm() {
  return {
    apiKey: ui.els.apiKeyInput.value.trim(),
    model: ui.els.modelSelect.value,
    voice: ui.els.voiceSelect.value,
    autoSpeak: ui.els.autoSpeakInput.checked,
    webSearchEnabled: ui.els.webSearchInput.checked,
    calculatorEnabled: ui.els.calculatorInput.checked,
    wakeWordEnabled: ui.els.wakeWordInput.checked,
    bargeInEnabled: ui.els.bargeInInput.checked,
    pcUrl: state.settings.pcUrl || '',
    pcToken: state.settings.pcToken || ''
  };
}

export function saveSettings() {
  const next = currentSettingsFromForm();
  const result = persistSettings(next);
  if (!result.ok) {
    ui.toast('保存失败');
    return;
  }
  state.settings = result.settings;
  ui.updateHint();
  callBridge('setWakeWordEnabled', next.wakeWordEnabled);
  ui.closeSheets();
  ui.toast(next.wakeWordEnabled ? '免按住模式已开启，正在加载唤醒词' : '设置已保存');
}

export function testTts() {
  const next = currentSettingsFromForm();
  const result = persistSettings(next);
  if (!result.ok) {
    ui.toast('保存失败');
    return;
  }
  state.settings = result.settings;
  ui.updateHint();
  if (state.speaking) state.ttsStopIgnore++;
  state.ttsSuppressed = true;
  state.ttsStarted = false;
  state.ttsBuffer = '';
  const id = uid();
  state.ttsId = id;
  state.speaking = true;
  ui.setStatus('播放中…');
  callBridge('speak', id, '你好，我是 DeepSeek 助手，现在正在测试朗读。');
  ui.toast('正在测试朗读，请确认媒体音量已打开');
}

export function clearAudioCache() {
  const result = callBridge('clearAudioCache');
  state.ttsSuppressed = true;
  ui.toast(typeof result === 'string' ? result : '已清理音频缓存');
}

export function clearHistory() {
  if (!clearArmed) {
    clearArmed = true;
    ui.els.clearHistory.textContent = '再点一次确认清空';
    clearTimeout(clearTimer);
    clearTimer = setTimeout(() => {
      clearArmed = false;
      ui.els.clearHistory.textContent = '清空聊天记录';
    }, 3500);
    ui.toast('再点一次即可清空本机聊天记录');
    return;
  }
  clearTimeout(clearTimer);
  clearArmed = false;
  ui.els.clearHistory.textContent = '清空聊天记录';
  clearStoredHistory();
  state.messages = [];
  ui.renderAll();
  ui.closeSheets();
  ui.toast('聊天记录已清空');
}

export function shareDiagnostics() {
  callBridge('shareDiagnostics');
}
