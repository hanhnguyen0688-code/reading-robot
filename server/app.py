"""Token server + static hosting for the robot screen and the teacher view.

    uvicorn server.app:app --host 0.0.0.0 --port 8000

GET  /api/token?student=cathy   -> LiveKit token; the Reading Robot agent is dispatched into the room
GET  /api/roster                -> class roster (teacher queue)
GET  /api/reports               -> latest reports (teacher view)
POST /api/reports               -> receives reports (point TEACHER_WEBHOOK_URL here when the
                                   agent runs on another host)
"""
from __future__ import annotations

import json
import os
import sys
import uuid
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from livekit import api

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env")

from reading_robot.passages import find_student, load_passages, load_roster  # noqa: E402

REPORTS_DIR = Path(os.getenv("REPORTS_DIR", ROOT / "reports"))
AGENT_NAME = os.getenv("AGENT_NAME", "reading-robot")

app = FastAPI(title="Reading Robot")


@app.get("/api/token")
def token(student: str):
    s = find_student(student)
    if not s:
        raise HTTPException(404, f"unknown student '{student}'")
    key, secret = os.getenv("LIVEKIT_API_KEY"), os.getenv("LIVEKIT_API_SECRET")
    url = os.getenv("LIVEKIT_PUBLIC_URL") or os.getenv("LIVEKIT_URL")
    if not (key and secret and url):
        raise HTTPException(500, "LIVEKIT_URL / LIVEKIT_API_KEY / LIVEKIT_API_SECRET not configured")
    room = f"reading-{s['id']}-{uuid.uuid4().hex[:6]}"
    attrs = {"student_id": s["id"], "name": s["name"], "year": s.get("year", ""),
             "teacher": s.get("teacher", ""), "passage_id": s.get("passage_id", "")}
    tok = (
        api.AccessToken(key, secret)
        .with_identity(f"student:{s['id']}:{uuid.uuid4().hex[:4]}")
        .with_name(s["name"])
        .with_attributes(attrs)
        .with_metadata(json.dumps(attrs))
        .with_grants(api.VideoGrants(room_join=True, room=room, can_publish=True,
                                     can_subscribe=True, can_publish_data=True))
        .with_room_config(api.RoomConfiguration(agents=[
            api.RoomAgentDispatch(agent_name=AGENT_NAME, metadata=json.dumps(attrs))]))
        .to_jwt()
    )
    return {"url": url, "token": tok, "room": room, "student": s}


@app.get("/api/roster")
def roster():
    return load_roster()


@app.get("/api/passages")
def passages():
    return [{"id": p.id, "title": p.title, "level": p.level, "word_count": p.word_count}
            for p in load_passages().values()]


@app.get("/api/reports")
def list_reports(limit: int = 50):
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    files = sorted(REPORTS_DIR.glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True)[:limit]
    return [json.loads(f.read_text(encoding="utf-8")) for f in files]


@app.post("/api/reports")
async def receive_report(req: Request):
    d = await req.json()
    if not d.get("session_id"):
        raise HTTPException(400, "not a reading report")
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    name = f"{d.get('created_at', '')[:10]}_{d.get('student_id', 'unknown')}_{d['session_id']}.json"
    (REPORTS_DIR / Path(name).name).write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
    return JSONResponse({"ok": True})


@app.get("/")
def robot_screen():
    return FileResponse(ROOT / "web" / "index.html")


@app.get("/teacher")
def teacher_view():
    return FileResponse(ROOT / "web" / "teacher.html")


app.mount("/web", StaticFiles(directory=ROOT / "web"), name="web")
