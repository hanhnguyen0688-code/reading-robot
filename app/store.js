/* On-device storage for the standalone app: roster, passages, settings, reports.
 * Uses localStorage (works in Chrome, installed PWAs and Android WebView with DOM storage on).
 */
(function (root) {
  'use strict';
  const KEY = 'reading-robot.v1';

  const DEFAULTS = {
    settings: {
      locale: 'en-AU', voiceName: '', speechRate: 0.95, pitch: 1.15,
      showErrorsLive: false, hesitationSeconds: 3, teacherPin: '1234', className: '2P', serverUrl: '',
    },
    students: [
      { id: 'cathy', name: 'Cathy', year: 'Year 2', teacher: 'Ms. Patel', passage_id: 'mission-7', streak_days: 0, stars_total: 0, last_day: '' },
      { id: 'leo', name: 'Leo', year: 'Year 2', teacher: 'Ms. Patel', passage_id: 'mission-8', streak_days: 0, stars_total: 0, last_day: '' },
    ],
    passages: [
      { id: 'mission-7', mission: 7, title: 'The Lost Sock', level: 'B',
        text: 'Milo the cat had a big problem. His favourite red sock was gone! He looked under the bed. He looked behind the door. He even looked inside the fridge, which was very cold. Then Milo heard a tiny squeak. A mouse was sleeping in the sock, snoring like a little train.',
        reactions: { 0: 'Uh-oh, a big problem!', 1: 'Oh no, not the red sock!', 3: 'Not behind the door either...', 4: 'A cat in the fridge?! Keep going...', 5: 'A squeak? Who could that be?', 6: 'Ha! A snoring mouse!' } },
      { id: 'mission-8', mission: 8, title: 'Rain Day', level: 'B',
        text: 'It was raining on Saturday. Ava could not go to the park. She sat by the window and watched the drops race down the glass. Her dad made pancakes with blueberries. Then they built a big blanket fort in the lounge room. It was the best rainy day ever.',
        reactions: { 1: 'Oh no, no park today!', 3: 'Pancakes! Yum!', 4: 'A blanket fort? So cool!' } },
    ],
    reports: [],
  };

  let mem = null;
  function load() {
    if (mem) return mem;
    let data = null;
    try { data = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { data = null; }
    mem = Object.assign(JSON.parse(JSON.stringify(DEFAULTS)), data || {});
    mem.settings = Object.assign({}, DEFAULTS.settings, mem.settings || {});
    return mem;
  }
  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(mem)); return true; }
    catch (e) {  // storage full: drop oldest reports and retry once
      if (mem.reports.length > 20) { mem.reports = mem.reports.slice(0, Math.floor(mem.reports.length / 2)); try { localStorage.setItem(KEY, JSON.stringify(mem)); return true; } catch (e2) {} }
      return false;
    }
  }
  const slug = s => String(s).toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'x';
  function uniqueId(base, list) { let id = slug(base), k = 2; while (list.some(x => x.id === id)) id = `${slug(base)}-${k++}`; return id; }
  const today = () => new Date().toISOString().slice(0, 10);

  const Store = {
    get settings() { return load().settings; },
    setSettings(patch) { Object.assign(load().settings, patch); save(); },
    students() { return load().students; },
    student(id) { return load().students.find(s => s.id === id); },
    upsertStudent(s) {
      const list = load().students;
      if (!s.id) s.id = uniqueId(s.name, list);
      const i = list.findIndex(x => x.id === s.id);
      if (i >= 0) list[i] = Object.assign(list[i], s); else list.push(Object.assign({ streak_days: 0, stars_total: 0, last_day: '' }, s));
      save(); return s.id;
    },
    deleteStudent(id) { const d = load(); d.students = d.students.filter(s => s.id !== id); save(); },
    passages() { return load().passages; },
    passage(id) { return load().passages.find(p => p.id === id); },
    upsertPassage(p) {
      const list = load().passages;
      if (!p.id) p.id = uniqueId(p.title, list);
      const i = list.findIndex(x => x.id === p.id);
      if (i >= 0) list[i] = Object.assign(list[i], p); else list.push(p);
      save(); return p.id;
    },
    deletePassage(id) { const d = load(); d.passages = d.passages.filter(p => p.id !== id); save(); },
    reports() { return load().reports; },
    addReport(r) {
      const d = load(); d.reports.unshift(r); if (d.reports.length > 300) d.reports.length = 300;
      // stars + daily streak on the student record
      const s = d.students.find(x => x.id === r.student_id);
      if (s) {
        s.stars_total = (s.stars_total || 0) + (r.stars_earned || 0);
        const t = today(); const y = new Date(Date.now() - 864e5).toISOString().slice(0, 10);
        if (s.last_day !== t) s.streak_days = s.last_day === y ? (s.streak_days || 0) + 1 : 1;
        s.last_day = t;
      }
      return save();
    },
    deleteReport(sessionId) { const d = load(); d.reports = d.reports.filter(r => r.session_id !== sessionId); save(); },
    exportAll() { return JSON.stringify(load(), null, 2); },
    importAll(json) { const data = JSON.parse(json); if (!data || !Array.isArray(data.students)) throw new Error('Not a Reading Robot backup'); mem = Object.assign(load(), data); save(); },
    resetAll() { try { localStorage.removeItem(KEY); } catch (e) {} mem = null; load(); save(); },
    reportsCsv() {
      const cols = ['created_at', 'student_name', 'year', 'teacher', 'passage_title', 'level', 'total_words', 'words_attempted', 'errors',
        'self_corrections', 'accuracy_pct', 'accuracy_band', 'wcpm', 'completion_pct', 'reading_seconds', 'finished_reason', 'practice_words', 'teacher_note'];
      const q = v => `"${String(Array.isArray(v) ? v.join(' ') : v ?? '').replace(/"/g, '""')}"`;
      return [cols.join(','), ...load().reports.map(r => cols.map(c => q(r[c])).join(','))].join('\n');
    },
  };
  root.RRStore = Store;
})(window);
