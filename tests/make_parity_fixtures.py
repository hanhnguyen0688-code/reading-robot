"""Write reading sessions + Python reports so the JS engine can be checked against them."""
import sys, os; sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import json, random
from pathlib import Path
from reading_robot.aligner import HypWord
from reading_robot.passages import load_passages
from reading_robot.scoring import LiveTracker, build_report
from reading_robot.simulate import CATHY_SCRIPT
from reading_robot.text import normalise
from reading_robot.teacher import fallback_teacher_note

OUT = Path(__file__).parent / "fixtures" / "parity.json"
STUDENT = {"id": "cathy", "name": "Cathy", "year": "Year 2", "teacher": "Ms. Patel"}
KEYS = ["total_words","words_attempted","words_correct","errors","self_corrections","accuracy_pct","accuracy_band",
        "completion_pct","reading_seconds","wcpm","pace","stars_earned","star_rating","error_rate","sc_rate","practice_words"]

def run(passage, script):
    tr = LiveTracker(passage); finals = []; t = 0.0; steps = []; events = []
    for said, pause, dur, acc in script:
        t += pause
        if said == "<HELP>":
            idx = tr.state.cursor + 1; tr.mark_told(idx); steps.append({"told": idx}); events.append(tr.refresh()); t += dur; continue
        hw = HypWord(normalise(said), round(t, 3), round(t + dur, 3), 0.9, acc)
        interim = finals + [HypWord(hw.norm, hw.start, hw.end, 0.6, None)]
        steps.append({"hyp": [[h.norm, h.start, h.end, h.accuracy] for h in interim], "t": t})
        events.append(tr.update(interim, now=t))
        t += dur; finals.append(hw)
        steps.append({"hyp": [[h.norm, h.start, h.end, h.accuracy] for h in finals], "t": t})
        events.append(tr.update(finals, now=t))
    r = build_report(tr, student=STUDENT, finished_reason="completed").to_dict()
    return {"steps": steps, "report": {k: r[k] for k in KEYS},
            "miscues": [[m["index"], m["type"], m["said"]] for m in r["miscues"]],
            "statuses": [w["status"] for w in r["words"]], "teacher_note": fallback_teacher_note(r),
            "progress": [{k: e[k] for k in ("cursor","marks","new_sentences","stars_session","wcpm","pace")} for e in events]}

def random_script(passage, rng):
    """Perturbed reading: substitutions, omissions, insertions, repeats, sound-outs, pauses."""
    out = []; words = [w.display.strip('.,!?"') for w in passage.words]; i = 0
    while i < len(words):
        w = words[i]; r = rng.random(); pause = rng.choice([0.1,0.2,0.3,0.5,0.8,3.5 if rng.random()<.1 else 0.3]); acc = rng.choice([None, 40, 70, 95])
        if r < 0.06: i += 1; continue                                          # omission
        if r < 0.12: out.append((rng.choice(["cat","the","went","big","sat"]), pause, .3, acc))  # substitution
        elif r < 0.17: out.append((w[:max(1,len(w)//2)], pause, .3, None)); out.append((w, .4, .3, acc))  # sound out
        elif r < 0.21: out.append((w, pause, .3, acc)); out.append((w, .2, .3, acc))           # repeat
        elif r < 0.24: out.append(("zebra", pause, .3, None)); out.append((w, .2, .3, acc))     # insertion
        elif r < 0.26: out.append(("<HELP>", pause, .3, None)); out.append((w, .3, .3, acc))
        else: out.append((w, pause, .3, acc))
        i += 1
    return out

def main():
    ps = load_passages(); rng = random.Random(7); cases = []
    cases.append({"name": "cathy", "passage": "mission-7", **run(ps["mission-7"], CATHY_SCRIPT)})
    for k in range(40):
        pid = rng.choice(list(ps)); cases.append({"name": f"random-{k}", "passage": pid, **run(ps[pid], random_script(ps[pid], rng))})
    raw = json.loads((Path(__file__).parent.parent / "data" / "passages.json").read_text())
    OUT.write_text(json.dumps({"passages": raw, "cases": cases}))
    print("wrote", len(cases), "cases")

if __name__ == "__main__":
    main()
