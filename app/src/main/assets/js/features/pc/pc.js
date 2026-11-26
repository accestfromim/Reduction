import { callBridge } from '../../core/bridge.js';
import { saveSettings } from '../settings/settings-store.js';
import { state, uid } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

let pcRequestId = null;
let commandArmed = false;
let commandTimer = null;

let pcStatusTimer = null;

function refreshPcConnection() {
  const connected = callBridge('pcConnected') === true;
  ui.setPcStatus(connected ? '已连接' : '自动连接中…', connected ? 'ok' : undefined);
}

export function openPc() {
  ui.els.pcHostInput.value = state.settings.pcHost || '';
  ui.els.pcPortInput.value = state.settings.pcPort || 22;
  ui.els.pcUserInput.value = state.settings.pcUser || '';
  ui.els.pcProxyJumpInput.value = state.settings.pcProxyJump || '';
  ui.openSheet(ui.els.pcSheet, ui.els.pcBackdrop);
  callBridge('pcConnect');
  refreshPcConnection();
  clearTimeout(pcStatusTimer);
  for (const delay of [1500, 4000, 8000]) {
    setTimeout(refreshPcConnection, delay);
  }
}

function savePcFields() {
  const next = {
    ...state.settings,
    pcHost: ui.els.pcHostInput.value.trim(),
    pcPort: Math.max(1, Math.min(65535, Number(ui.els.pcPortInput.value) || 22)),
    pcUser: ui.els.pcUserInput.value.trim(),
    pcProxyJump: ui.els.pcProxyJumpInput.value.trim()
  };
  const result = saveSettings(next);
  if (!result.ok) {
    ui.toast('保存电脑设置失败');
    return false;
  }
  state.settings = result.settings;
  return true;
}

export function testPc() {
  if (!savePcFields()) return;
  if (!state.settings.pcHost || !state.settings.pcUser) {
    ui.toast('请填写主机和 SSH 用户');
    return;
  }
  ui.setPcStatus('连接中…');
  ui.setPcOutput('');
  pcRequestId = uid();
  callBridge('pcInfo', pcRequestId);
}

export function runPcCommand() {
  if (!savePcFields()) return;
  const command = ui.els.pcCommandInput.value.trim();
  if (!command) {
    ui.toast('请输入要执行的命令');
    return;
  }
  if (!state.settings.pcHost || !state.settings.pcUser) {
    ui.toast('请填写主机和 SSH 用户');
    return;
  }
  if (!commandArmed) {
    commandArmed = true;
    ui.els.pcRunCommand.textContent = '再点一次执行';
    clearTimeout(commandTimer);
    commandTimer = setTimeout(() => {
      commandArmed = false;
      ui.els.pcRunCommand.textContent = '执行命令';
    }, 4000);
    ui.toast('再点一次确认在电脑上执行');
    return;
  }
  clearTimeout(commandTimer);
  commandArmed = false;
  ui.els.pcRunCommand.textContent = '执行命令';
  pcRequestId = uid();
  ui.setPcStatus('执行中…');
  ui.setPcOutput('');
  callBridge('pcExec', pcRequestId, command);
}

export function copyPublicKey() {
  const key = callBridge('pcPublicKey');
  if (typeof key !== 'string' || !key) {
    ui.toast('生成公钥失败');
    return;
  }
  callBridge('copyText', key);
  ui.setPcOutput(key);
  ui.toast('公钥已复制');
}

export function handlePcInfo(id, json) {
  if (id !== pcRequestId) return;
  try {
    const info = JSON.parse(json);
    ui.setPcStatus(`已连接：${info.hostname || '电脑'}`, 'ok');
    ui.setPcOutput(
      `主机：${info.hostname || ''}\n` +
      `系统：${info.os || ''}\n` +
      `用户：${info.user || ''}\n` +
      `时间：${info.time || ''}\n` +
      `Host key：${info.hostKey || ''}`
    );
    ui.toast('电脑连接成功');
  } catch (error) {
    ui.setPcStatus('返回格式异常', 'err');
  }
}

export function handlePcStatus(id, text) {
  if (id !== pcRequestId) return;
  ui.setPcStatus(text);
}

export function handlePcExec(id, output) {
  if (id !== pcRequestId) return;
  ui.setPcStatus('命令执行完成', 'ok');
  ui.setPcOutput(output || '（无输出）');
  ui.toast('命令执行完成');
}

export function handlePcError(id, message) {
  if (id !== pcRequestId) return;
  ui.setPcStatus(message || '电脑操作失败', 'err');
  ui.setPcOutput(message || '');
  ui.toast(message || '电脑操作失败');
}
