/* Speech in/out for the standalone app.
 *
 *   Listening:  Android app  -> native SpeechRecognizer via window.RRNative (see android/)
 *               Chrome       -> Web Speech API (webkitSpeechRecognition)
 *   Speaking:   Android app  -> native TextToSpeech via window.RRNative
 *               Chrome       -> speechSynthesis
 *
 * Both listeners call onResult(text, isFinal) with the growing transcript of the
 * current utterance, then a final. The session turns that into timed words.
 */
(function (root) {
  'use strict';
  const native = () => root.RRNative || null;

  // Native layer calls back into these.
  const bridge = root.RRSpeech = root.RRSpeech || {};

  // ------------------------------------------------------------ listening
  class NativeListener {
    constructor() { this.name = 'android_speechrecognizer'; this.cb = null; }
    start(lang, onResult, onError, onStatus) {
      this.cb = onResult; bridge.onResult = (t, f) => this.cb && this.cb(String(t || ''), !!f);
      bridge.onError = e => onError && onError(String(e));
      bridge.onState = st => onStatus && onStatus({ state: String(st) });     // listening | idle | error:<code>
      bridge.onLevel = v => onStatus && onStatus({ level: +v });              // microphone loudness 0..1
      native().startListening(lang);
    }
    stop() { this.cb = null; try { native().stopListening(); } catch (e) {} }
  }

  class WebListener {
    constructor() { this.name = 'web_speech'; this.rec = null; this.running = false; }
    static supported() { return !!(root.SpeechRecognition || root.webkitSpeechRecognition); }
    start(lang, onResult, onError, onStatus) {
      const SR = root.SpeechRecognition || root.webkitSpeechRecognition;
      this.running = true; this.onResult = onResult; this.onError = onError;
      const status = x => onStatus && onStatus(x);
      const boot = () => {
        if (!this.running) return;
        const rec = this.rec = new SR();
        rec.lang = lang; rec.continuous = true; rec.interimResults = true; rec.maxAlternatives = 1;
        let lastFinal = '';                  // Android Chrome repeats earlier finals: emit only new text
        rec.onresult = ev => {
          let interim = '';
          for (let i = ev.resultIndex; i < ev.results.length; i++) {
            const r = ev.results[i], t = r[0].transcript.trim();
            if (r.isFinal) {
              let fresh = t;
              if (lastFinal && t.toLowerCase().startsWith(lastFinal.toLowerCase())) fresh = t.slice(lastFinal.length).trim();
              else if (lastFinal && t.toLowerCase() === lastFinal.toLowerCase()) fresh = '';
              lastFinal = t;
              if (fresh) this.onResult(fresh, true);
            } else interim += (interim ? ' ' : '') + t;
          }
          if (interim) {
            if (lastFinal && interim.toLowerCase().startsWith(lastFinal.toLowerCase())) interim = interim.slice(lastFinal.length).trim();
            if (interim) this.onResult(interim, false);
          }
        };
        rec.onerror = e => {
          if (e.error === 'not-allowed' || e.error === 'service-not-allowed') { this.running = false; this.onError && this.onError('mic-denied'); }
          else if (e.error === 'network') this.onError && this.onError('network');
          else if (e.error !== 'no-speech' && e.error !== 'aborted') this.onError && this.onError(e.error);
        };
        rec.onstart = () => status({ state: 'listening' });
        rec.onsoundstart = () => status({ level: 0.7 });
        rec.onsoundend = () => status({ level: 0 });
        rec.onend = () => { status({ state: this.running ? 'restarting' : 'idle' }); if (this.running) setTimeout(boot, 120); };   // keep listening across pauses
        try { rec.start(); } catch (e) { setTimeout(boot, 400); }
      };
      boot();
    }
    stop() { this.running = false; try { this.rec && this.rec.abort(); } catch (e) {} }
  }

  // Test double: window.__RRFake = { listen: fn(onResult) } lets automated tests "speak".
  class FakeListener {
    constructor() { this.name = 'fake'; }
    start(lang, onResult, onError, onStatus) { root.__RRFake.emit = (t, f) => onResult(t, f); onStatus && onStatus({ state: 'listening' }); root.__RRFake.level = v => onStatus && onStatus({ level: v }); }
    stop() { if (root.__RRFake) root.__RRFake.emit = () => {}; }
  }

  function makeListener() {
    if (root.__RRFake) return new FakeListener();
    if (native() && native().startListening) return new NativeListener();
    if (WebListener.supported()) return new WebListener();
    return null;
  }

  // ------------------------------------------------------------- speaking
  let seq = 0; const pending = {};
  bridge.onSpeakDone = id => { const r = pending[id]; delete pending[id]; r && r(); };

  function pickVoice(locale, name) {
    if (!root.speechSynthesis) return null;
    const vs = speechSynthesis.getVoices();
    return vs.find(v => v.name === name) || vs.find(v => v.lang === locale) || vs.find(v => v.lang && v.lang.startsWith('en-')) || null;
  }

  const TTS = {
    name() { return root.__RRFake ? 'fake' : native() && native().speak ? 'android_tts' : root.speechSynthesis ? 'web_speech' : 'none'; },
    /** Speak and resolve when finished (with a safety timeout so a flaky engine never blocks the session). */
    speak(text, opts = {}) {
      const { locale = 'en-AU', rate = 0.95, pitch = 1.15, voiceName = '' } = opts;
      const safety = 2500 + text.length * 90;
      if (root.__RRFake) return new Promise(r => setTimeout(r, root.__RRFake.speakMs ?? 300));
      if (native() && native().speak) {
        return new Promise(res => { const id = 'u' + (++seq); pending[id] = res; native().speak(text, id, locale, rate, pitch); setTimeout(() => bridge.onSpeakDone(id), safety); });
      }
      if (!root.speechSynthesis) return new Promise(r => setTimeout(r, 600));
      return new Promise(res => {
        let done = false; const finish = () => { if (!done) { done = true; res(); } };
        const u = new SpeechSynthesisUtterance(text);
        const v = pickVoice(locale, voiceName); if (v) u.voice = v;
        u.lang = locale; u.rate = rate; u.pitch = pitch; u.onend = finish; u.onerror = finish;
        speechSynthesis.cancel(); speechSynthesis.speak(u); setTimeout(finish, safety);
      });
    },
    cancel() { try { root.speechSynthesis && speechSynthesis.cancel(); } catch (e) {} try { native() && native().stopSpeaking && native().stopSpeaking(); } catch (e) {} },
    /** Must run inside a tap: mobile browsers only allow speech after a user gesture. */
    unlock() { try { if (!native() && root.speechSynthesis) { const u = new SpeechSynthesisUtterance(' '); u.volume = 0; speechSynthesis.speak(u); } } catch (e) {} },
    voices() { return root.speechSynthesis ? speechSynthesis.getVoices().filter(v => v.lang && v.lang.startsWith('en')) : []; },
  };

  root.RRSpeech.makeListener = makeListener;
  root.RRSpeech.TTS = TTS;
  root.RRSpeech.available = () => !!(root.__RRFake || (native() && native().startListening) || WebListener.supported());
})(window);
