import { callBridge } from '../../core/bridge.js';

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
  bargeInEnabled: true,
  headsetMicEnabled: false
});

function string(value, fallback = '') {
  return typeof value === 'string' ? value : fallback;
}

export function normalizeSettings(raw) {
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
    bargeInEnabled: raw?.bargeInEnabled !== false,
    headsetMicEnabled: raw?.headsetMicEnabled === true
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
