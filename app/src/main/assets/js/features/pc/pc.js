import { callBridge } from '../../core/bridge.js';
import { saveSettings } from '../settings/settings-store.js';
import { state, uid } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

let pcRequestId = null;
let commandArmed = false;
let commandTimer = null;

export function openPc() {
  ui.els.pcUrlInput.value = state.settings.pcUrl || '';
  ui.els.pcTokenInput.value = state.settings.pcToken || '';
  ui.openSheet(ui.els.pcSheet, ui.els.pcBackdrop);
}

function savePcFields() {
  const next = {
    ...state.settings,
    pcUrl: ui.els.pcUrlInput.value.trim(),
    pcToken: ui.els.pcTokenInput.value.trim()
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
  if (!state.settings.pcUrl) {
    ui.toast('请先填写电脑地址');
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
  if (!state.settings.pcUrl) {
    ui.toast('请先填写电脑地址');
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

export function checkUpdate() {
  if (!savePcFields()) return;
  if (!state.settings.pcUrl) {
    ui.toast('请先填写电脑地址');
    return;
  }
  pcRequestId = uid();
  ui.setPcStatus('检查更新…');
  ui.setPcOutput('');
  callBridge('pcCheckUpdate', pcRequestId);
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
      `地址：${Array.isArray(info.ips) ? info.ips.join(', ') : ''}\n` +
      `时间：${info.time || ''}`
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

export function handleUpdateStatus(id, text) {
  if (id !== pcRequestId) return;
  ui.setPcStatus(text);
}

export function handleUpdateNone(id, version) {
  if (id !== pcRequestId) return;
  ui.setPcStatus(`已是最新版本 ${version}`, 'ok');
  ui.toast('已是最新版本');
}

export function handleUpdateReady(id, version) {
  if (id !== pcRequestId) return;
  ui.setPcStatus(`更新 ${version} 已下载，请在系统弹窗里点安装`, 'ok');
  ui.toast('更新已下载，请在系统弹窗里点安装');
}

export function handleUpdateError(id, message) {
  if (id !== pcRequestId) return;
  ui.setPcStatus(message || '更新失败', 'err');
  ui.toast(message || '更新失败');
}
