import { callBridge } from '../../core/bridge.js';

export const DEFAULT_SETTINGS = Object.freeze({
  apiKey: '',
  model: 'deepseek-flash',
  voice: 'aishell3-0',
  autoSpeak: true,
  pcHost: '',
  pcPort: 22,
  pcUser: '',
  webSearchEnabled: true,
  calculatorEnabled: true,
  wakeWordEnabled: false,
  bargeInEnabled: true,
  headsetMicEnabled: false,
  agentEngine: 'opencode',
  opencodeEnabled: false,
  opencodeWriteEnabled: false,
  opencodeSessionId: '',
  pcProject: '~/Reduction-iterate',
  agentDirectories: []
});

function string(value, fallback = '') {
  return typeof value === 'string' ? value : fallback;
}

export function normalizeSettings(raw) {
  return {
    apiKey: string(raw?.apiKey).trim(),
    model: string(raw?.model, 'deepseek-flash').trim() || 'deepseek-flash',
    voice: ['tide', 'aishell3-1'].includes(raw?.voice) ? 'aishell3-1' : 'aishell3-0',
    autoSpeak: raw?.autoSpeak !== false,
    pcHost: string(raw?.pcHost).trim(),
    pcPort: Math.max(1, Math.min(65535, Number(raw?.pcPort) || 22)),
    pcUser: string(raw?.pcUser).trim(),
    webSearchEnabled: raw?.webSearchEnabled !== false,
    calculatorEnabled: raw?.calculatorEnabled !== false,
    wakeWordEnabled: raw?.wakeWordEnabled === true,
    bargeInEnabled: raw?.bargeInEnabled !== false,
    headsetMicEnabled: raw?.headsetMicEnabled === true,
    agentEngine: raw?.agentEngine === 'codex' ? 'codex' : DEFAULT_SETTINGS.agentEngine,
    opencodeEnabled: raw?.opencodeEnabled === true,
    opencodeWriteEnabled: raw?.opencodeWriteEnabled === true,
    opencodeSessionId: string(raw?.opencodeSessionId).trim(),
    pcProject: string(raw?.pcProject, '~/Reduction-iterate').trim() || '~/Reduction-iterate',
    agentDirectories: Array.isArray(raw?.agentDirectories) ? raw.agentDirectories
      .filter((entry) => typeof entry?.path === 'string' && entry.path.trim())
      .map((entry) => ({ path: entry.path.trim(), writable: entry.writable === true })) : []
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
