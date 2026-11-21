import { callBridge } from '../../core/bridge.js';
import * as chat from '../chat/chat.js';
import { openSettings } from '../settings/settings.js';
import { LONG_PRESS_MS, state, uid } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

// ── Hands-free: the app owns the state machine; this only draws it. ─────────

const LISTENING_HINT = '说完请说：完毕';

export function isHandsFree() {
  return state.voice !== 'IDLE';
}

export function applyVoiceState(name) {
  const previous = state.voice;
  state.voice = name;

  if (name === 'PREPARING' || name === 'LISTENING') {
    ui.showVoiceMode();
    ui.showRecordingBar(LISTENING_HINT);
    ui.setRecordingTime('听');
  } else if (previous === 'PREPARING' || previous === 'LISTENING') {
    ui.hideRecordingBar();
  }

  if (name === 'PREPARING') {
    callBridge('vibrate', 30);
    ui.setStatus('准备中…');
  } else if (name === 'LISTENING') {
    ui.setStatus('正在听…');
  } else if (name === 'TURN_ACK') {
    ui.setStatus('好的');
  } else if (name === 'IDLE' && previous !== 'IDLE' && !state.busy && !state.speaking) {
    ui.setStatus('');
  }
}

export function showPartial(text) {
  if (state.voice === 'LISTENING') ui.setStatus(text || '正在听…');
}

export function showVoiceError(message) {
  ui.toast(message || '免按住录音失败');
}

export function finishTurn() {
  callBridge('voiceFinish');
}

export function leaveHandsFree() {
  callBridge('voiceCancel');
}

// ── Push-to-talk ────────────────────────────────────────────────────────────

function formatElapsed() {
  const seconds = Math.max(0, Math.floor((Date.now() - state.recStartedAt) / 1000));
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  ui.setRecordingTime(`${mm}:${ss}`);
}

export function handleMicPermission(granted) {
  state.micGranted = !!granted;
  ui.updateHint();
  if (granted) ui.toast('麦克风已就绪');
}

export function onMicDown(event) {
  if (event) event.preventDefault();
  if (state.recording) return;
  if (isHandsFree()) leaveHandsFree();
  if (state.speaking) chat.stopSpeaking();

  state.micPressed = true;
  state.longPressTriggered = false;
  clearTimeout(state.longPressTimer);
  state.longPressTimer = setTimeout(() => {
    state.longPressTriggered = true;
    startRecording();
  }, LONG_PRESS_MS);
}

export function onMicUp(event) {
  if (!state.micPressed) return;
  state.micPressed = false;
  if (event) event.preventDefault();
  clearTimeout(state.longPressTimer);
  state.longPressTimer = null;

  if (state.longPressTriggered) {
    state.longPressTriggered = false;
    if (state.recording) endRecording(event);
  } else {
    ui.showTextMode();
  }
}

export function onMicCancel() {
  if (!state.micPressed && !state.recording) return;
  if (isHandsFree()) return;
  state.micPressed = false;
  clearTimeout(state.longPressTimer);
  state.longPressTimer = null;
  state.longPressTriggered = false;
  if (state.recording) cancelRecording();
}

export function startRecording() {
  if (state.busy) {
    ui.toast('请等待当前回复完成');
    return;
  }
  if (!state.settings.apiKey) {
    ui.toast('请先填写 DeepSeek API Key');
    openSettings();
    return;
  }
  if (!state.micGranted) {
    callBridge('requestMicPermission');
    ui.toast('请允许麦克风权限');
    return;
  }

  state.recId = uid();
  state.recStartedAt = Date.now();
  ui.showRecordingBar('松开发送');
  ui.setRecordingTime('00:00');
  clearInterval(state.recTimer);
  state.recTimer = setInterval(formatElapsed, 200);
  ui.setStatus('正在录音…');
  callBridge('vibrate', 18);
  callBridge('startRecording', state.recId);
}

export function endRecording(event) {
  if (event) event.preventDefault();
  if (!state.recording) return;
  ui.hideRecordingBar();
  ui.setStatus('识别中…');
  callBridge('stopAndTranscribe', state.recId);
}

export function cancelRecording() {
  if (isHandsFree()) {
    leaveHandsFree();
    return;
  }
  state.micPressed = false;
  clearTimeout(state.longPressTimer);
  state.longPressTimer = null;
  state.longPressTriggered = false;
  ui.hideRecordingBar();
  callBridge('cancelRecording');
  ui.setStatus('已取消');
}

export function handleRecordingStart(id) {
  if (id !== state.recId) return;
  ui.showRecordingBar('松开发送');
  ui.setStatus('正在录音…');
}

export function handleRecordingLimit(id) {
  if (id !== state.recId) return;
  ui.hideRecordingBar();
  ui.setStatus('识别中…');
}

export function handleAsrResult(id, ok, text) {
  if (id !== state.recId) return;
  ui.hideRecordingBar();
  if (ok && text) {
    chat.sendText(text, id);
    return;
  }
  ui.toast(text || '没有识别到文字');
  ui.setStatus('');
}
