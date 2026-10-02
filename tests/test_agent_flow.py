"""Drive the real ReadingAgent / GreeterAgent with a fake LiveKit session.

No LiveKit server or speech keys needed: transcripts are injected exactly
like AgentSession's `user_input_transcribed` events would deliver them.
"""
import asyncio
import json
import types

import pytest

from reading_robot import agent as A
from reading_robot import teacher
from reading_robot.config import ReadingConfig


class FakeHandle:
    def __await__(self):
        return asyncio.sleep(0).__await__()


class FakeSession:
    def __init__(self):
        self.said, self.handlers, self.llm, self.current_agent = [], {}, None, None

    def say(self, text, **kw):
        self.said.append(text)
        return FakeHandle()

    def on(self, name, cb):
        self.handlers.setdefault(name, []).append(cb)

    def update_agent(self, agent):
        self.current_agent = agent
        asyncio.get_event_loop().create_task(_enter(agent, self))


async def _enter(agent, session):
    agent.__class__ = type(agent.__class__.__name__, (agent.__class__,), {"session": property(lambda s: session)})
    await agent.on_enter()


class FakeLP:
    def __init__(self):
        self.events = []

    async def publish_data(self, payload, topic="", reliable=True):
        self.events.append(json.loads(payload))


class FakeJob:
    def __init__(self):
        self.room = types.SimpleNamespace(local_participant=FakeLP(), remote_participants={}, name="room-test")
        self.shutdown_reason = None

    def shutdown(self, reason=""):
        self.shutdown_reason = reason


def tev(text, final):
    return types.SimpleNamespace(transcript=text, is_final=final)


@pytest.fixture(autouse=True)
def fast(monkeypatch, tmp_path):
    monkeypatch.setenv("READING_ASR_BACKEND", "session_stt")
    monkeypatch.setattr(teacher, "REPORTS_DIR", tmp_path)
    real_sleep = asyncio.sleep
    monkeypatch.setattr(A.asyncio, "sleep", lambda s: real_sleep(min(s, 0.05)))


def make(student="cathy"):
    job = FakeJob()
    s = A.find_student(student)
    cfg = ReadingConfig(finish_grace_seconds=0.3)
    rc = A.ReadingContext(job=job, student=s, passage_id=s["passage_id"], cfg=cfg)
    return job, rc, FakeSession()


async def test_greet_then_ready_hands_off_to_reading():
    job, rc, sess = make()
    g = A.GreeterAgent(rc)
    await _enter(g, sess)
    assert sess.said[0] == "Hi Cathy! Ready to read to me? I polished my ears just for you!"
    msg = types.SimpleNamespace(text_content="Ready!")
    with pytest.raises(A.StopResponse):
        await g.on_user_turn_completed(None, msg)
    await asyncio.sleep(0.1)
    assert isinstance(sess.current_agent, A.ReadingAgent)
    types_ = [e.get("state") or e["type"] for e in job.room.local_participant.events]
    assert "greet" in types_ and "reading" in types_ and "passage" in types_
    sess.current_agent._finishing = True


async def test_not_me():
    job, rc, sess = make()
    g = A.GreeterAgent(rc)
    await _enter(g, sess)
    with pytest.raises(A.StopResponse):
        await g.on_user_turn_completed(None, types.SimpleNamespace(text_content="That's not me!"))
    assert job.shutdown_reason == "wrong student"


async def test_full_read_with_help_and_finish():
    job, rc, sess = make()
    r = A.ReadingAgent(rc)
    await _enter(r, sess)
    on_tr = sess.handlers["user_input_transcribed"][0]

    utterances = [
        "Milo the cat had a big problem",
        "His favourite red sock was gone",
        "He looked under the bed",
        "He looked behind the door",
        "He even looked inside the fridge which was cold",      # skips "very"
        "Then Milo heard a tiny speak",                          # squeak -> speak
        "A mouse was sleeping in the sock",
    ]
    for u in utterances:
        words = u.split()
        for k in range(1, len(words)):                           # growing interim
            on_tr(tev(" ".join(words[:k]), False))
        on_tr(tev(u, True))
        await asyncio.sleep(0.01)

    on_tr(tev("help", True))                                     # stuck on "snoring"
    await asyncio.sleep(0.05)
    assert sess.said[-1] == "That word is snoring."
    r._on_agent_state(types.SimpleNamespace(new_state="listening"))
    await asyncio.sleep(0.5)                                     # echo tail passes
    on_tr(tev("snoring like a little train", True))

    for _ in range(60):
        if job.shutdown_reason:
            break
        await asyncio.sleep(0.1)
    assert job.shutdown_reason == "session complete"

    evs = job.room.local_participant.events
    report = next(e["report"] for e in evs if e["type"] == "report")
    assert report["total_words"] == 51 and report["errors"] == 3
    assert {(m["word"], m["type"]) for m in report["miscues"]} >= {
        ("very", "omission"), ("squeak", "substitution"), ("snoring", "told")}
    assert report["finished_reason"] == "completed"
    sent = next(e for e in evs if e["type"] == "report_sent")
    assert sent["teacher"] == "Ms. Patel" and "94%" in sent["teacher_note"]
    assert sess.said[-1].startswith("Great work Cathy. I'll send some key information to your teacher")
    assert any(e["type"] == "progress" and e["new_sentences"] for e in evs)
    assert evs[-1] == {"type": "state", "state": "sleep"}


async def test_robot_echo_is_not_scored():
    job, rc, sess = make()
    r = A.ReadingAgent(rc)
    await _enter(r, sess)
    on_tr = sess.handlers["user_input_transcribed"][0]
    r._on_agent_state(types.SimpleNamespace(new_state="speaking"))
    on_tr(tev("Milo the cat take your time say help", True))
    assert r.tracker.state.cursor == -1 and not r.tracker.aligner.told
    r._finishing = True
