"""Live progress tracking and the final reading report.

Numbers here are computed deterministically from the alignment. The LLM is
only ever given this report to *phrase* a comment - it never computes scores.
"""
from __future__ import annotations

import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone

from .aligner import AlignmentState, HypWord, StreamingAligner
from .config import ReadingConfig
from .text import Passage


def accuracy_band(acc: float, cfg: ReadingConfig) -> str:
    if acc >= cfg.independent_min:
        return "independent"
    if acc >= cfg.instructional_min:
        return "instructional"
    return "frustration"


def pace_label(wcpm: float | None, level: str, cfg: ReadingConfig) -> str:
    if wcpm is None:
        return "warming_up"
    band = cfg.pace_for(level)
    if wcpm < band.slow_below:
        return "slow"
    if wcpm > band.fast_above:
        return "fast"
    return "just_right"


PACE_TEXT = {
    "warming_up": "Warming up...",
    "slow": "Nice and steady",
    "just_right": "Just right!",
    "fast": "Whoa, speedy!",
}


def sentence_errors(state: AlignmentState, idxs: list[int]) -> int:
    return sum(1 for i in idxs if state.words[i].is_error)


class LiveTracker:
    """Turns successive alignment states into UI events and running stars."""

    def __init__(self, passage: Passage, cfg: ReadingConfig | None = None):
        self.passage = passage
        self.cfg = cfg or ReadingConfig()
        self.aligner = StreamingAligner(passage, self.cfg)
        self.sentences_awarded: set[int] = set()
        self.stars = 0
        self.last_progress_at = time.monotonic()
        self._last_cursor = -1

    @property
    def state(self) -> AlignmentState:
        return self.aligner.state

    def mark_told(self, index: int) -> None:
        self.aligner.mark_told(index)

    def update(self, hyp: list[HypWord], now: float | None = None) -> dict:
        state = self.aligner.update(hyp)
        if state.cursor > self._last_cursor:
            self._last_cursor = state.cursor
            self.last_progress_at = now if now is not None else time.monotonic()
        return self.progress_event(state)

    def refresh(self) -> dict:
        return self.progress_event(self.aligner.state)

    def live_wcpm(self, state: AlignmentState) -> float | None:
        correct = [w for w in state.words if w.status == "correct" and w.end is not None]
        if len(correct) < 4 or state.last_end is None:
            return None
        window = self.cfg.live_wcpm_window_seconds
        t_end = state.last_end
        recent = [w for w in correct if w.end >= t_end - window]
        t0 = max(t_end - window, state.first_start or 0.0)
        span = t_end - t0
        if span < 3.0:
            return None
        return round(len(recent) / span * 60.0, 1)

    def progress_event(self, state: AlignmentState) -> dict:
        new_sentences = []
        for s, idxs in enumerate(self.passage.sentences):
            if s in self.sentences_awarded:
                continue
            if state.cursor >= idxs[-1]:
                errs = sentence_errors(state, idxs)
                gained = self.cfg.stars_per_sentence + (self.cfg.stars_clean_sentence_bonus if errs == 0 else 0)
                self.sentences_awarded.add(s)
                self.stars += gained
                new_sentences.append({"sentence": s, "errors": errs, "stars": gained})

        wcpm = self.live_wcpm(state)
        # Detailed marks are always sent; the screen decides whether to show
        # error squiggles (show_errors_live) - running-record practice says no.
        marks = []
        for w in state.words:
            if w.status == "pending":
                marks.append("p")
            elif w.is_error:
                marks.append({"substitution": "s", "omission": "o", "told": "t"}[w.status])
            elif w.low_pronunciation or w.sounded_out:
                marks.append("w")     # read, worth practising
            else:
                marks.append("r")     # read
        pace = pace_label(wcpm, self.passage.level, self.cfg)
        return {
            "type": "progress",
            "cursor": state.cursor,
            "next": min(state.cursor + 1, self.passage.word_count - 1),
            "marks": marks,
            "show_errors_live": self.cfg.show_errors_live,
            "sentences_done": sorted(self.sentences_awarded),
            "new_sentences": new_sentences,
            "stars_session": self.stars,
            "wcpm": wcpm,
            "pace": pace,
            "pace_text": PACE_TEXT[pace],
            "percent": round(100 * (state.cursor + 1) / max(1, self.passage.word_count)),
        }

    def reached_end(self) -> bool:
        return self.aligner.state.cursor >= self.passage.word_count - 1


# ---------------------------------------------------------------- report
@dataclass
class Miscue:
    index: int
    word: str
    type: str          # substitution | omission | told | insertion | self_correction | sounded_out | repetition | hesitation | pronunciation
    said: str | None = None
    detail: str | None = None
    time: float | None = None


