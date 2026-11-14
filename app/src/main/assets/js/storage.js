import { callBridge } from './bridge.js';

export const DEFAULT_SETTINGS = Object.freeze({
  apiKey: '',
  model: 'deepseek-flash',
  voice: 'stella',
  autoSpeak: true,
  pcUrl: '',
  pcToken: '',
  webSearchEnabled: true,
  calculatorEnabled: true,
  wakeWordEnabled: false,
  bargeInEnabled: true
});

function string(value, fallback = '') {
  return typeof value === 'string' ? value : fallback;
}

function normalizeSettings(raw) {
  return {
    apiKey: string(raw?.apiKey).trim(),
    model: string(raw?.model, 'deepseek-flash').trim() || 'deepseek-flash',
    voice: raw?.voice === 'tide' ? 'tide' : 'stella',
    autoSpeak: raw?.autoSpeak !== false,
    pcUrl: string(raw?.pcUrl).trim(),
    pcToken: string(raw?.pcToken).trim(),
    webSearchEnabled: raw?.webSearchEnabled !== false,
    calculatorEnabled: raw?.calculatorEnabled !== false,
    wakeWordEnabled: raw?.wakeWordEnabled === true,
    bargeInEnabled: raw?.bargeInEnabled !== false
  };
}

export function loadSettings() {
  const raw = callBridge('loadSettings');
  if (typeof raw !== 'string' || !raw) return { ...DEFAULT_SETTINGS };
  try {
    return normalizeSettings(JSON.parse(raw));
  } catch (error) {
    console.warn('load settings failed', error);
    return { ...DEFAULT_SETTINGS };
  }
}

export function saveSettings(settings) {
  const normalized = normalizeSettings(settings);
  const saved = callBridge('saveSettings', JSON.stringify(normalized));
  return { ok: saved === true, settings: normalized };
}

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
