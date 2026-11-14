import { callBridge } from '../../core/bridge.js';

export function loadHistory() {
  const raw = callBridge('loadHistory');
  if (typeof raw !== 'string' || !raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((item) => item && (item.role === 'user' || item.role === 'assistant'))
      .filter((item) => typeof item.content === 'string')
      .map((item) => item.role === 'assistant'
        ? { ...item, content: item.content.replace(/^(?:null){2,}(?=\S)/i, '') }
        : item)
      .slice(-100);
  } catch (error) {
    console.warn('load history failed', error);
    return [];
  }
}

export function saveHistory(messages) {
  return callBridge('saveHistory', JSON.stringify(messages.slice(-100))) === true;
}

export function clearHistory() {
  callBridge('clearHistory');
}
