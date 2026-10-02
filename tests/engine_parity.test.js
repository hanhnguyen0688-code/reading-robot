// node tests/engine_parity.test.js  -> JS engine must reproduce the Python engine exactly
const assert = require('assert');
const E = require('../app/engine.js');
const fx = require('./fixtures/parity.json');
const passages = Object.fromEntries(fx.passages.map(p => [p.id, E.buildPassage(p)]));
let ok = 0;
for (const c of fx.cases) {
  const tr = new E.LiveTracker(passages[c.passage]); const prog = [];
  for (const s of c.steps) {
    if (s.told !== undefined) { tr.markTold(s.told); prog.push(tr.refresh()); continue; }
    prog.push(tr.update(s.hyp.map(([norm, start, end, accuracy]) => ({ norm, start, end, accuracy })), s.t));
  }
  const r = E.buildReport(tr, { student: { id: 'cathy', name: 'Cathy', year: 'Year 2', teacher: 'Ms. Patel' }, finishedReason: 'completed' });
  for (const k of Object.keys(c.report)) assert.deepStrictEqual(r[k], c.report[k], `${c.name}: ${k} js=${JSON.stringify(r[k])} py=${JSON.stringify(c.report[k])}`);
  assert.deepStrictEqual(r.words.map(w => w.status), c.statuses, `${c.name}: statuses`);
  assert.deepStrictEqual(r.miscues.map(m => [m.index, m.type, m.said]), c.miscues, `${c.name}: miscues`);
  assert.strictEqual(r.teacher_note, c.teacher_note, `${c.name}: teacher note`);
  c.progress.forEach((p, i) => { for (const k of Object.keys(p)) assert.deepStrictEqual(prog[i][k], p[k], `${c.name}: progress[${i}].${k}`); });
  ok++;
}
console.log(`engine parity OK: ${ok} sessions identical to Python`);
