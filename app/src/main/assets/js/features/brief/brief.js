import { callBridge } from '../../core/bridge.js';
import { state } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

const STORAGE_KEY = 'dsassist_brief_v1';

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, state.brief || '');
  } catch (_) {}
}

function render() {
  const text = (state.brief || '').trim();
  ui.els.briefCard.classList.toggle('hidden', !text);
  if (!text) {
    ui.els.briefPreview.textContent = '';
    return;
  }
  ui.els.briefPreview.textContent = text.length > 260 ? text.slice(0, 260) + '…' : text;
}

export function loadBrief() {
  try {
    state.brief = localStorage.getItem(STORAGE_KEY) || '';
  } catch (_) {
    state.brief = '';
  }
  render();
}

export function updateFromTool(text) {
  state.brief = String(text || '').trim();
  persist();
  render();
  if (state.brief) ui.toast('指令已更新，可点“展开”查看或复制');
}

export function openBrief() {
  ui.els.briefText.value = state.brief || '';
  ui.openSheet(ui.els.briefSheet, ui.els.briefBackdrop);
  setTimeout(() => {
    try { ui.els.briefText.focus(); } catch (_) {}
  }, 80);
}

export function saveBrief() {
  state.brief = ui.els.briefText.value.trim();
  persist();
  render();
  ui.closeSheets();
  ui.toast(state.brief ? '指令已保存' : '指令已清空');
}

export function copyBrief() {
  const text = (state.brief || '').trim();
  if (!text) {
    ui.toast('还没有指令');
    return;
  }
  callBridge('copyText', text);
  ui.toast('指令已复制');
}

export function clearBrief() {
  state.brief = '';
  ui.els.briefText.value = '';
  persist();
  render();
  ui.closeSheets();
  ui.toast('指令已清空');
}