@dataclass
class ReadingReport:
    session_id: str
    student_id: str
    student_name: str
    year: str
    teacher: str
    passage_id: str
    passage_title: str
    level: str
    created_at: str
    total_words: int
    words_attempted: int
    words_correct: int
    errors: int
    self_corrections: int
    accuracy_pct: float
    accuracy_band: str
    completion_pct: float
    reading_seconds: float
    wcpm: float | None
    pace: str
    stars_earned: int
    star_rating: int                  # 1-3, child-facing
    finished_reason: str
    error_rate: str                   # "1:17" running-record style
    sc_rate: str | None
    miscues: list[Miscue] = field(default_factory=list)
    practice_words: list[str] = field(default_factory=list)
    words: list[dict] = field(default_factory=list)
    teacher_note: str = ""
    audio_url: str | None = None
    asr_backend: str = ""

    def to_dict(self) -> dict:
        return asdict(self)


def build_report(
    tracker: LiveTracker,
    *,
    student: dict,
    finished_reason: str,
    session_id: str | None = None,
    asr_backend: str = "",
) -> ReadingReport:
    cfg, p, st = tracker.cfg, tracker.passage, tracker.state
    words = st.words
    attempted = [w for w in words if w.attempted]
    errors = [w for w in words if w.is_error]
    insertions = st.insertions
    n_err = len(errors) + len(insertions)
    n_att = len(attempted)
    correct = sum(1 for w in words if w.status == "correct")
    acc = round(100.0 * max(0, n_att - n_err) / n_att, 1) if n_att else 0.0
    scs = sum(1 for w in words if w.self_corrected)

    timed = [w for w in words if w.start is not None]
    secs = (max(w.end for w in timed) - min(w.start for w in timed)) if len(timed) >= 2 else 0.0
    wcpm = round(correct / secs * 60, 1) if secs >= 5 else None

    finished = tracker.reached_end()
    stars = tracker.stars + (cfg.stars_finish_bonus if finished else 0)
    band = accuracy_band(acc, cfg)
    if band == "independent" and n_att:
        stars += cfg.stars_accuracy_bonus
    rating = 3 if band == "independent" and finished else 2 if band != "frustration" else 1

    miscues: list[Miscue] = []
    for w in words:
        ref = p.words[w.index].norm
        if w.status in ("substitution", "omission", "told"):
            miscues.append(Miscue(w.index, ref, w.status, w.said, time=w.start))
        if w.self_corrected:
            miscues.append(Miscue(w.index, ref, "self_correction", time=w.start))
        if w.sounded_out:
            miscues.append(Miscue(w.index, ref, "sounded_out", time=w.start))
        if w.repeated:
            miscues.append(Miscue(w.index, ref, "repetition", time=w.start))
        if w.hesitation:
            miscues.append(Miscue(w.index, ref, "hesitation", detail=f"{w.hesitation:.1f}s pause", time=w.start))
        if w.low_pronunciation and w.status == "correct":
            miscues.append(Miscue(w.index, ref, "pronunciation", detail=f"score {w.accuracy:.0f}/100", time=w.start))
    for ins in insertions:
        miscues.append(Miscue(ins.after_index, "", "insertion", ins.said, time=ins.start))
    miscues.sort(key=lambda m: (m.index, m.type))

    practice: list[str] = []
    for w in words:
        if w.is_error or w.low_pronunciation or w.sounded_out or (w.hesitation or 0) >= cfg.hesitation_seconds:
            ref = p.words[w.index].norm
            if ref not in practice:
                practice.append(ref)

    def ratio(a: int, b: int) -> str:
        return f"1:{round(a / b)}" if b else "0"

    return ReadingReport(
        session_id=session_id or uuid.uuid4().hex[:12],
        student_id=student.get("id", ""),
        student_name=student.get("name", ""),
        year=student.get("year", ""),
        teacher=student.get("teacher", ""),
        passage_id=p.id,
        passage_title=p.title,
        level=p.level,
        created_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        total_words=p.word_count,
        words_attempted=n_att,
        words_correct=correct,
        errors=n_err,
        self_corrections=scs,
        accuracy_pct=acc,
        accuracy_band=band,
        completion_pct=round(100.0 * n_att / p.word_count, 1),
        reading_seconds=round(secs, 1),
        wcpm=wcpm,
        pace=pace_label(wcpm, p.level, cfg),
        stars_earned=stars,
        star_rating=rating,
        finished_reason=finished_reason,
        error_rate=ratio(n_att, n_err),
        sc_rate=(f"1:{round((n_err + scs) / scs)}" if scs else None),
        miscues=miscues,
        practice_words=practice,
        words=[
            {"i": w.index, "w": p.words[w.index].display, "status": w.status, "said": w.said,
             "start": w.start, "acc": w.accuracy, "sc": w.self_corrected, "so": w.sounded_out,
             "rep": w.repeated, "hes": w.hesitation, "lowpron": w.low_pronunciation}
            for w in words
        ],
        asr_backend=asr_backend,
    )
