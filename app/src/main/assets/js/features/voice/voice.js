import { callBridge } from '../../core/bridge.js';
import * as chat from '../chat/chat.js';
import { openSettings } from '../settings/settings.js';
import { LONG_PRESS_MS, state, uid } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

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
  if (state.wakeActive) endWakeConversation();
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
  if (state.wakeActive || state.followupActive) {
    endWakeConversation();
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
  state.followupActive = false;

  if (ok && text) {
    if (state.busy) {
      if (state.wakeActive) endWakeConversation();
      return;
    }
    chat.sendText(text);
    return;
  }

  ui.toast(text || '没有识别到文字');
  if (state.wakeActive) {
    endWakeConversation();
  } else {
    ui.setStatus('');
  }
}

export function onWakeWord() {
  callBridge('consumeWakePending');
  if (!state.settings.wakeWordEnabled) return;
  if (state.recording) {
    callBridge('resumeWakeWord');
    return;
  }

  if (state.busy || state.speaking) {
    chat.abandonResponseForBargeIn();
  } else {
    chat.interruptSpeech();
  }
  callBridge('pauseWakeWord');
  state.ttsStarted = false;
  state.ttsFinished = false;
  state.ttsSuppressed = false;
  state.wakeActive = true;
  state.followupActive = false;
  state.textMode = false;
  ui.showVoiceMode();
  ui.showRecordingBar('我在听');
  ui.setRecordingTime('听');
  ui.setStatus('我在听…');
  callBridge('vibrate', 30);
  state.recId = uid();
  callBridge('startHandsFree', state.recId, false);
}

export function startListeningAfterBargeIn() {
  state.wakeActive = state.settings.wakeWordEnabled;
  state.followupActive = false;
  callBridge('pauseWakeWord');
  state.recId = uid();
  ui.showVoiceMode();
  ui.showRecordingBar('我在听');
  ui.setRecordingTime('听');
  ui.setStatus('我在听…');
  callBridge('vibrate', 20);
  callBridge('startHandsFree', state.recId, false);
}

export function handleHandsFreeStart(id) {
  if (id !== state.recId) return;
  ui.showRecordingBar('我在听');
  ui.setRecordingTime('听');
  ui.setStatus('正在听…');
}

export function handleHandsFreeTimeout(id) {
  if (id !== state.recId) return;
  endWakeConversation();
}

export function handleHandsFreeError(id, message) {
  if (id !== state.recId) return;
  ui.toast(message || '免按住录音失败');
  endWakeConversation();
}

export function startFollowup() {
  if (!state.wakeActive || state.followupActive) return;
  if (state.recording || state.busy || state.speaking) return;
  if (!state.settings.wakeWordEnabled) {
    endWakeConversation();
    return;
  }
  callBridge('pauseWakeWord');
  state.followupActive = true;
  state.recId = uid();
  ui.showRecordingBar('继续说话');
  ui.setRecordingTime('听');
  ui.setStatus('继续说话…');
  callBridge('startHandsFree', state.recId, true);
}

export function endWakeConversation() {
  state.wakeActive = false;
  state.followupActive = false;
  ui.hideRecordingBar();
  callBridge('cancelHandsFree');
  callBridge('resumeWakeWord');
  ui.setStatus('');
}

export function onChatFinished() {
  if (!state.wakeActive) return;
  if (state.settings.wakeWordEnabled) callBridge('resumeWakeWord');
  if (!state.settings.autoSpeak || state.ttsSuppressed || !state.ttsStarted) {
    setTimeout(startFollowup, 400);
  }
}

export function onTtsFinished() {
  if (state.wakeActive) setTimeout(startFollowup, 250);
}

export function onChatFailed() {
  if (state.wakeActive) endWakeConversation();
}
