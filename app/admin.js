/* Teacher area: reports, students, passages, settings. Opened from the home screen behind a PIN. */
(function (root) {
  'use strict';
  const $ = s => document.querySelector(s);
  const St = () => root.RRStore;
  const E = root.RREngine;
  const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const BAND = { independent: 'Independent ≥95%', instructional: 'Instructional 90–94%', frustration: 'Frustration <90%' };
  const TYPE = { substitution: 'Substitution', omission: 'Omission', told: 'Told (asked for help)', insertion: 'Insertion', self_correction: 'Self-correction',
    sounded_out: 'Sounded out', repetition: 'Repetition', hesitation: 'Hesitation', pronunciation: 'Pronunciation to practise' };
  let tab = 'reports', selected = 0, onClose = null;

  function open(close) { onClose = close; $('#admin').hidden = false; render(); }
  function close() { $('#admin').hidden = true; onClose && onClose(); }

  function toastA(msg) { const t = $('#aToast'); t.textContent = msg; t.classList.add('show'); clearTimeout(t._t); t._t = setTimeout(() => t.classList.remove('show'), 2200); }

  async function share(title, text) {
    try {
      if (root.RRNative && root.RRNative.share) { root.RRNative.share(title, text); return; }
      if (navigator.share) { await navigator.share({ title, text }); return; }
    } catch (e) { if (e && e.name === 'AbortError') return; }
    try { await navigator.clipboard.writeText(text); toastA('Copied to clipboard'); }
    catch (e) { toastA('Could not share on this device'); }
  }
  function download(name, text, type) {
    if (root.RRNative && root.RRNative.saveFile) { root.RRNative.saveFile(name, text, type); return; }
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type })); a.download = name; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  function render() {
    document.querySelectorAll('#admin .tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
    const body = $('#aBody');
    if (tab === 'reports') body.innerHTML = reportsView();
    if (tab === 'students') body.innerHTML = studentsView();
    if (tab === 'passages') body.innerHTML = passagesView();
    if (tab === 'settings') body.innerHTML = settingsView();
    wire();
  }

  // ------------------------------------------------------------- reports
  function reportsView() {
    const rs = St().reports();
    if (!rs.length) return `<div class="empty">No reports yet. A report appears here as soon as a child finishes reading.</div>`;
    selected = Math.min(selected, rs.length - 1);
    const list = rs.map((r, i) => `<button class="item ${i === selected ? 'on' : ''}" data-r="${i}"><span><b>${esc(r.student_name)}</b><small>${esc(r.passage_title)} · ${new Date(r.created_at).toLocaleString()}</small></span><span class="band ${r.accuracy_band}">${r.accuracy_pct}%</span></button>`).join('');
    return `<div class="rtools"><button class="ab" data-act="csv">Export CSV</button><span class="muted">${rs.length} report${rs.length > 1 ? 's' : ''} on this device</span></div>
      <div class="rlayout"><div class="rlist">${list}</div><div class="rdetail">${reportDetail(rs[selected])}</div></div>`;
  }
  function reportDetail(r) {
    const flags = {}; (r.miscues || []).forEach(m => { (flags[m.index] = flags[m.index] || []).push(m.type); });
    const passage = r.words.map(w => {
      const cls = ['w', w.status === 'correct' ? '' : w.status, (w.lowpron || w.so || w.hes) ? 'flag' : ''].join(' ');
      const sup = [w.sc ? 'SC' : '', w.rep ? 'R' : '', w.hes ? '⏸' : ''].filter(Boolean).join(' ');
      const said = w.status === 'substitution' && w.said ? `<span class="said">${esc(w.said)}</span>` : '';
      const tip = [...new Set((flags[w.i] || []).map(t => TYPE[t]))].join(', ') + (w.hes ? ` · paused ${w.hes}s` : '');
      return `<span class="${cls}" title="${esc(tip)}">${said}${esc(w.w)}${sup ? `<sup>${sup}</sup>` : ''}</span>`;
    }).join(' ');
    const rows = (r.miscues || []).filter(m => m.type !== 'repetition').map(m => `<tr><td>${m.index + 1}</td><td>${esc(m.word || '—')}</td><td>${TYPE[m.type] || m.type}</td><td>${esc(m.said || '')}</td><td>${esc(m.detail || '')}</td></tr>`).join('');
    return `<div class="rhead"><div><h2>${esc(r.student_name)} <span class="muted">· ${esc(r.year)}</span></h2>
      <div class="muted">${esc(r.passage_title)} · Level ${esc(r.level)} · ${esc(r.finished_reason)} · ${esc(r.asr_backend)}</div></div>
      <span class="band ${r.accuracy_band}">${BAND[r.accuracy_band]}</span></div>
      <div class="kpis">
        <div class="kpi"><small>Accuracy</small><b>${r.accuracy_pct}%</b><span>${r.words_attempted - r.errors}/${r.words_attempted} · error rate ${r.error_rate}</span></div>
        <div class="kpi"><small>WCPM</small><b>${r.wcpm ?? '—'}</b><span>${r.reading_seconds}s · ${String(r.pace).replace('_', ' ')}</span></div>
        <div class="kpi"><small>Errors</small><b>${r.errors}</b><span>SC ${r.self_corrections} · SC rate ${r.sc_rate ?? '—'}</span></div>
        <div class="kpi"><small>Completion</small><b>${r.completion_pct}%</b><span>of ${r.total_words} words</span></div>
      </div>
      <h3>Teacher note</h3><div class="note">${esc(r.teacher_note)}</div>
      <h3>Running record</h3><div class="rr">${passage}</div>
      <div class="legend"><span><span class="w substitution">word</span> substitution</span><span><span class="w omission">word</span> omission</span><span><span class="w told">word</span> told</span><span><span class="w flag">word</span> practise</span><span>SC self-correction · R repetition · ⏸ pause</span></div>
      <h3>Practice words</h3><div>${(r.practice_words || []).map(w => `<span class="chip">${esc(w)}</span>`).join('') || '—'}</div>
      <h3>Miscues</h3><div class="tablewrap"><table><thead><tr><th>#</th><th>Word</th><th>Type</th><th>Said</th><th>Detail</th></tr></thead><tbody>${rows || '<tr><td colspan="5">None</td></tr>'}</tbody></table></div>
      <div class="rtools"><button class="ab primary" data-act="share" data-id="${esc(r.session_id)}">Share with teacher</button><button class="ab danger" data-act="delrep" data-id="${esc(r.session_id)}">Delete report</button></div>`;
  }
  function reportText(r) {
    return `Reading Robot report\n${r.student_name} (${r.year}) · ${r.passage_title} (Level ${r.level})\n${new Date(r.created_at).toLocaleString()}\n\n` +
      `Accuracy ${r.accuracy_pct}% (${r.accuracy_band}) · ${r.wcpm ?? '—'} WCPM · ${r.errors} errors · ${r.self_corrections} self-corrections\n\n` +
      `${r.teacher_note}\n\nMiscues:\n` + r.miscues.filter(m => m.type !== 'repetition').map(m => `- ${m.word || m.said}: ${TYPE[m.type]}${m.said && m.type === 'substitution' ? ` (said "${m.said}")` : ''}`).join('\n');
  }

  // ------------------------------------------------------------ students
  function studentsView() {
    const ps = St().passages();
    const opts = sel => ps.map(p => `<option value="${esc(p.id)}" ${p.id === sel ? 'selected' : ''}>${esc(p.title)} (Level ${esc(p.level)})</option>`).join('');
    const rows = St().students().map(s => `<div class="row" data-sid="${esc(s.id)}">
      <input id="sn-${esc(s.id)}" value="${esc(s.name)}" aria-label="Name" placeholder="Name">
      <input id="sy-${esc(s.id)}" value="${esc(s.year)}" aria-label="Year" placeholder="Year">
      <input id="st-${esc(s.id)}" value="${esc(s.teacher)}" aria-label="Teacher" placeholder="Teacher">
      <select id="sp-${esc(s.id)}" aria-label="Passage">${opts(s.passage_id)}</select>
      <span class="muted nowrap">★ ${s.stars_total || 0} · ${s.streak_days || 0}d</span>
      <button class="ab" data-act="savestu">Save</button><button class="ab danger" data-act="delstu">Delete</button></div>`).join('');
    return `<p class="muted">Each child sees their name on the robot. Pick the passage they read next.</p>
      <div class="rows">${rows}</div>
      <h3>Add a student</h3>
      <div class="row"><input id="nsName" placeholder="Name"><input id="nsYear" placeholder="Year" value="Year 2"><input id="nsTeacher" placeholder="Teacher" value="${esc(St().students()[0]?.teacher || '')}">
      <select id="nsPassage">${opts(ps[0]?.id)}</select><button class="ab primary" data-act="addstu">Add</button></div>`;
  }

  // ------------------------------------------------------------ passages
  function passagesView() {
    const rows = St().passages().map(p => {
      const n = E.buildPassage(p).wordCount;
      return `<details class="pcard"><summary><b>${esc(p.title)}</b> <span class="muted">Mission ${esc(p.mission ?? '–')} · Level ${esc(p.level)} · ${n} words</span></summary>
        <div class="pform" data-pid="${esc(p.id)}">
          <label>Title <input id="pt-${esc(p.id)}" value="${esc(p.title)}"></label>
          <label>Mission <input id="pm-${esc(p.id)}" type="number" value="${esc(p.mission ?? '')}"></label>
          <label>Level <select id="pl-${esc(p.id)}">${['A', 'B', 'C', 'D'].map(l => `<option ${l === p.level ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
          <label class="full">Text <textarea id="px-${esc(p.id)}" rows="5">${esc(p.text)}</textarea></label>
          <div class="full rtools"><span class="muted wc" data-for="px-${esc(p.id)}">${n} words</span><button class="ab primary" data-act="savepas">Save</button><button class="ab danger" data-act="delpas">Delete</button></div>
        </div></details>`;
    }).join('');
    return `<p class="muted">Word counts are calculated from the text. Sentences end at . ! or ?</p>${rows}
      <h3>Add a passage</h3>
      <div class="pform"><label>Title <input id="npTitle"></label><label>Mission <input id="npMission" type="number"></label>
      <label>Level <select id="npLevel"><option>A</option><option selected>B</option><option>C</option><option>D</option></select></label>
      <label class="full">Text <textarea id="npText" rows="5" placeholder="Paste the passage the child will read"></textarea></label>
      <div class="full rtools"><span class="muted wc" data-for="npText">0 words</span><button class="ab primary" data-act="addpas">Add passage</button></div></div>`;
  }

  // ------------------------------------------------------------ settings
  function settingsView() {
    const s = St().settings;
    const voices = root.RRSpeech.TTS.voices();
    const voiceOpts = `<option value="">Automatic (${esc(s.locale)})</option>` + voices.map(v => `<option ${v.name === s.voiceName ? 'selected' : ''}>${esc(v.name)}</option>`).join('');
    return `<div class="pform">
      <label>Reading accent <select id="sLocale">${['en-AU', 'en-GB', 'en-US', 'en-NZ'].map(l => `<option ${l === s.locale ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
      <label>Robot voice <select id="sVoice">${voiceOpts}</select></label>
      <label>Speaking speed <input id="sRate" type="number" step="0.05" min="0.6" max="1.4" value="${s.speechRate}"></label>
      <label>Pause counted as hesitation (s) <input id="sHes" type="number" step="0.5" min="1" max="10" value="${s.hesitationSeconds}"></label>
      <label class="check"><input id="sMarks" type="checkbox" ${s.showErrorsLive ? 'checked' : ''}> Show error marks while the child reads</label>
      <label>Teacher PIN <input id="sPin" inputmode="numeric" maxlength="8" value="${esc(s.teacherPin)}"></label>
      <div class="full rtools"><button class="ab primary" data-act="saveset">Save settings</button><button class="ab" data-act="testvoice">Test robot voice</button><button class="ab" data-act="testmic">Test microphone</button></div>
      <div class="full mictest" id="micOut" hidden></div>
      <h3 class="full">Backup</h3>
      <div class="full rtools"><button class="ab" data-act="backup">Download backup</button><label class="ab filebtn">Restore backup<input id="restoreFile" type="file" accept="application/json"></label><button class="ab danger" data-act="reset">Reset app</button></div>
      <p class="full muted">Speech engine: ${esc(root.RRSpeech.available() ? (root.RRNative ? 'Android (on-device)' : 'Chrome Web Speech') : 'not available on this browser')} · Voice: ${esc(root.RRSpeech.TTS.name())}</p>
    </div>`;
  }

  // --------------------------------------------------------------- wiring
  function wire() {
    const body = $('#aBody');
    body.querySelectorAll('[data-r]').forEach(b => b.onclick = () => { selected = +b.dataset.r; render(); });
    body.querySelectorAll('textarea').forEach(t => t.oninput = () => { const w = body.querySelector(`.wc[data-for="${t.id}"]`); if (w) w.textContent = `${E.buildPassage({ text: t.value }).wordCount} words`; });
    const rf = $('#restoreFile'); if (rf) rf.onchange = async () => { try { St().importAll(await rf.files[0].text()); toastA('Backup restored'); render(); } catch (e) { toastA(e.message); } };
    body.querySelectorAll('[data-act]').forEach(b => b.onclick = () => act(b.dataset.act, b));
  }
  let resetArmed = false;
  function act(a, b) {
    const val = id => ($('#' + CSS.escape(id)) || {}).value;
    if (a === 'csv') return download(`reading-reports-${new Date().toISOString().slice(0, 10)}.csv`, St().reportsCsv(), 'text/csv');
    if (a === 'share') { const r = St().reports().find(x => x.session_id === b.dataset.id); return share(`Reading report: ${r.student_name}`, reportText(r)); }
    if (a === 'delrep') { St().deleteReport(b.dataset.id); toastA('Report deleted'); return render(); }
    if (a === 'savestu' || a === 'delstu') {
      const id = b.closest('[data-sid]').dataset.sid;
      if (a === 'delstu') { St().deleteStudent(id); toastA('Student removed'); return render(); }
      if (!val('sn-' + id).trim()) return toastA('Enter a name');
      St().upsertStudent({ id, name: val('sn-' + id).trim(), year: val('sy-' + id), teacher: val('st-' + id), passage_id: val('sp-' + id) });
      return toastA('Saved');
    }
    if (a === 'addstu') {
      const name = val('nsName').trim(); if (!name) return toastA('Enter a name');
      St().upsertStudent({ name, year: val('nsYear'), teacher: val('nsTeacher'), passage_id: val('nsPassage') }); toastA(`${name} added`); return render();
    }
    if (a === 'savepas' || a === 'delpas') {
      const id = b.closest('[data-pid]').dataset.pid;
      if (a === 'delpas') {
        if (St().students().some(s => s.passage_id === id)) return toastA('A student is assigned this passage. Change their passage first.');
        St().deletePassage(id); toastA('Passage deleted'); return render();
      }
      const text = val('px-' + id).trim(); if (E.buildPassage({ text }).wordCount < 5) return toastA('The passage needs at least 5 words');
      St().upsertPassage({ id, title: val('pt-' + id).trim() || 'Untitled', mission: val('pm-' + id) ? +val('pm-' + id) : null, level: val('pl-' + id), text });
      toastA('Saved'); return render();
    }
    if (a === 'addpas') {
      const text = val('npText').trim(), title = val('npTitle').trim();
      if (!title) return toastA('Enter a title'); if (E.buildPassage({ text }).wordCount < 5) return toastA('The passage needs at least 5 words');
      St().upsertPassage({ title, mission: val('npMission') ? +val('npMission') : null, level: val('npLevel'), text, reactions: {} }); toastA('Passage added'); return render();
    }
    if (a === 'saveset') {
      const pin = val('sPin').trim(); if (!/^\d{4,8}$/.test(pin)) return toastA('PIN must be 4–8 digits');
      St().setSettings({ locale: val('sLocale'), voiceName: val('sVoice'), speechRate: +val('sRate') || 0.95, hesitationSeconds: +val('sHes') || 3,
        showErrorsLive: $('#sMarks').checked, teacherPin: pin });
      return toastA('Settings saved');
    }
    if (a === 'testvoice') { root.RRSpeech.TTS.unlock(); const s = St().settings; return root.RRSpeech.TTS.speak('Hi! I am Reading Robot. Ready to read to me?', { locale: val('sLocale'), rate: +val('sRate'), pitch: s.pitch, voiceName: val('sVoice') }); }
    if (a === 'testmic') {
      const out = $('#micOut'); out.hidden = false; out.textContent = 'Listening… say a sentence.';
      const l = root.RRSpeech.makeListener(); if (!l) { out.textContent = 'Speech recognition is not available on this browser.'; return; }
      l.start(val('sLocale'), (t, f) => { out.textContent = (f ? '✓ ' : '… ') + t; if (f) setTimeout(() => l.stop(), 300); },
        e => { out.textContent = e === 'mic-denied' ? 'Microphone permission denied.' : 'Error: ' + e; });
      setTimeout(() => l.stop(), 12000); return;
    }
    if (a === 'backup') return download(`reading-robot-backup-${new Date().toISOString().slice(0, 10)}.json`, St().exportAll(), 'application/json');
    if (a === 'reset') {
      if (!resetArmed) { resetArmed = true; b.textContent = 'Tap again to erase everything'; setTimeout(() => { resetArmed = false; b.textContent = 'Reset app'; }, 4000); return; }
      St().resetAll(); toastA('App reset'); resetArmed = false; return render();
    }
  }

  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('#admin .tabs button').forEach(b => b.onclick = () => { tab = b.dataset.tab; render(); });
    $('#aClose').onclick = close;
  });
  root.RRAdmin = { open, close };
})(window);
