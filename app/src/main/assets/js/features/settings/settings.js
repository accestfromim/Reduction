import { callBridge } from '../../core/bridge.js';
import { clearHistory as clearStoredHistory } from '../chat/history-store.js';
import { saveSettings as persistSettings } from './settings-store.js';
import { state } from '../../core/state.js';
import { speakText } from '../chat/chat.js';
import * as ui from '../../ui/ui.js';

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
  ui.els.headsetMicInput.checked = state.settings.headsetMicEnabled === true;
  ui.els.opencodeEnabledInput.checked = state.settings.opencodeEnabled === true;
  ui.els.opencodeWriteInput.checked = state.settings.opencodeWriteEnabled === true;
  ui.els.opencodeSessionInput.value = state.settings.opencodeSessionId || '';
  ui.els.pcProjectInput.value = state.settings.pcProject || '/home/user/DSAssist-iterate';

  const raw = callBridge('getAppInfo');
  if (typeof raw === 'string' && raw) {
    try {
      const info = JSON.parse(raw);
      ui.setAppInfo(
        `版本 ${info.version} · Android ${info.android} (API ${info.sdk})\n` +
        `设备 ${info.device}\n` +
        `Key：${info.hasKey ? '已设置' : '未设置'}\n` +
        `电脑：${info.pcUser || ''}@${info.pcHost || '未设置'}:${info.pcPort || 22}\n` +
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
    headsetMicEnabled: ui.els.headsetMicInput.checked,
    opencodeEnabled: ui.els.opencodeEnabledInput.checked,
    opencodeWriteEnabled: ui.els.opencodeWriteInput.checked,
    opencodeSessionId: ui.els.opencodeSessionInput.value.trim(),
    pcProject: ui.els.pcProjectInput.value.trim() || '/home/user/DSAssist-iterate',
    pcHost: state.settings.pcHost || '',
    pcPort: state.settings.pcPort || 22,
    pcUser: state.settings.pcUser || ''
  };
}

export function saveSettingsForm() {
  const next = currentSettingsFromForm();
  const result = persistSettings(next);
  if (!result.ok) {
    ui.toast('保存失败');
    return;
  }
  state.settings = result.settings;
  ui.updateHint();
  callBridge('setWakeWordEnabled', next.wakeWordEnabled);
  if (next.headsetMicEnabled) callBridge('requestBluetoothPermission');
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
  speakText('你好，我是 Entropy，现在正在测试朗读。');
  ui.toast('正在测试朗读，请确认媒体音量已打开');
}

export function clearAudioCache() {
  const result = callBridge('clearAudioCache');
  ui.toast(typeof result === 'string' ? result : '已清理音频缓存');
}

export function clearChatHistory() {
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
