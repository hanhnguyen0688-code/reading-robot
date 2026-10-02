/* Reading Robot scoring engine (JavaScript port of reading_robot/*.py).
 * Runs on the device so the app works without a server.
 * Kept line-for-line equivalent to the Python engine; tests/engine_parity.test.js
 * checks both give the same report for the same reading.
 */
(function (root) {
  'use strict';

  // ------------------------------------------------------------- config
  const DEFAULT_PACE = { A: [25, 80], B: [35, 110], C: [50, 130], D: [60, 150] };
  function defaultConfig() {
    return {
      hesitationSeconds: 3.0, repetitionWindow: 4,
      lowPronunciationScore: 60, countMispronunciationAsError: false, mispronunciationErrorScore: 35,
      finishGraceSeconds: 1.2, stallPromptSeconds: 7, stallFinishSeconds: 20,
      earlyFinishRatio: 0.85, earlyFinishSilence: 6, maxSessionSeconds: 300, helpDebounceSeconds: 3,
      showErrorsLive: false, liveWcpmWindowSeconds: 20,
      starsPerSentence: 1, starsCleanSentenceBonus: 1, starsFinishBonus: 3, starsAccuracyBonus: 2,
      independentMin: 95, instructionalMin: 90, paceBands: DEFAULT_PACE,
    };
  }
  const paceFor = (cfg, level) => cfg.paceBands[(level || 'B').toUpperCase()[0]] || cfg.paceBands.B;

  // Python rounds halves to even (round(8.5) == 8); match it so reports are identical.
  function r0(x) { const r = Math.round(x); return (Math.abs(x % 1) === 0.5 && r % 2 !== 0) ? r - 1 : r; }
  // round(x, 1): toFixed works on the exact binary value; only exact ties (.25/.75) need half-even.
  function r1(x) {
    if (Number.isInteger(x * 4) && !Number.isInteger(x * 2)) { const d = Math.floor(x * 10); return (d % 2 === 0 ? d : d + 1) / 10; }
    return Number(x.toFixed(1));
  }
  function r2(x) {
    if (Number.isInteger(x * 8) && !Number.isInteger(x * 4)) { const d = Math.floor(x * 100); return (d % 2 === 0 ? d : d + 1) / 100; }
    return Number(x.toFixed(2));
  }

  // --------------------------------------------------------------- text
  const SPELLING = { favorite: 'favourite', color: 'colour', colors: 'colours', neighbor: 'neighbour', neighbors: 'neighbours',
    honor: 'honour', mom: 'mum', gray: 'grey', center: 'centre', theater: 'theatre', realize: 'realise', realized: 'realised',
    organize: 'organise', traveled: 'travelled', traveling: 'travelling', flavor: 'flavour', humor: 'humour', behavior: 'behaviour',
    jewelry: 'jewellery', pajamas: 'pyjamas', cozy: 'cosy', practice: 'practise' };
  const NUMBERS = { 0: 'zero', 1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight', 9: 'nine',
    10: 'ten', 11: 'eleven', 12: 'twelve', 20: 'twenty', 100: 'hundred' };
  const HOMOPHONES = [['to', 'too', 'two'], ['there', 'their', 'theyre'], ['for', 'four'], ['by', 'buy', 'bye'], ['hear', 'here'],
    ['see', 'sea'], ['won', 'one'], ['new', 'knew'], ['no', 'know'], ['right', 'write'], ['sun', 'son'], ['ate', 'eight'],
    ['blue', 'blew'], ['red', 'read'], ['tail', 'tale'], ['whole', 'hole'], ['wear', 'where'], ['week', 'weak'], ['bear', 'bare'],
    ['flower', 'flour'], ['its', "it's"], ['your', 'youre'], ['mail', 'male']];
  const HOMO = {}; HOMOPHONES.forEach((g, i) => g.forEach(w => { HOMO[w] = i; }));
  const FILLERS = new Set(['um', 'umm', 'uh', 'uhh', 'er', 'erm', 'hmm', 'mm', 'ah', 'eh', 'oh']);
  const CONTROL = new Set(['help']);
  const WORD_RE = /[A-Za-z0-9]+(?:['’][A-Za-z]+)*/g;

  function normalise(word) {
    let w = String(word).normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/’/g, "'");
    w = w.replace(/[^a-z0-9']/g, '').replace(/^'+|'+$/g, '').replace(/'/g, '');
    w = NUMBERS[w] || w;
    return SPELLING[w] || w;
  }
  function splitHypothesis(text) {
    const out = []; const m = String(text).match(WORD_RE) || [];
    for (const t of m) { const n = normalise(t); if (n) out.push(n); }
    return out;
  }
  function sameWord(r, h) {
    if (r === h) return true;
    const a = HOMO[r], b = HOMO[h];
    return a !== undefined && a === b;
  }
  function editSimilarity(a, b) {
    if (a === b) return 1; if (!a || !b) return 0;
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      const cur = [i];
      for (let j = 1; j <= b.length; j++) cur.push(Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] !== b[j - 1] ? 1 : 0)));
      prev = cur;
    }
    return 1 - prev[b.length] / Math.max(a.length, b.length);
  }
  function buildPassage(p) {
    const words = []; let sentence = 0;
    for (const tok of String(p.text).split(/\s+/).filter(Boolean)) {
      const norm = normalise(tok); if (!norm) continue;
      const end = /[.!?]+["'”’)]*$/.test(tok);
      words.push({ index: words.length, display: tok, norm, sentence, sentenceEnd: end });
      if (end) sentence++;
    }
    if (words.length && !words[words.length - 1].sentenceEnd) words[words.length - 1].sentenceEnd = true;
    const sentences = [];
    for (const w of words) { while (sentences.length <= w.sentence) sentences.push([]); sentences[w.sentence].push(w.index); }
    return { id: p.id, title: p.title, text: p.text, level: p.level || '', mission: p.mission ?? null,
      reactions: p.reactions || {}, words, sentences, wordCount: words.length };
  }

  // ------------------------------------------------------------ aligner
  function wordResult(i) {
    return { index: i, status: 'pending', said: null, start: null, end: null, accuracy: null,
      selfCorrected: false, soundedOut: false, repeated: false, hesitation: null, lowPronunciation: false };
  }
  const isError = w => w.status === 'substitution' || w.status === 'omission' || w.status === 'told';

  class StreamingAligner {
    constructor(passage, cfg) {
      this.passage = passage; this.cfg = cfg || defaultConfig();
      this.ref = passage.words.map(w => w.norm); this.told = new Set(); this.hyp = [];
      this.state = this.empty();
    }
    empty() { return { words: this.ref.map((_, i) => wordResult(i)), insertions: [], cursor: -1, hypCount: 0, firstStart: null, lastEnd: null }; }
    markTold(i) { if (i >= 0 && i < this.ref.length) this.told.add(i); }
    update(hyp) {
      let h = hyp.filter(x => x.norm && !FILLERS.has(x.norm));
      if (!this.ref.some(r => CONTROL.has(r))) h = h.filter(x => !CONTROL.has(x.norm));
      this.hyp = h; this.state = this.align(); return this.state;
    }
    subCost(r, h) { if (sameWord(r, h)) return 0; return editSimilarity(r, h) >= 0.5 ? 0.55 : 1.0; }
    insCost(i, h) {
      const R = this.ref, w = this.cfg.repetitionWindow;
      for (let k = Math.max(0, i - w); k < i; k++) if (sameWord(R[k], h)) return 0.4;
      if (i < R.length) {
        const nxt = R[i];
        if (nxt.startsWith(h) && h !== nxt) return 0.5;
        if (editSimilarity(nxt, h) >= 0.5) return 0.7;
      }
      return 1.0;
    }
    align() {
      const R = this.ref, H = this.hyp, n = R.length, m = H.length;
      if (!m) {
        const st = this.empty();
        this.told.forEach(i => { st.words[i].status = 'told'; });
        st.cursor = this.told.size ? Math.max(...this.told) : -1; return st;
      }
      const dp = Array.from({ length: n + 1 }, () => new Float64Array(m + 1).fill(Infinity));
      const bt = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(''));
      dp[0][0] = 0;
      for (let j = 1; j <= m; j++) { dp[0][j] = dp[0][j - 1] + this.insCost(0, H[j - 1].norm); bt[0][j] = 'ins'; }
      for (let i = 1; i <= n; i++) {
        const d = this.told.has(i - 1) ? 0 : 1.0;
        dp[i][0] = dp[i - 1][0] + d; bt[i][0] = 'del';
        const ri = R[i - 1];
        for (let j = 1; j <= m; j++) {
          let best = dp[i - 1][j - 1] + this.subCost(ri, H[j - 1].norm), how = 'diag';
          let c = dp[i - 1][j] + d; if (c < best) { best = c; how = 'del'; }
          c = dp[i][j - 1] + this.insCost(i, H[j - 1].norm); if (c < best) { best = c; how = 'ins'; }
          dp[i][j] = best; bt[i][j] = how;
        }
      }
      let endI = 0;
      for (let i = 1; i <= n; i++) if (dp[i][m] < dp[endI][m]) endI = i;   // ties keep the shorter prefix
      const steps = []; let i = endI, j = m;
      while (i > 0 || j > 0) {
        const how = bt[i][j];
        if (how === 'diag') { steps.push({ op: sameWord(R[i - 1], H[j - 1].norm) ? 'match' : 'sub', r: i - 1, h: j - 1 }); i--; j--; }
        else if (how === 'del') { steps.push({ op: 'del', r: i - 1, h: -1 }); i--; }
        else { steps.push({ op: 'ins', r: i - 1, h: j - 1 }); j--; }
      }
      steps.reverse();
      return this.classify(steps, endI);
    }
    nextRefMatched(steps, k) {
      for (const s of steps.slice(k + 1)) {
        if (s.op === 'match') return s.r;
        if (s.op === 'sub' || s.op === 'del') return null;
      }
      return null;
    }
    classify(steps, endI) {
      const cfg = this.cfg, H = this.hyp, R = this.ref;
      const words = R.map((_, i) => wordResult(i)); const insertions = []; let prevEnd = null;
      steps.forEach((s, k) => {
        if (s.op === 'match' || s.op === 'sub') {
          const h = H[s.h], w = words[s.r];
          Object.assign(w, { said: h.norm, start: h.start, end: h.end, accuracy: h.accuracy ?? null });
          w.status = s.op === 'match' ? 'correct' : 'substitution';
          if (s.op === 'match' && h.accuracy != null && h.accuracy < cfg.lowPronunciationScore) {
            w.lowPronunciation = true;
            if (cfg.countMispronunciationAsError && h.accuracy < cfg.mispronunciationErrorScore) w.status = 'substitution';
          }
          if (prevEnd != null && h.start - prevEnd >= cfg.hesitationSeconds) w.hesitation = r2(h.start - prevEnd);
          prevEnd = h.end;
        } else if (s.op === 'del') {
          words[s.r].status = 'omission';
        } else {
          const h = H[s.h]; const nxt = this.nextRefMatched(steps, k);
          let rep = null;
          for (let r = s.r; r > Math.max(-1, s.r - cfg.repetitionWindow); r--) if (r >= 0 && sameWord(R[r], h.norm)) { rep = r; break; }
          if (rep != null) words[rep].repeated = true;
          else if (nxt != null && R[nxt].startsWith(h.norm) && h.norm !== R[nxt]) words[nxt].soundedOut = true;
          else if (nxt != null && editSimilarity(R[nxt], h.norm) >= 0.5) words[nxt].selfCorrected = true;
          else insertions.push({ afterIndex: s.r, said: h.norm, start: h.start });
          if (prevEnd != null && h.start - prevEnd >= cfg.hesitationSeconds && nxt != null) words[nxt].hesitation = r2(h.start - prevEnd);
          prevEnd = h.end;
        }
      });
      this.told.forEach(t => { words[t].status = 'told'; words[t].selfCorrected = false; });
      const cursor = Math.max(endI - 1, this.told.size ? Math.max(...this.told) : -1);
      return { words, insertions, cursor, hypCount: H.length, firstStart: H.length ? H[0].start : null, lastEnd: H.length ? H[H.length - 1].end : null };
    }
  }

  // ------------------------------------------------------------ tracker
  const PACE_TEXT = { warming_up: 'Warming up...', slow: 'Nice and steady', just_right: 'Just right!', fast: 'Whoa, speedy!' };
  function accuracyBand(acc, cfg) { return acc >= cfg.independentMin ? 'independent' : acc >= cfg.instructionalMin ? 'instructional' : 'frustration'; }
  function paceLabel(wcpm, level, cfg) {
    if (wcpm == null) return 'warming_up';
    const [lo, hi] = paceFor(cfg, level);
    return wcpm < lo ? 'slow' : wcpm > hi ? 'fast' : 'just_right';
  }

  class LiveTracker {
    constructor(passage, cfg) {
      this.passage = passage; this.cfg = cfg || defaultConfig();
      this.aligner = new StreamingAligner(passage, this.cfg);
      this.awarded = new Set(); this.stars = 0; this.lastProgressAt = now(); this.lastCursor = -1;
    }
    get state() { return this.aligner.state; }
    markTold(i) { this.aligner.markTold(i); }
    update(hyp, t) {
      const st = this.aligner.update(hyp);
      if (st.cursor > this.lastCursor) { this.lastCursor = st.cursor; this.lastProgressAt = t ?? now(); }
      return this.progressEvent(st);
    }
    refresh() { return this.progressEvent(this.aligner.state); }
    liveWcpm(st) {
      const correct = st.words.filter(w => w.status === 'correct' && w.end != null);
      if (correct.length < 4 || st.lastEnd == null) return null;
      const win = this.cfg.liveWcpmWindowSeconds, tEnd = st.lastEnd;
      const recent = correct.filter(w => w.end >= tEnd - win);
      const t0 = Math.max(tEnd - win, st.firstStart || 0), span = tEnd - t0;
      if (span < 3) return null;
      return r1(recent.length / span * 60);
    }
    progressEvent(st) {
      const newSentences = [];
      this.passage.sentences.forEach((idxs, s) => {
        if (this.awarded.has(s) || st.cursor < idxs[idxs.length - 1]) return;
        const errs = idxs.filter(i => isError(st.words[i])).length;
        const gained = this.cfg.starsPerSentence + (errs === 0 ? this.cfg.starsCleanSentenceBonus : 0);
        this.awarded.add(s); this.stars += gained; newSentences.push({ sentence: s, errors: errs, stars: gained });
      });
      const marks = st.words.map(w => w.status === 'pending' ? 'p'
        : isError(w) ? { substitution: 's', omission: 'o', told: 't' }[w.status]
        : (w.lowPronunciation || w.soundedOut) ? 'w' : 'r');
      const wcpm = this.liveWcpm(st), pace = paceLabel(wcpm, this.passage.level, this.cfg);
      return { type: 'progress', cursor: st.cursor, next: Math.min(st.cursor + 1, this.passage.wordCount - 1), marks,
        show_errors_live: this.cfg.showErrorsLive, sentences_done: [...this.awarded].sort((a, b) => a - b),
        new_sentences: newSentences, stars_session: this.stars, wcpm, pace, pace_text: PACE_TEXT[pace],
        percent: r0(100 * (st.cursor + 1) / Math.max(1, this.passage.wordCount)) };
    }
    reachedEnd() { return this.aligner.state.cursor >= this.passage.wordCount - 1; }
  }

  // ------------------------------------------------------------- report
  function buildReport(tracker, { student, finishedReason, sessionId, asrBackend }) {
    const cfg = tracker.cfg, p = tracker.passage, st = tracker.state, words = st.words;
    const attempted = words.filter(w => w.status !== 'pending');
    const nErr = words.filter(isError).length + st.insertions.length, nAtt = attempted.length;
    const correct = words.filter(w => w.status === 'correct').length;
    const acc = nAtt ? r1(100 * Math.max(0, nAtt - nErr) / nAtt) : 0;
    const scs = words.filter(w => w.selfCorrected).length;
    const timed = words.filter(w => w.start != null);
    const secs = timed.length >= 2 ? Math.max(...timed.map(w => w.end)) - Math.min(...timed.map(w => w.start)) : 0;
    const wcpm = secs >= 5 ? r1(correct / secs * 60) : null;
    const finished = tracker.reachedEnd();
    let stars = tracker.stars + (finished ? cfg.starsFinishBonus : 0);
    const band = accuracyBand(acc, cfg);
    if (band === 'independent' && nAtt) stars += cfg.starsAccuracyBonus;
    const rating = band === 'independent' && finished ? 3 : band !== 'frustration' ? 2 : 1;

    const miscues = [];
    for (const w of words) {
      const ref = p.words[w.index].norm;
      if (isError(w)) miscues.push({ index: w.index, word: ref, type: w.status, said: w.said, detail: null, time: w.start });
      if (w.selfCorrected) miscues.push({ index: w.index, word: ref, type: 'self_correction', said: null, detail: null, time: w.start });
      if (w.soundedOut) miscues.push({ index: w.index, word: ref, type: 'sounded_out', said: null, detail: null, time: w.start });
      if (w.repeated) miscues.push({ index: w.index, word: ref, type: 'repetition', said: null, detail: null, time: w.start });
      if (w.hesitation) miscues.push({ index: w.index, word: ref, type: 'hesitation', said: null, detail: `${w.hesitation.toFixed(1)}s pause`, time: w.start });
      if (w.lowPronunciation && w.status === 'correct') miscues.push({ index: w.index, word: ref, type: 'pronunciation', said: null, detail: `score ${Math.round(w.accuracy)}/100`, time: w.start });
    }
    for (const ins of st.insertions) miscues.push({ index: ins.afterIndex, word: '', type: 'insertion', said: ins.said, detail: null, time: ins.start });
    miscues.sort((a, b) => a.index - b.index || (a.type < b.type ? -1 : a.type > b.type ? 1 : 0));
    const practice = [];
    for (const w of words) {
      if (isError(w) || w.lowPronunciation || w.soundedOut || (w.hesitation || 0) >= cfg.hesitationSeconds) {
        const ref = p.words[w.index].norm; if (!practice.includes(ref)) practice.push(ref);
      }
    }
    const report = {
      session_id: sessionId || Math.random().toString(16).slice(2, 14), student_id: student.id || '', student_name: student.name || '',
      year: student.year || '', teacher: student.teacher || '', passage_id: p.id, passage_title: p.title, level: p.level,
      created_at: new Date().toISOString().slice(0, 19) + 'Z', total_words: p.wordCount, words_attempted: nAtt, words_correct: correct,
      errors: nErr, self_corrections: scs, accuracy_pct: acc, accuracy_band: band, completion_pct: r1(100 * nAtt / p.wordCount),
      reading_seconds: r1(secs), wcpm, pace: paceLabel(wcpm, p.level, cfg), stars_earned: stars, star_rating: rating,
      finished_reason: finishedReason, error_rate: nErr ? `1:${r0(nAtt / nErr)}` : '0', sc_rate: scs ? `1:${r0((nErr + scs) / scs)}` : null,
      miscues, practice_words: practice,
      words: words.map(w => ({ i: w.index, w: p.words[w.index].display, status: w.status, said: w.said, start: w.start, acc: w.accuracy,
        sc: w.selfCorrected, so: w.soundedOut, rep: w.repeated, hes: w.hesitation, lowpron: w.lowPronunciation })),
      teacher_note: '', asr_backend: asrBackend || '',
    };
    report.teacher_note = teacherNote(report);
    return report;
  }

  function teacherNote(d) {
    const pace = d.wcpm ? `${r0(d.wcpm)} words correct per minute` : 'pace not measured';
    const done = d.completion_pct >= 99 ? 'finished the passage' : `read ${r0(d.completion_pct)}% of the passage`;
    const s1 = `${d.student_name} ${done} with ${r0(d.accuracy_pct)}% accuracy (${d.accuracy_band} level), ${pace}.`;
    const by = {}; d.miscues.forEach(m => { (by[m.type] = by[m.type] || []).push(m.type === 'insertion' ? (m.said || '') : m.word); });
    const parts = [];
    if (by.substitution) parts.push('substituted ' + d.miscues.filter(m => m.type === 'substitution').slice(0, 2).map(m => `'${m.said}' for '${m.word}'`).join(', '));
    if (by.omission) parts.push('skipped ' + by.omission.slice(0, 3).map(w => `'${w}'`).join(', '));
    if (by.told) parts.push('needed help with ' + by.told.slice(0, 3).map(w => `'${w}'`).join(', '));
    const s2 = parts.length ? `${d.student_name} ${parts.join('; ')}.` : 'No errors were recorded.';
    const strengths = [];
    if (d.self_corrections) strengths.push(`self-corrected ${d.self_corrections} time${d.self_corrections > 1 ? 's' : ''}`);
    if (by.sounded_out) strengths.push('used sounding out on ' + by.sounded_out.slice(0, 2).map(w => `'${w}'`).join(', '));
    const s3 = strengths.length ? `Strengths: ${strengths.join(' and ')}.` : '';
    const s4 = d.practice_words.length ? `Suggested practice words: ${d.practice_words.slice(0, 6).join(', ')}.` : '';
    return [s1, s2, s3, s4].filter(Boolean).join(' ');
  }

  function now() { return (typeof performance !== 'undefined' ? performance.now() : Date.now()) / 1000; }

  const api = { defaultConfig, normalise, splitHypothesis, sameWord, editSimilarity, buildPassage,
    StreamingAligner, LiveTracker, buildReport, teacherNote, accuracyBand, paceLabel, PACE_TEXT, isError };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.RREngine = api;
})(typeof window !== 'undefined' ? window : globalThis);
