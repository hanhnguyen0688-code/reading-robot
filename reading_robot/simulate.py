"""Simulated "Cathy" read-through, used by tests and by the web demo.

It feeds the *real* engine with a realistic, imperfect reading (sounding out,
a self-correction, a skipped word, a substitution, a long pause, a request
for help, changing pace) and records exactly what the robot screen would
receive. `python -m reading_robot.simulate` writes web/demo_events.json.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from .aligner import HypWord
from .config import ReadingConfig
from .passages import find_student, load_passages, passage_payload
from .scoring import LiveTracker, build_report
from .teacher import fallback_teacher_note, closing_line, greeting_line
from .text import normalise

# (said, pause_before_s, duration_s, pronunciation_accuracy)
# "<HELP>" = child says "Help!" -> robot reads the next word.
CATHY_SCRIPT: list[tuple[str, float, float, float | None]] = [
    # slow, careful start
    ("Milo", 0.6, 0.45, 92), ("the", 0.35, 0.2, 95), ("cat", 0.3, 0.35, 96), ("had", 0.35, 0.3, 90),
    ("a", 0.25, 0.15, 97), ("big", 0.3, 0.3, 94), ("problem", 0.35, 0.55, 88),
    ("His", 0.8, 0.3, 93),
    ("fav", 0.4, 0.35, None), ("favourite", 0.6, 0.7, 71),          # sounded out
    ("red", 0.3, 0.25, 95), ("sock", 0.25, 0.3, 94), ("was", 0.25, 0.2, 96), ("gone", 0.3, 0.35, 91),
    # warming up - faster
    ("He", 0.6, 0.15, 95), ("looked", 0.15, 0.3, 93), ("under", 0.15, 0.3, 92), ("the", 0.12, 0.15, 97),
    ("bed", 0.15, 0.25, 96),
    ("He", 0.5, 0.15, 95), ("looked", 0.12, 0.3, 94), ("behind", 0.15, 0.35, 90), ("the", 0.1, 0.12, 97),
    ("door", 0.12, 0.3, 95),
    ("He", 0.5, 0.15, 95), ("even", 0.15, 0.3, 48),                  # weak pronunciation
    ("looked", 0.15, 0.3, 93), ("inside", 0.15, 0.35, 90), ("the", 0.12, 0.12, 97),
    ("fridge", 3.4, 0.4, 85),                                         # long pause before
    ("which", 0.5, 0.3, 90), ("was", 0.15, 0.2, 95), ("cold", 0.25, 0.35, 93),   # skipped "very"
    ("Then", 0.7, 0.25, 94), ("Milo", 0.15, 0.4, 92), ("heard", 0.15, 0.3, 89), ("a", 0.12, 0.12, 97),
    ("tiny", 0.15, 0.3, 91), ("speak", 0.2, 0.35, 60),                # "squeak" -> "speak"
    ("A", 0.7, 0.12, 96), ("moose", 0.2, 0.35, None), ("mouse", 0.4, 0.4, 88),    # self-correction
    ("was", 0.15, 0.2, 95), ("sleeping", 0.15, 0.45, 90), ("in", 0.12, 0.12, 96), ("the", 0.1, 0.12, 97),
    ("sock", 0.12, 0.3, 94),
    ("<HELP>", 2.5, 0.4, None),                                       # stuck on "snoring"
    ("snoring", 1.4, 0.5, 80),
    ("like", 0.2, 0.25, 94), ("a", 0.12, 0.12, 97), ("little", 0.15, 0.3, 92), ("like", 0.25, 0.25, 93),
    ("a", 0.1, 0.12, 97), ("little", 0.12, 0.3, 92), ("train", 0.15, 0.4, 95),  # re-read "like a little"
]


def run_simulation(student_id: str = "cathy", passage_id: str | None = None,
                   cfg: ReadingConfig | None = None, interim_step: float = 0.35) -> tuple[list[dict], dict]:
    cfg = cfg or ReadingConfig()
    student = find_student(student_id) or {"id": student_id, "name": student_id.title()}
    passages = load_passages()
    passage = passages[passage_id or student.get("passage_id", "mission-7")]
    tracker = LiveTracker(passage, cfg)

    events: list[dict] = []
    t_wall = 0.0

    def emit(t: float, ev: dict) -> None:
        events.append({"t": round(t, 2), **ev})

    emit(0.0, {"type": "state", "state": "greet", "student": student})
    emit(0.2, {"type": "robot_says", "text": greeting_line(student["name"])})
    emit(4.5, {"type": "user_says", "text": "Ready!"})
    emit(5.0, {"type": "state", "state": "reading"})
    emit(5.0, passage_payload(passage))
    emit(5.1, {"type": "robot_says", "text": "Start reading out loud whenever you're ready! I'm all ears... well, all microphones."})
    t0 = 8.0                              # reading starts
    t = 0.0
    finals: list[HypWord] = []
    for said, pause, dur, acc in CATHY_SCRIPT:
        t += pause
        if said == "<HELP>":
            emit(t0 + t, {"type": "user_says", "text": "Help!"})
            idx = tracker.state.cursor + 1
            tracker.mark_told(idx)
            emit(t0 + t + 0.3, {"type": "robot_says", "text": f"That word is “{passage.words[idx].norm}”.", "told_index": idx})
            emit(t0 + t + 0.4, tracker.refresh())
            t += dur
            continue
        hw = HypWord(normalise(said), t, t + dur, 0.9, acc, final=False, raw=said)
        # interim result first (no pronunciation score yet), then final
        ev = tracker.update(finals + [HypWord(hw.norm, hw.start, hw.end, 0.6, None, False, said)], now=t0 + t)
        emit(t0 + t + 0.15, ev)
        t += dur
        hw.final = True
        finals.append(hw)
        ev = tracker.update(finals, now=t0 + t)
        emit(t0 + t + 0.25, ev)

    end_t = t0 + t + cfg.finish_grace_seconds
    report = build_report(tracker, student=student, finished_reason="completed", asr_backend="simulation")
    report.teacher_note = fallback_teacher_note(report)
    emit(end_t, {"type": "state", "state": "finish"})
    emit(end_t, {"type": "report", "report": report.to_dict()})
    emit(end_t + 0.2, {"type": "robot_says", "text": closing_line(student["name"])})
    emit(end_t + 1.5, {"type": "report_sent", "teacher": student.get("teacher", "Teacher"),
                       "webhook": True, "teacher_note": report.teacher_note})
    return events, report.to_dict()


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "web" / "demo_events.json"
    events, report = run_simulation()
    out.write_text(json.dumps(events, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote {len(events)} events -> {out}")
    keys = ["words_attempted", "words_correct", "errors", "self_corrections", "accuracy_pct",
            "accuracy_band", "wcpm", "pace", "stars_earned", "error_rate", "sc_rate"]
    print(json.dumps({k: report[k] for k in keys}, indent=1))
    for m in report["miscues"]:
        print(f"  #{m['index']:>2} {m['type']:<16} {m['word']:<10} said={m['said']} {m['detail'] or ''}")
    print("practice:", report["practice_words"])
    print("note:", report["teacher_note"])


if __name__ == "__main__":
    main()
