"""Streaming alignment of what the child said against the reference passage.

Why we do this ourselves instead of relying on the ASR / LLM:
  * ASR engines "auto-correct" towards real words and an LLM only sees text,
    so neither can be trusted to say whether a child read a word correctly.
  * Azure Pronunciation Assessment does not compute miscues (omission /
    insertion) in continuous mode, which is the only mode that fits a
    whole-passage read.
So the recogniser gives us *words + timings (+ optional pronunciation score)*
and this module decides, word by word, what happened.

Algorithm: semi-global Needleman-Wunsch over words. The whole hypothesis must
be explained, but the reference may stop early (the child has not finished
yet). The full hypothesis is re-aligned on every update, which is cheap
(58 words x ~80 hyp words = ~5k cells) and lets interim ASR results be
revised freely without corrupting state.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from .config import ReadingConfig
from .text import Passage, edit_similarity, same_word

FILLERS = {"um", "umm", "uh", "uhh", "er", "erm", "hmm", "mm", "ah", "eh", "oh"}
CONTROL_WORDS = {"help"}

Op = Literal["match", "sub", "del", "ins"]


@dataclass
class HypWord:
    norm: str                       # normalised text
    start: float                    # seconds from reading start
    end: float
    confidence: float = 1.0
    accuracy: float | None = None   # pronunciation accuracy 0-100 (Azure), if any
    final: bool = True
    raw: str = ""


@dataclass
class WordResult:
    index: int
    status: Literal["pending", "correct", "substitution", "omission", "told"] = "pending"
    said: str | None = None          # what the child said for this word
    start: float | None = None
    end: float | None = None
    accuracy: float | None = None    # pronunciation score if available
    self_corrected: bool = False     # wrong attempt immediately fixed (not an error)
    sounded_out: bool = False        # partial attempt e.g. "fav... favourite"
    repeated: bool = False           # child re-read this word
    hesitation: float | None = None  # pause (s) before this word, if long
    low_pronunciation: bool = False  # correct word, weak pronunciation

    @property
    def is_error(self) -> bool:
        return self.status in ("substitution", "omission", "told")

    @property
    def attempted(self) -> bool:
        return self.status != "pending"


@dataclass
class Insertion:
    after_index: int                 # inserted after this ref index (-1 = before first)
    said: str
    start: float


@dataclass
class AlignmentState:
    words: list[WordResult]
    insertions: list[Insertion]
    cursor: int                      # last reference index the child has reached (-1 = none)
    hyp_count: int
    first_start: float | None
    last_end: float | None

    @property
    def next_index(self) -> int:
        return self.cursor + 1


@dataclass
class _Step:
    op: Op
    r: int  # ref index (or position for ins)
    h: int  # hyp index (or -1 for del)


class StreamingAligner:
    def __init__(self, passage: Passage, config: ReadingConfig | None = None):
        self.passage = passage
        self.cfg = config or ReadingConfig()
        self.ref = [w.norm for w in passage.words]
        self.told: set[int] = set()
        self._hyp: list[HypWord] = []
        self.state = self._empty_state()

    # ------------------------------------------------------------------ API
    def mark_told(self, index: int) -> None:
        """Robot supplied this word after the child asked for help."""
        if 0 <= index < len(self.ref):
            self.told.add(index)

    def update(self, hyp: list[HypWord]) -> AlignmentState:
        """Re-align with the full current hypothesis (finals + interim tail)."""
        self._hyp = [h for h in hyp if h.norm and h.norm not in FILLERS]
        self._hyp = self._drop_control_words(self._hyp)
        self.state = self._align()
        return self.state

    # ------------------------------------------------------------ internals
    def _empty_state(self) -> AlignmentState:
        return AlignmentState([WordResult(i) for i in range(len(self.ref))], [], -1, 0, None, None)

    def _drop_control_words(self, hyp: list[HypWord]) -> list[HypWord]:
        """'help' is a command, not reading - unless the passage contains it."""
        if any(r in CONTROL_WORDS for r in self.ref):
            return hyp
        return [h for h in hyp if h.norm not in CONTROL_WORDS]

    def _sub_cost(self, r: str, h: str) -> float:
        if same_word(r, h):
            return 0.0
        sim = edit_similarity(r, h)
        return 0.55 if sim >= 0.5 else 1.0

    def _ins_cost(self, i: int, h: str) -> float:
        """Cost of an extra spoken word after reference position i-1.

        Re-reading, sounding out and self-correcting are normal reading
        behaviours, so they are cheaper than a random extra word. This also
        makes the aligner attach a re-read phrase to its *first* reading.
        """
        R, w = self.ref, self.cfg.repetition_window
        if any(same_word(r, h) for r in R[max(0, i - w):i]):
            return 0.4
        if i < len(R):
            nxt = R[i]
            if nxt.startswith(h) and h != nxt:
                return 0.5
            if edit_similarity(nxt, h) >= 0.5:
                return 0.7
        return 1.0

    def _align(self) -> AlignmentState:
        R, H = self.ref, self._hyp
        n, m = len(R), len(H)
        if m == 0:
            st = self._empty_state()
            for i in self.told:
                st.words[i].status = "told"
            st.cursor = max(self.told) if self.told else -1
            return st

        INF = float("inf")
        del_c = 1.0
        dp = [[INF] * (m + 1) for _ in range(n + 1)]
        bt: list[list[str]] = [[""] * (m + 1) for _ in range(n + 1)]
        dp[0][0] = 0.0
        for j in range(1, m + 1):
            dp[0][j] = dp[0][j - 1] + self._ins_cost(0, H[j - 1].norm)
            bt[0][j] = "ins"
        for i in range(1, n + 1):
            # skipping a word the robot told costs nothing: the child need not re-read it
            d = 0.0 if (i - 1) in self.told else del_c
            dp[i][0] = dp[i - 1][0] + d
            bt[i][0] = "del"
            ri = R[i - 1]
            for j in range(1, m + 1):
                best, how = dp[i - 1][j - 1] + self._sub_cost(ri, H[j - 1].norm), "diag"
                c = dp[i - 1][j] + d
                if c < best:
                    best, how = c, "del"
                c = dp[i][j - 1] + self._ins_cost(i, H[j - 1].norm)
                if c < best:
                    best, how = c, "ins"
                dp[i][j], bt[i][j] = best, how

        # Free reference suffix: child may not have finished yet.
        # On ties prefer the *shorter* reference prefix so stray interim
        # words do not push the cursor forward.
        end_i = min(range(n + 1), key=lambda i: (dp[i][m], i))

        steps: list[_Step] = []
        i, j = end_i, m
        while i > 0 or j > 0:
            how = bt[i][j]
            if how == "diag":
                op: Op = "match" if same_word(R[i - 1], H[j - 1].norm) else "sub"
                steps.append(_Step(op, i - 1, j - 1)); i -= 1; j -= 1
            elif how == "del":
                steps.append(_Step("del", i - 1, -1)); i -= 1
            else:
                steps.append(_Step("ins", i - 1, j - 1)); j -= 1
        steps.reverse()
        return self._classify(steps, end_i)

    def _classify(self, steps: list[_Step], end_i: int) -> AlignmentState:
        cfg, H, R = self.cfg, self._hyp, self.ref
        words = [WordResult(i) for i in range(len(R))]
        insertions: list[Insertion] = []
        prev_end: float | None = None

        for k, s in enumerate(steps):
            if s.op in ("match", "sub"):
                h = H[s.h]
                w = words[s.r]
                w.said, w.start, w.end, w.accuracy = h.norm, h.start, h.end, h.accuracy
                w.status = "correct" if s.op == "match" else "substitution"
                if s.op == "match" and h.accuracy is not None and h.accuracy < cfg.low_pronunciation_score:
                    w.low_pronunciation = True
                    if cfg.count_mispronunciation_as_error and h.accuracy < cfg.mispronunciation_error_score:
                        w.status = "substitution"
                if prev_end is not None and h.start - prev_end >= cfg.hesitation_seconds:
                    w.hesitation = round(h.start - prev_end, 2)
                prev_end = h.end
            elif s.op == "del":
                words[s.r].status = "omission"
            else:  # insertion - decide whether it is an error at all
                h = H[s.h]
                nxt = self._next_ref_matched(steps, k)
                # Re-reading: the word was just read (the DP attaches the first copy).
                back = range(s.r, max(-1, s.r - cfg.repetition_window), -1)
                rep = next((r for r in back if r >= 0 and same_word(R[r], h.norm)), None)
                if rep is not None:
                    words[rep].repeated = True
                elif nxt is not None and R[nxt].startswith(h.norm) and len(h.norm) >= 1 and h.norm != R[nxt]:
                    words[nxt].sounded_out = True      # "fav... favourite"
                elif nxt is not None and edit_similarity(R[nxt], h.norm) >= 0.5:
                    words[nxt].self_corrected = True   # "sack... sock"
                else:
                    insertions.append(Insertion(s.r, h.norm, h.start))
                if prev_end is not None and h.start - prev_end >= cfg.hesitation_seconds and nxt is not None:
                    words[nxt].hesitation = round(h.start - prev_end, 2)
                prev_end = h.end

        for t in self.told:
            words[t].status = "told"
            words[t].self_corrected = False

        # A told word counts as reached: the robot said it for the child.
        cursor = max(end_i - 1, max(self.told, default=-1))
        timed = H
        return AlignmentState(
            words=words,
            insertions=insertions,
            cursor=cursor,
            hyp_count=len(H),
            first_start=timed[0].start if timed else None,
            last_end=timed[-1].end if timed else None,
        )

    @staticmethod
    def _next_ref_matched(steps: list[_Step], k: int) -> int | None:
        """Reference index of the next match/sub after an insertion."""
        for s in steps[k + 1:]:
            if s.op in ("match", "sub"):
                return s.r if s.op == "match" else None
            if s.op == "del":
                return None
        return None
