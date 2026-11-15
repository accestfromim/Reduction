import { callBridge } from '../../core/bridge.js';
import { loadHistory } from './history-store.js';
import { state, uid } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

// Draws the conversation. The app owns it: it keeps the history, builds the request and
// speaks the reply. This page is told what happens and shows it.

let renderScheduled = false;
let lastRenderAt = 0;

function scheduleAssistantRender() {
  if (renderScheduled) return;
  renderScheduled = true;
  const delay = Math.max(0, 60 - (performance.now() - lastRenderAt));
  setTimeout(() => {
    renderScheduled = false;
    lastRenderAt = performance.now();
    if (state.assistantMsg && state.assistantBubble) {
      ui.renderAssistantContent(state.assistantBubble, state.assistantMsg.content);
    }
  }, delay);
}

/** Hands what the user typed, or said into push-to-talk, to the app. The app answers with onReplyBegin. */
export function sendText(raw) {
  const text = String(raw || '').trim();
  if (!text) return;
  if (callBridge('submitText', text) !== true && state.settings.apiKey) ui.toast('消息发送失败');
}

/** A new exchange has begun, typed or spoken: draw what was said and a place for the reply. */
export function handleBegin(id, text) {
  if (state.busy) closeReply();
  state.speaking = false;
  ui.appendMessage('user', text);
  state.busy = true;
  ui.updateControls();

  const assistant = ui.appendMessage('assistant', '');
  assistant.bubble.classList.add('streaming');
  state.assistantMsg = assistant.message;
  state.assistantBubble = assistant.bubble;
  state.assistantTrace = assistant.trace;
  state.chatId = id;
  ui.setStatus('思考中…');
}

/** Ends the reply being drawn. An empty assistant bubble is removed. */
function closeReply() {
  if (state.assistantBubble) state.assistantBubble.classList.remove('streaming');
  ui.settleToolTrace(state.assistantTrace);
  if (state.assistantMsg && !state.assistantMsg.content.trim()) {
    ui.removeMessage(state.assistantMsg);
  }
  state.assistantMsg = null;
  state.assistantBubble = null;
  state.assistantTrace = null;
  state.busy = false;
  ui.updateControls();
}

function cleanLeadingNullToken(text, hasContent) {
  if (hasContent) return text;
  if (/^(?:null)+$/i.test(text)) return '';
  const match = text.match(/^(?:null)+(?=\S)/i);
  return match ? text.slice(match[0].length) : text;
}

export function handleToken(id, token) {
  if (id !== state.chatId || !state.assistantMsg) return;
  const text = cleanLeadingNullToken(token, state.assistantMsg.content.length > 0);
  if (!text) return;
  state.assistantMsg.content += text;
  scheduleAssistantRender();
  ui.setStatus(state.speaking ? '播放中…' : '回复中…');
  ui.scrollToBottom();
}

export function handleToolStarted(id, name, summary, argumentsJson) {
  if (id !== state.chatId) return;
  // What came before a tool call is a preamble; the answer starts over after the tool.
  state.speaking = false;
  if (state.assistantMsg) {
    state.assistantMsg.content = '';
    if (state.assistantBubble) state.assistantBubble.innerHTML = '';
  }
  ui.addToolTrace(state.assistantTrace, name, summary, argumentsJson);
  const label = name === 'web_search' ? '联网搜索中…' : name === 'calculator' ? '计算中…' : name === 'run_agent' ? 'sub agent 工作中…' : '使用工具…';
  ui.setStatus(label);
}

export function handleToolFinished(id, name, durationMs, success, result) {
  if (id !== state.chatId) return;
  ui.finishToolTrace(state.assistantTrace, name, durationMs, success, result);
  ui.setStatus(success ? '工具完成，继续回答…' : '工具失败，继续回答…');
}

export function handleDone(id, full) {
  if (id !== state.chatId) {
    // A reply that began before this page was showing: take it from the app's history.
    if (!state.busy) {
      state.messages = loadHistory();
      ui.renderAll();
    }
    return;
  }
  const text = cleanLeadingNullToken(String(full || ''), false);
  if (state.assistantMsg) {
    state.assistantMsg.content = text;
    if (state.assistantBubble && text) ui.renderAssistantContent(state.assistantBubble, text);
  }
  closeReply();
  if (!state.speaking) ui.setStatus('');
}

export function handleError(id, message) {
  if (id !== state.chatId) return;
  state.speaking = false;
  closeReply();
  ui.setStatus('');
  ui.toast(message || '请求失败');
}

/** The app abandoned this reply (the user interrupted it). What was drawn so far stays. */
export function handleAborted(id) {
  if (id !== state.chatId) return;
  state.speaking = false;
  state.chatId = null;
  closeReply();
}

export function handleTtsStart() {
  state.speaking = true;
  ui.setStatus('播放中…');
}

export function handleTtsDone() {
  state.speaking = false;
  if (!state.busy) ui.setStatus('');
}

export function handleTtsError(message) {
  state.speaking = false;
  if (!state.busy) ui.setStatus('');
  ui.toast(message || '朗读失败');
}

export function handleTtsStopped() {
  state.speaking = false;
  ui.setStatus('已停止朗读');
}

/** Reads a message aloud on request. */
export function speakText(text) {
  if (!String(text || '').trim()) return;
  callBridge('speak', uid(), text);
}

export function stopSpeaking() {
  callBridge('stopSpeaking');
}
