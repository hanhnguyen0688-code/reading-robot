/* On-device Reading Robot session: the same flow as the LiveKit agent
 * (reading_robot/agent.py), running entirely on the tablet.
 *
 *   greet  -> "Hi Cathy! Ready to read to me?"   ("Ready!" / tap / raise-hand button)
 *   reading-> passage on screen, robot listens, never chats; Help -> robot reads the word
 *   finish -> "Great work Cathy. I'll send some key information to your teacher..."
 *
 * Emits the same UI events as the server agent, so one renderer serves both.
 */
(function (root) {
  'use strict';
  const E = root.RREngine, S = root.RRSpeech;

  const L = {
    greeting: n => `Hi ${n}! Ready to read to me? I polished my ears just for you!`,
    start: "Start reading out loud whenever you're ready! I'm all ears... well, all microphones.",
    closing: n => `Great work ${n}. I'll send some key information to your teacher so they know how you went. You can head back to your desk now.`,
    notMe: 'Oops, sorry! Please ask your teacher to send the right reader to me.',
    retry: "When you're ready, just say Ready, or tap the button!",
    stall: ['Take your time. If a word is tricky, just say Help!', "You're doing great. Keep going, or say Help if you're stuck."],
    told: w => `That word is ${w}.`,
    cantHear: "Hmm, I can't hear you yet. Read a little louder, or tap my ear and try again.",
  };
  const READY = new Set(['ready', 'yes', 'yeah', 'yep', 'ok', 'okay', 'start', 'go']);
  const NOT_ME = ['not me', 'thats not me', 'wrong name', 'im not'];
  const EST = 0.32;   // estimated seconds per word when the recogniser gives no timing

  class Session {
    constructor({ student, passage, settings, emit, onDone }) {
      this.student = student; this.raw = passage; this.settings = settings; this.emit = emit; this.onDone = onDone;
      this.cfg = Object.assign(E.defaultConfig(), { showErrorsLive: !!settings.showErrorsLive, hesitationSeconds: +settings.hesitationSeconds || 3 });
      this.passage = E.buildPassage(passage);
      this.phase = 'idle'; this.speaking = false; this.muteUntil = 0;
      this.listener = S.makeListener();
      this.finals = []; this.tailSeen = []; this.t0 = 0;
      this.timers = [];
    }
    now() { return performance.now() / 1000 - this.t0; }
    voice() { const s = this.settings; return { locale: s.locale, rate: +s.speechRate || 0.95, pitch: +s.pitch || 1.15, voiceName: s.voiceName }; }

    async say(text, extra = {}) {
      this.emit({ type: 'robot_says', text, ...extra });
      this.speaking = true;
      try { await S.TTS.speak(text, this.voice()); } finally { this.speaking = false; this.muteUntil = performance.now() + 450; }
    }
    muted() { return this.speaking || performance.now() < this.muteUntil; }

    // ------------------------------------------------------------ 1. greet
    async start() {
      if (!this.listener) { this.emit({ type: 'error', text: 'Speech recognition is not available on this device.' }); return; }
      this.phase = 'greet';
      this.emit({ type: 'state', state: 'greet', student: this.student });
      this.startListening();
      await this.say(L.greeting(this.student.name));
      this.later(() => { if (this.phase === 'greet') this.say(L.retry); }, 20000);
      this.later(() => { if (this.phase === 'greet') this.end('no_response'); }, 60000);
    }

    startListening() {
      this.heardAny = false;
      this.listener.start(this.settings.locale || 'en-AU', (t, f) => this.onSpeech(t, f), e => this.onSpeechError(e), st => this.onMicStatus(st));
    }
    /** Tap on the ear: restart the recogniser (it can get stuck on some tablets). */
    restartListening() {
      if (!this.listener || this.phase === 'finish' || this.phase === 'ended') return;
      this.emit({ type: 'mic', state: 'starting' });
      this.listener.stop();
      setTimeout(() => { if (this.phase === 'greet' || this.phase === 'reading' || this.phase === 'starting') this.startListening(); }, 350);
    }
    onMicStatus(st) { this.emit({ type: 'mic', ...st }); }

    onSpeechError(e) {
      const msg = {
        'mic-denied': 'Microphone permission was denied. Allow the microphone for Reading Robot in Android Settings, then try again.',
        'no-recognizer': 'This tablet has no speech recognition service. Install or update the Google app (and Speech Services by Google), then try again.',
        'network': 'Speech recognition needs an internet connection. Check the Wi-Fi, then tap the ear to listen again.',
      }[e];
      this.emit({ type: 'mic', state: 'error:' + e });
      if (e === 'network') { this.emit({ type: 'robot_says', text: "I can't hear you without the internet. Check the Wi-Fi, then tap my ear." }); return; }
      if (msg) this.emit({ type: 'error', text: msg });
    }

    onSpeech(text, isFinal) {
      if (this.muted() || this.phase === 'finish' || this.phase === 'idle') return;
      const words = E.splitHypothesis(text);
      if (words.length) { this.heardAny = true; this.emit({ type: 'heard', text, final: isFinal }); }
      if (this.phase === 'greet') {
        // The robot's own greeting ("Ready to read to me?") can reach the mic late: ignore it.
        const low = text.toLowerCase();
        if (low.includes('read to me') || low.includes('polished') || words.length > 6) return;
        if (!isFinal && !words.some(w => READY.has(w))) return;
        this.emit({ type: 'user_says', text });
        const flat = text.toLowerCase().replace(/['’]/g, '');
        if (NOT_ME.some(p => flat.includes(p))) return this.notMe();
        if (words.some(w => READY.has(w))) return this.startReading();
        return;
      }
      if (this.phase === 'reading') {
        this.lastSpeechAt = performance.now();
        if (words.includes('help') && words.length <= 3) this.help();
        this.feed(text, isFinal);
      }
    }

    rpc(method) {
      if (method === 'start_reading' && this.phase === 'greet') return this.startReading();
      if (method === 'not_me' && this.phase === 'greet') return this.notMe();
      if (method === 'help' && this.phase === 'reading') return this.help();
      if (method === 'finish' && this.phase === 'reading') return this.finish('manual');
      if (method === 'listen') return this.restartListening();
    }

    async notMe() {
      this.phase = 'not_me';
      this.emit({ type: 'state', state: 'not_me' });
      await this.say(L.notMe);
      this.later(() => this.end('wrong_student'), 2500);
    }

    // ----------------------------------------------------- 2 + 3. reading
    async startReading() {
      if (this.phase !== 'greet') return;
      this.phase = 'starting';
      this.clearTimers();
      this.emit({ type: 'state', state: 'reading' });
      this.emit(passagePayload(this.passage));
      await this.say(L.start);
      this.tracker = new E.LiveTracker(this.passage, this.cfg);
      this.finals = []; this.tailSeen = []; this.t0 = performance.now() / 1000;
      this.startedAt = this.lastSpeechAt = performance.now(); this.stallPrompts = 0; this.lastHelpAt = 0; this.endReachedAt = null;
      this.phase = 'reading';
      this.watch = setInterval(() => this.watchdog(), 500);
    }

    feed(transcript, isFinal) {
      const t = this.now(), ws = E.splitHypothesis(transcript);
      while (this.tailSeen.length < ws.length) this.tailSeen.push(t);
      let lastEnd = this.finals.length ? this.finals[this.finals.length - 1].end : 0;
      const tail = ws.map((n, k) => {
        let start = this.tailSeen[k]; if (start < lastEnd) start = lastEnd;
        const h = { norm: n, start, end: start + EST, accuracy: null }; lastEnd = h.end; return h;
      });
      let hyp;
      if (isFinal) { this.finals.push(...tail); this.tailSeen = []; hyp = this.finals.slice(); }
      else hyp = this.finals.concat(tail);
      const ev = this.tracker.update(hyp, performance.now() / 1000);
      this.emit(ev);
      if (this.tracker.reachedEnd() && this.endReachedAt == null) this.endReachedAt = performance.now();
    }

    async help() {
      const nowMs = performance.now();
      if (this.phase !== 'reading' || nowMs - this.lastHelpAt < this.cfg.helpDebounceSeconds * 1000) return;
      const idx = this.tracker.state.cursor + 1;
      if (idx >= this.passage.wordCount || this.passage.words[idx].norm === 'help') return;
      this.lastHelpAt = nowMs;
      this.tracker.markTold(idx);
      this.emit(this.tracker.refresh());
      await this.say(L.told(this.passage.words[idx].norm), { told_index: idx });
    }

    watchdog() {
      if (this.phase !== 'reading' || this.speaking) return;
      const cfg = this.cfg, nowMs = performance.now();
      const idle = (nowMs - Math.max(this.tracker.lastProgressAt * 1000, this.startedAt)) / 1000;
      const silent = (nowMs - this.lastSpeechAt) / 1000;
      const ratio = (this.tracker.state.cursor + 1) / this.passage.wordCount;
      if (this.endReachedAt && nowMs - this.endReachedAt >= cfg.finishGraceSeconds * 1000) return this.finish('completed');
      if (ratio >= cfg.earlyFinishRatio && silent >= cfg.earlyFinishSilence) return this.finish('stopped_near_end');
      if ((nowMs - this.startedAt) / 1000 >= cfg.maxSessionSeconds) return this.finish('time_limit');
      if (idle >= cfg.stallFinishSeconds && silent >= cfg.stallFinishSeconds) return this.finish('stalled');
      if (idle >= cfg.stallPromptSeconds * (this.stallPrompts + 1) && this.stallPrompts < L.stall.length) {
        this.say(this.heardAny ? L.stall[this.stallPrompts] : L.cantHear);
        this.stallPrompts++;
      }
    }

    // ---------------------------------------------------------- 4. finish
    async finish(reason) {
      if (this.phase === 'finish') return;
      this.phase = 'finish'; clearInterval(this.watch); this.listener && this.listener.stop();
      const report = E.buildReport(this.tracker, { student: this.student, finishedReason: reason,
        sessionId: `${this.student.id}-${Date.now().toString(36)}`, asrBackend: this.listener ? this.listener.name : '' });
      this.emit({ type: 'state', state: 'finish' });
      this.emit({ type: 'report', report });
      const saved = root.RRStore.addReport(report);
      await this.say(L.closing(this.student.name));
      this.emit({ type: 'report_sent', teacher: this.student.teacher || 'Teacher', saved, teacher_note: report.teacher_note });
      this.later(() => this.end('complete'), 8000);
      return report;
    }

    end(reason) {
      if (this.phase === 'ended') return;
      this.phase = 'ended'; this.clearTimers(); clearInterval(this.watch);
      this.listener && this.listener.stop(); S.TTS.cancel();
      this.emit({ type: 'state', state: 'sleep' });
      this.onDone && this.onDone(reason);
    }
    later(fn, ms) { this.timers.push(setTimeout(fn, ms)); }
    clearTimers() { this.timers.forEach(clearTimeout); this.timers = []; }
  }

  function passagePayload(p) {
    return { type: 'passage', id: p.id, title: p.title, mission: p.mission, level: p.level, word_count: p.wordCount,
      words: p.words.map(w => ({ i: w.index, t: w.display, s: w.sentence })), sentences: p.sentences.length, reactions: p.reactions };
  }

  root.RRSession = Session;
  root.RRLines = L;
})(window);
