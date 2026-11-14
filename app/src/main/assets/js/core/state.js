import { DEFAULT_SETTINGS } from '../features/settings/settings-store.js';

export const LONG_PRESS_MS = 360;

export const state = {
  settings: { ...DEFAULT_SETTINGS },
  messages: [],
  busy: false,
  recording: false,
  speaking: false,
  micGranted: false,
  recId: null,
  chatId: null,
  ttsId: null,
  recStartedAt: 0,
  recTimer: null,
  assistantMsg: null,
  assistantBubble: null,
  textMode: false,
  longPressTimer: null,
  longPressTriggered: false,
  micPressed: false,
  ttsStarted: false,
  ttsFinished: false,
  ttsSuppressed: false,
  ttsStopIgnore: 0,
  wakeActive: false,
  followupActive: false
};

export function uid() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}
