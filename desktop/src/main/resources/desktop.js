// The computer's stand-in for the Android app. The page calls `window.Android` exactly as
// it does on the phone; here those calls go to the local server, and the server's events
// come back over one stream. Sound and microphone are this browser's.
(() => {
  const post = (path, body, type) => fetch(path, { method: 'POST', headers: { 'X-Reduction': '1', 'Content-Type': type || 'application/json' }, body });

  function call(method, args) {
    const request = new XMLHttpRequest();
    request.open('POST', '/bridge/' + method, false);
    request.setRequestHeader('X-Reduction', '1');
    request.send(JSON.stringify(args));
    if (request.status !== 200) return undefined;
    const result = JSON.parse(request.responseText).result;
    return result === null ? undefined : result;
  }

  const native = (fn, ...args) => {
    if (window.Native && typeof window.Native[fn] === 'function') window.Native[fn](...args);
  };

  // ── the window: its edges drag it to another size, and a button gives it the whole screen ─────
  // Only where the page is the pet's panel, which has no frame of its own; in a browser the browser has one.
  const host = window.chrome && window.chrome.webview;
  if (host) {
    window.addEventListener('DOMContentLoaded', () => {
      document.documentElement.classList.add('in-panel');
      for (const edge of ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw']) {
        const grip = document.createElement('div');
        grip.className = 'window-grip grip-' + edge;
        grip.addEventListener('pointerdown', (event) => {
          if (event.button !== 0) return;
          event.preventDefault();
          host.postMessage('resize:' + edge);
        });
        document.body.appendChild(grip);
      }
      const button = document.createElement('button');
      button.className = 'icon-btn window-full';
      button.setAttribute('aria-label', '全屏');
      const draw = (full) => {
        button.title = full ? '回到卡片大小（F11）' : '全屏（F11）';
        button.innerHTML = full
          ? '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/></svg>'
          : '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>';
        document.documentElement.classList.toggle('panel-full', full);
      };
      draw(false);
      button.addEventListener('click', () => host.postMessage('fullscreen'));
      host.addEventListener('message', (event) => draw(event.data === 'full'));
      const actions = document.querySelector('.header-actions') || document.querySelector('.topbar-right');
      if (actions) actions.insertBefore(button, actions.firstChild);
    });
  }

  // ── sound: PCM pieces played one after another ──────────────────────────
  const RATE = 24000;
  let audio = null;
  let playUntil = 0;
  let sources = [];
  const context = () => {
    if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: RATE });
    if (audio.state === 'suspended') audio.resume();
    return audio;
  };
  // Browsers let a page make sound only after the user has touched it.
  for (const type of ['pointerdown', 'keydown']) window.addEventListener(type, () => context(), { capture: true });

  function play(base64) {
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const samples = new Int16Array(bytes.buffer, 0, bytes.length >> 1);
    if (!samples.length) return;
    const ctx = context();
    const buffer = ctx.createBuffer(1, samples.length, RATE);
    const channel = buffer.getChannelData(0);
    for (let i = 0; i < samples.length; i++) channel[i] = samples[i] / 32768;
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);
    const at = Math.max(ctx.currentTime + 0.03, playUntil);
    source.start(at);
    playUntil = at + buffer.duration;
    sources.push(source);
    source.onended = () => { sources = sources.filter((item) => item !== source); };
  }

  function stopSound() {
    for (const source of sources) { try { source.stop(); } catch (_) {} }
    sources = [];
    playUntil = 0;
  }

  /** Tells the server once everything of a stretch of speech has been heard. */
  function whenPlayed(session) {
    const wait = audio ? Math.max(0, playUntil - audio.currentTime) : 0;
    setTimeout(() => post('/audio-done?session=' + session, '{}'), wait * 1000 + 60);
  }

  // ── microphone: hold the button to talk ─────────────────────────────────
  let recording = null;

  async function startRecording(id) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      const source = ctx.createMediaStreamSource(stream);
      const tap = ctx.createScriptProcessor(4096, 1, 1);
      const chunks = [];
      tap.onaudioprocess = (event) => chunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
      source.connect(tap);
      tap.connect(ctx.destination);
      recording = { id, stream, ctx, chunks };
      native('onRecordingStart', id);
    } catch (error) {
      native('onAsrResult', id, false, '用不了麦克风：' + (error && error.message ? error.message : '浏览器没有授权'));
    }
  }

  function endRecording() {
    const done = recording;
    recording = null;
    if (!done) return null;
    done.stream.getTracks().forEach((track) => track.stop());
    const rate = done.ctx.sampleRate;
    done.ctx.close();
    const length = done.chunks.reduce((sum, chunk) => sum + chunk.length, 0);
    const all = new Float32Array(length);
    let at = 0;
    for (const chunk of done.chunks) { all.set(chunk, at); at += chunk.length; }
    return { id: done.id, wav: wav16k(all, rate) };
  }

  /** Mono 16 kHz 16-bit WAV, which is what speech recognition is given on the phone too. */
  function wav16k(samples, rate) {
    const count = Math.floor(samples.length * 16000 / rate);
    const view = new DataView(new ArrayBuffer(44 + count * 2));
    const text = (offset, value) => { for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i)); };
    text(0, 'RIFF'); view.setUint32(4, 36 + count * 2, true); text(8, 'WAVE'); text(12, 'fmt ');
    view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, 16000, true); view.setUint32(28, 32000, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    text(36, 'data'); view.setUint32(40, count * 2, true);
    const step = rate / 16000;
    for (let i = 0; i < count; i++) {
      // Average the source samples that fall into this one: a plain low-pass, enough for speech.
      const from = Math.floor(i * step);
      const to = Math.max(from + 1, Math.floor((i + 1) * step));
      let sum = 0;
      for (let j = from; j < to; j++) sum += samples[j] || 0;
      view.setInt16(44 + i * 2, Math.max(-1, Math.min(1, sum / (to - from))) * 32767, true);
    }
    return view.buffer;
  }

  // ── what this browser does itself, instead of asking the server ─────────
  // When the computer has its own loudspeakers and microphone in hand, they are the server's business.
  const ownAudio = call('nativeAudio', []) !== true;
  const here = {
    ...(ownAudio ? {
      requestMicPermission: () => setTimeout(() => native('onMicPermission', !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)), 0),
      startRecording: (id) => { startRecording(id); },
      stopAndTranscribe: (id) => {
        const done = endRecording();
        if (!done || done.wav.byteLength < 44 + 3200) return native('onAsrResult', id, false, '没有录到声音，请再试一次');
        post('/asr?id=' + encodeURIComponent(id), done.wav, 'audio/wav');
      },
      cancelRecording: () => { endRecording(); },
      stopSpeaking: () => { stopSound(); call('stopSpeaking', []); }
    } : {}),
    copyText: (text) => { if (navigator.clipboard) navigator.clipboard.writeText(String(text)); },
    vibrate: () => {},
    requestBluetoothPermission: () => {},
    shareDiagnostics: () => {},
    checkUpdate: () => {},
    installUpdate: () => {},
    pcConnect: () => {},
    voiceFinish: () => {},
    voiceCancel: () => {}
  };

  window.Android = new Proxy({}, { get: (_, method) => here[method] || ((...args) => call(method, args)) });

  // ── what the server tells this page ─────────────────────────────────────
  function listen() {
    const events = new EventSource('/events');
    events.onmessage = (message) => {
      const event = JSON.parse(message.data);
      if (event.fn) return native(event.fn, ...event.args);
      if (event.signal === 'audio') play(event.pcm);
      else if (event.signal === 'audioEnd') whenPlayed(event.session);
      else if (event.signal === 'audioStop') stopSound();
      else if (event.signal === 'approval') {
        const allowed = window.confirm(event.title + '\n\n' + event.message);
        post('/approve', JSON.stringify({ id: event.id, allowed }));
      }
    };
  }
  listen();
})();
