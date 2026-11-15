import { DEFAULT_SETTINGS } from '../features/settings/settings-store.js';

export const LONG_PRESS_MS = 360;

export const state = {
  settings: { ...DEFAULT_SETTINGS },
  messages: [],
  // Memo cards: the directory, and which one is shown.
  cards: [],
  cardName: '',
  busy: false,
  recording: false,
  speaking: false,
  micGranted: false,
  // Hands-free state as reported by the app: IDLE, PREPARING, LISTENING, TURN_ACK or REPLYING.
  voice: 'IDLE',
  recId: null,
  chatId: null,
  recStartedAt: 0,
  recTimer: null,
  assistantMsg: null,
  assistantBubble: null,
  assistantTrace: null,
  textMode: false,
  longPressTimer: null,
  longPressTriggered: false,
  micPressed: false
};

export function uid() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}
