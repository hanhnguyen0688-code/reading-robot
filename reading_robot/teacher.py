"""Robot lines, the teacher note, and report delivery."""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path

logger = logging.getLogger("reading-robot.teacher")

REPORTS_DIR = Path(os.getenv("REPORTS_DIR", Path(__file__).resolve().parent.parent / "reports"))


# --- fixed lines from the client script (kept verbatim on purpose) --------
def greeting_line(name: str) -> str:
    return f"Hi {name}! Ready to read to me? I polished my ears just for you!"


def closing_line(name: str) -> str:
    return (f"Great work {name}. I'll send some key information to your teacher so they "
            f"know how you went. You can head back to your desk now.")


START_LINE = "Start reading out loud whenever you're ready! I'm all ears... well, all microphones."
NOT_ME_LINE = "Oops, sorry! Please ask your teacher to send the right reader to me."
STALL_LINES = [
    "Take your time. If a word is tricky, just say Help!",
    "You're doing great. Keep going, or say Help if you're stuck.",
]
CONFIRM_RETRY_LINE = "When you're ready, just say Ready, or tap the button!"


def told_line(word: str) -> str:
    return f"That word is {word}."


# --- teacher note -----------------------------------------------------------
TEACHER_NOTE_SYSTEM = """You write a short note for a primary-school teacher about one oral reading session.
Rules:
- Use ONLY the facts in the JSON. Never invent numbers, words or behaviours.
- 3 to 4 sentences, plain professional English (Australian spelling), no headings, no emojis.
- Sentence 1: accuracy % and band, words per minute, whether the passage was finished.
- Sentence 2: the most useful miscue pattern (name up to 3 specific words from the JSON).
- Sentence 3: one strength (e.g. self-corrections, steady pace).
- Sentence 4 (optional): one concrete next step using practice_words.
"""


def teacher_note_payload(report) -> str:
    d = report.to_dict() if hasattr(report, "to_dict") else report
    keep = ["student_name", "year", "passage_title", "level", "total_words", "words_attempted",
            "words_correct", "errors", "self_corrections", "accuracy_pct", "accuracy_band",
            "completion_pct", "reading_seconds", "wcpm", "pace", "practice_words", "finished_reason"]
    slim = {k: d[k] for k in keep}
    slim["miscues"] = [{k: m[k] for k in ("word", "type", "said", "detail")} for m in d["miscues"]]
    return json.dumps(slim, ensure_ascii=False)


def fallback_teacher_note(report) -> str:
    """Deterministic note used when no LLM is available (or as a guard)."""
    d = report.to_dict() if hasattr(report, "to_dict") else report
    band = {"independent": "independent", "instructional": "instructional", "frustration": "frustration"}[d["accuracy_band"]]
    pace = f"{d['wcpm']:.0f} words correct per minute" if d["wcpm"] else "pace not measured"
    done = "finished the passage" if d["completion_pct"] >= 99 else f"read {d['completion_pct']:.0f}% of the passage"
    s1 = f"{d['student_name']} {done} with {d['accuracy_pct']:.0f}% accuracy ({band} level), {pace}."
    by_type: dict[str, list[str]] = {}
    for m in d["miscues"]:
        by_type.setdefault(m["type"], []).append(m["word"] if m["type"] != "insertion" else (m["said"] or ""))
    parts = []
    if by_type.get("substitution"):
        subs = [f"'{m['said']}' for '{m['word']}'" for m in d["miscues"] if m["type"] == "substitution"][:2]
        parts.append("substituted " + ", ".join(subs))
    if by_type.get("omission"):
        parts.append("skipped " + ", ".join(f"'{w}'" for w in by_type["omission"][:3]))
    if by_type.get("told"):
        parts.append("needed help with " + ", ".join(f"'{w}'" for w in by_type["told"][:3]))
    s2 = (f"{d['student_name']} " + "; ".join(parts) + ".") if parts else "No errors were recorded."
    strengths = []
    if d["self_corrections"]:
        strengths.append(f"self-corrected {d['self_corrections']} time{'s' if d['self_corrections'] > 1 else ''}")
    if by_type.get("sounded_out"):
        strengths.append("used sounding out on " + ", ".join(f"'{w}'" for w in by_type["sounded_out"][:2]))
    s3 = ("Strengths: " + " and ".join(strengths) + ".") if strengths else ""
    s4 = ("Suggested practice words: " + ", ".join(d["practice_words"][:6]) + ".") if d["practice_words"] else ""
    return " ".join(s for s in (s1, s2, s3, s4) if s)


async def llm_teacher_note(report, llm) -> str:
    """Ask the session LLM to phrase the note. Falls back on any failure."""
    try:
        from livekit.agents import llm as lk_llm

        ctx = lk_llm.ChatContext()
        ctx.add_message(role="system", content=TEACHER_NOTE_SYSTEM)
        ctx.add_message(role="user", content=teacher_note_payload(report))
        text = ""
        async with llm.chat(chat_ctx=ctx) as stream:
            async for chunk in stream:
                if chunk.delta and chunk.delta.content:
                    text += chunk.delta.content
        text = text.strip()
        # guard: a note quoting an accuracy figure that is not ours is rejected
        if not text or f"{report.accuracy_pct:.0f}" not in text:
            raise ValueError("LLM note failed fact check")
        return text
    except Exception as e:  # noqa: BLE001
        logger.warning("teacher note via LLM failed (%s); using template", e)
        return fallback_teacher_note(report)


# --- delivery -----------------------------------------------------------------
def save_report(report) -> Path:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    d = report.to_dict()
    path = REPORTS_DIR / f"{d['created_at'][:10]}_{d['student_id'] or 'unknown'}_{d['session_id']}.json"
    path.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


async def post_webhook(report) -> bool:
    """POST the report to TEACHER_WEBHOOK_URL (school portal, Teams workflow, LMS...)."""
    url = os.getenv("TEACHER_WEBHOOK_URL")
    if not url:
        return False
    import aiohttp

    try:
        async with aiohttp.ClientSession() as s:
            async with s.post(url, json=report.to_dict(), timeout=aiohttp.ClientTimeout(total=10)) as r:
                ok = 200 <= r.status < 300
                if not ok:
                    logger.warning("teacher webhook returned %s", r.status)
                return ok
    except Exception as e:  # noqa: BLE001
        logger.warning("teacher webhook failed: %s", e)
        return False
