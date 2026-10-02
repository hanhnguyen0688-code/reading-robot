"""All tunable business rules in one place.

These values are *calibration* knobs, not magic numbers: tune them against
human-scored recordings (see README > Hiệu chỉnh) before the Perth pilot.
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class PaceBand:
    slow_below: float     # WCPM below this -> "A bit slow"
    fast_above: float     # WCPM above this -> "Whoa, speedy!"


# Rough oral-reading-fluency targets per passage level (WCPM). Year 2 mid-year
# 50th percentile on published ORF norms sits around 70 WCPM; the "just right"
# window is deliberately wide because the meter is for encouragement, not grading.
DEFAULT_PACE = {
    "A": PaceBand(25, 80),
    "B": PaceBand(35, 110),
    "C": PaceBand(50, 130),
    "D": PaceBand(60, 150),
}


@dataclass
class ReadingConfig:
    # --- alignment / miscue rules -------------------------------------
    hesitation_seconds: float = 3.0          # pause before a word that counts as hesitation
    repetition_window: int = 4               # how far back a re-read word is recognised
    low_pronunciation_score: float = 60.0    # Azure accuracy below this -> "practise" flag
    count_mispronunciation_as_error: bool = False  # native-speaker kids: keep False
    mispronunciation_error_score: float = 35.0

    # --- session flow ---------------------------------------------------
    finish_grace_seconds: float = 1.2        # wait after last word before finishing
    stall_prompt_seconds: float = 7.0        # no progress -> gentle "take your time"
    stall_finish_seconds: float = 20.0       # no progress and nothing said -> wrap up
    early_finish_ratio: float = 0.85         # silence after this much of passage -> finish
    early_finish_silence: float = 6.0
    max_session_seconds: float = 300.0
    help_debounce_seconds: float = 3.0

    # --- UI behaviour ----------------------------------------------------
    show_errors_live: bool = False           # running-record practice: don't mark errors mid-read
    live_wcpm_window_seconds: float = 20.0

    # --- rewards -----------------------------------------------------------
    stars_per_sentence: int = 1
    stars_clean_sentence_bonus: int = 1      # sentence with zero errors
    stars_finish_bonus: int = 3
    stars_accuracy_bonus: int = 2            # accuracy >= independent band

    # --- accuracy bands (running-record convention) ---------------------
    independent_min: float = 95.0
    instructional_min: float = 90.0

    pace_bands: dict[str, PaceBand] = field(default_factory=lambda: dict(DEFAULT_PACE))

    def pace_for(self, level: str) -> PaceBand:
        return self.pace_bands.get((level or "B").upper()[:1], self.pace_bands["B"])
