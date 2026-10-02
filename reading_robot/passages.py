from __future__ import annotations

import json
from pathlib import Path

from .text import Passage, build_passage

DATA_DIR = Path(__file__).resolve().parent.parent / "data"


def load_passages(path: Path | None = None) -> dict[str, Passage]:
    raw = json.loads((path or DATA_DIR / "passages.json").read_text(encoding="utf-8"))
    out = {}
    for p in raw:
        ps = build_passage(p["id"], p["title"], p["text"], p.get("level", ""))
        ps.mission = p.get("mission")  # type: ignore[attr-defined]
        ps.reactions = p.get("reactions", {})  # type: ignore[attr-defined]
        out[p["id"]] = ps
    return out


def load_roster(path: Path | None = None) -> dict:
    return json.loads((path or DATA_DIR / "roster.json").read_text(encoding="utf-8"))


def find_student(student_id: str, roster: dict | None = None) -> dict | None:
    roster = roster or load_roster()
    sid = (student_id or "").strip().lower()
    for s in roster["students"]:
        if s["id"].lower() == sid or s["name"].lower() == sid:
            return s
    return None


def passage_payload(p: Passage) -> dict:
    """What the robot screen needs to render the passage."""
    return {
        "type": "passage",
        "id": p.id,
        "title": p.title,
        "mission": getattr(p, "mission", None),
        "level": p.level,
        "word_count": p.word_count,
        "words": [{"i": w.index, "t": w.display, "s": w.sentence} for w in p.words],
        "sentences": len(p.sentences),
        # optional per-sentence robot reactions authored with the passage
        "reactions": getattr(p, "reactions", {}),
    }
