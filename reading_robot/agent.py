"""Reading Robot agents for LiveKit Agents (>=1.x).

Flow (client script):
  GreeterAgent   1. "Hi Cathy! Ready to read to me?"  -> "Ready!" / raise hand / tap
  ReadingAgent   2. passage on screen  3. child reads (robot listens, never chats)
                 4. "Great work Cathy. I'll send some key information to your teacher..."

Key design rule: during reading the LLM is OFF. Scores come from the aligner;
the LLM only phrases the teacher note afterwards (with a fact check).

Plug-in options for an existing TomAI Voice agent:
  * run this worker as-is (agent_name="reading-robot"), or
  * from your own agent:  ``self.session.update_agent(ReadingAgent(ctx_data))``
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from dataclasses import dataclass, field

from livekit import rtc
from livekit.agents import Agent, AgentSession, JobContext, RunContext, StopResponse, function_tool, llm

from .config import ReadingConfig
from .passages import find_student, load_passages, passage_payload
from .scoring import LiveTracker, build_report
from .sources import AzurePronunciationSource, SessionSTTSource, WordSource
from .teacher import (NOT_ME_LINE, START_LINE, STALL_LINES, closing_line,
                      greeting_line, llm_teacher_note, post_webhook, save_report, told_line)
from .text import split_hypothesis

logger = logging.getLogger("reading-robot")

TOPIC = "reading-robot"
READY_WORDS = {"ready", "yes", "yeah", "yep", "ok", "okay", "start", "go"}
NOT_ME_PHRASES = ("not me", "thats not me", "wrong name", "im not")


@dataclass
class ReadingContext:
    job: JobContext
    student: dict
    passage_id: str
    cfg: ReadingConfig = field(default_factory=ReadingConfig)
    child_identity: str | None = None
    phase: str = "greet"

    async def publish(self, event: dict) -> None:
        """Send a UI event to the robot screen."""
        try:
            await self.job.room.local_participant.publish_data(
                json.dumps(event, ensure_ascii=False), topic=TOPIC, reliable=True)
        except Exception as e:  # noqa: BLE001
            logger.debug("publish failed: %s", e)


# =========================================================================== 1
class GreeterAgent(Agent):
    def __init__(self, rc: ReadingContext):
        name = rc.student.get("name", "friend")
        super().__init__(
            instructions=(
                f"You are Reading Robot, a friendly robot in an Australian primary classroom. "
                f"You are talking to {name}, a {rc.student.get('year', 'young')} student. "
                "Your ONLY goal right now is to get them ready to read a short story to you. "
                "Reply in ONE short, cheerful sentence a 7-year-old understands. "
                "If they say they are ready (or yes/okay), call start_reading. "
                "If they say they are not this student, call not_me. "
                "Never ask personal questions. Never talk about anything else for long."
            ),
        )
        self.rc = rc

    async def on_enter(self) -> None:
        self.rc.phase = "greet"
        await self.rc.publish({"type": "state", "state": "greet", "student": self.rc.student})
        line = greeting_line(self.rc.student.get("name", "friend"))
        await self.rc.publish({"type": "robot_says", "text": line})
        self.session.say(line)

    async def on_user_turn_completed(self, turn_ctx: llm.ChatContext, new_message: llm.ChatMessage) -> None:
        text = (new_message.text_content or "").lower()
        await self.rc.publish({"type": "user_says", "text": new_message.text_content})
        # Deterministic fast path - no LLM round-trip for the common answers.
        if any(p in text.replace("'", "").replace("’", "") for p in NOT_ME_PHRASES):
            await self._not_me()
            raise StopResponse()
        if set(split_hypothesis(text)) & READY_WORDS:
            self.session.update_agent(ReadingAgent(self.rc))
            raise StopResponse()
        # otherwise let the LLM give one short nudge

    @function_tool()
    async def start_reading(self, context: RunContext) -> Agent:
        """The student says they are ready to read."""
        return ReadingAgent(self.rc)

    @function_tool()
    async def not_me(self, context: RunContext) -> str:
        """The student says they are not the person the robot greeted."""
        await self._not_me()
        return "Told the child to fetch the teacher."

    async def _not_me(self) -> None:
        self.rc.phase = "not_me"
        await self.rc.publish({"type": "state", "state": "not_me"})
        await self.rc.publish({"type": "robot_says", "text": NOT_ME_LINE})
        handle = self.session.say(NOT_ME_LINE, allow_interruptions=False)
        await handle
        await asyncio.sleep(2)
        self.rc.job.shutdown(reason="wrong student")


# ======================================================================= 2 + 3
class ReadingAgent(Agent):
    def __init__(self, rc: ReadingContext):
        super().__init__(
            instructions="You are listening to a child read. Do not speak.",
            allow_interruptions=False,
        )
        self.rc = rc
        self.passage = load_passages()[rc.passage_id]
        self.tracker = LiveTracker(self.passage, rc.cfg)
        self.source: WordSource | None = None
        self.stt_source: SessionSTTSource | None = None
        self._finishing = False
        self._started_at = 0.0
        self._last_speech_at = 0.0
        self._last_help_at = 0.0
        self._stall_prompts = 0
        self._watchdog: asyncio.Task | None = None
        self._end_reached_at: float | None = None

    # ---------------------------------------------------------------- lifecycle
    async def on_enter(self) -> None:
        self.rc.phase = "reading"
        await self.rc.publish({"type": "state", "state": "reading"})
        await self.rc.publish(passage_payload(self.passage))
        await self.rc.publish({"type": "robot_says", "text": START_LINE})
        await self.session.say(START_LINE, allow_interruptions=False)

        self.stt_source = SessionSTTSource(self._on_words)
        backend = os.getenv("READING_ASR_BACKEND", "azure_pronunciation")
        track = self._child_audio_track()
        if backend == "azure_pronunciation" and track and os.getenv("AZURE_SPEECH_KEY"):
            self.source = AzurePronunciationSource(
                self._on_words, track=track, reference_text=self.passage.text,
                key=os.environ["AZURE_SPEECH_KEY"], region=os.environ["AZURE_SPEECH_REGION"],
                language=os.getenv("READING_LOCALE", "en-AU"))
        else:
            if backend == "azure_pronunciation":
                logger.warning("Azure PA unavailable (no key or no track) - falling back to session STT")
            self.source = self.stt_source
        await self.source.start()
        if self.source is not self.stt_source:
            await self.stt_source.start()   # still used for "Help" detection
        logger.info("reading started with backend=%s", self.source.name)

        self._started_at = self._last_speech_at = time.monotonic()
        self.session.on("user_input_transcribed", self._on_transcript)
        self.session.on("agent_state_changed", self._on_agent_state)
        self._watchdog = asyncio.create_task(self._watch())

    async def on_user_turn_completed(self, turn_ctx: llm.ChatContext, new_message: llm.ChatMessage) -> None:
        raise StopResponse()     # never chat while the child is reading

    # ------------------------------------------------------------------ inputs
    def _child_audio_track(self) -> rtc.Track | None:
        room = self.rc.job.room
        for p in room.remote_participants.values():
            if self.rc.child_identity and p.identity != self.rc.child_identity:
                continue
            for pub in p.track_publications.values():
                if pub.kind == rtc.TrackKind.KIND_AUDIO and pub.track is not None:
                    return pub.track
        return None

    def _on_agent_state(self, ev) -> None:
        # Don't let the robot's own voice (echo) be scored as reading.
        state = getattr(ev, "new_state", None)
        for src in {self.source, self.stt_source}:
            if src is None:
                continue
            if state == "speaking":
                src.begin_mute()
            elif state in ("listening", "idle", "thinking"):
                src.end_mute(tail=0.4)

    def _on_transcript(self, ev) -> None:
        if self._finishing or (self.stt_source and self.stt_source.is_muted(self.stt_source.now())):
            return
        self._last_speech_at = time.monotonic()
        words = split_hypothesis(ev.transcript)
        if "help" in words and len(words) <= 3:
            asyncio.create_task(self.handle_help())
        if self.source is self.stt_source:
            self.stt_source.feed(ev.transcript, ev.is_final)

    def _on_words(self, hyp) -> None:
        if self._finishing:
            return
        self._last_speech_at = time.monotonic()
        event = self.tracker.update(hyp, now=time.monotonic())
        asyncio.create_task(self.rc.publish(event))
        if self.tracker.reached_end() and self._end_reached_at is None:
            self._end_reached_at = time.monotonic()

    async def handle_help(self) -> None:
        """Child said 'Help!' (or tapped the button): robot reads the next word."""
        now = time.monotonic()
        if self._finishing or now - self._last_help_at < self.rc.cfg.help_debounce_seconds:
            return
        idx = self.tracker.state.cursor + 1
        if idx >= self.passage.word_count:
            return
        if "help" == self.passage.words[idx].norm:
            return   # the child is reading the word "help"
        self._last_help_at = now
        self.tracker.mark_told(idx)
        word = self.passage.words[idx].norm
        await self.rc.publish({"type": "robot_says", "text": told_line(word), "told_index": idx})
        await self.rc.publish(self.tracker.refresh())
        await self.session.say(told_line(word), allow_interruptions=False, add_to_chat_ctx=False)

    # ---------------------------------------------------------------- watchdog
    async def _watch(self) -> None:
        cfg = self.rc.cfg
        while not self._finishing:
            await asyncio.sleep(0.5)
            now = time.monotonic()
            idle = now - max(self.tracker.last_progress_at, self._started_at)
            silent = now - self._last_speech_at
            ratio = (self.tracker.state.cursor + 1) / self.passage.word_count
            if self._end_reached_at and now - self._end_reached_at >= cfg.finish_grace_seconds:
                await self.finish("completed"); return
            if ratio >= cfg.early_finish_ratio and silent >= cfg.early_finish_silence:
                await self.finish("stopped_near_end"); return
            if now - self._started_at >= cfg.max_session_seconds:
                await self.finish("time_limit"); return
            if idle >= cfg.stall_finish_seconds and silent >= cfg.stall_finish_seconds:
                await self.finish("stalled"); return
            if idle >= cfg.stall_prompt_seconds * (self._stall_prompts + 1) and self._stall_prompts < len(STALL_LINES):
                line = STALL_LINES[self._stall_prompts]
                self._stall_prompts += 1
                await self.rc.publish({"type": "robot_says", "text": line})
                self.session.say(line, allow_interruptions=False, add_to_chat_ctx=False)

    # ================================================================== 4
    async def finish(self, reason: str) -> None:
        if self._finishing:
            return
        self._finishing = True
        self.rc.phase = "finish"
        for src in {self.source, self.stt_source}:
            if src:
                await src.aclose()

        report = build_report(self.tracker, student=self.rc.student, finished_reason=reason,
                              session_id=self.rc.job.room.name, asr_backend=self.source.name if self.source else "")
        name = self.rc.student.get("name", "friend")
        await self.rc.publish({"type": "state", "state": "finish"})
        await self.rc.publish({"type": "report", "report": report.to_dict()})
        await self.rc.publish({"type": "robot_says", "text": closing_line(name)})
        speech = self.session.say(closing_line(name), allow_interruptions=False)

        # teacher note + delivery run while the robot is talking
        report.teacher_note = await llm_teacher_note(report, self.session.llm) if self.session.llm else ""
        if not report.teacher_note:
            from .teacher import fallback_teacher_note
            report.teacher_note = fallback_teacher_note(report)
        path = save_report(report)
        sent = await post_webhook(report)
        logger.info("report saved %s (webhook=%s) acc=%.1f wcpm=%s", path, sent, report.accuracy_pct, report.wcpm)
        await self.rc.publish({"type": "report_sent", "teacher": report.teacher, "webhook": sent,
                               "teacher_note": report.teacher_note})

        await speech
        await asyncio.sleep(8)           # "Robot nap in 7s..."
        await self.rc.publish({"type": "state", "state": "sleep"})
        self.rc.job.shutdown(reason="session complete")


# ============================================================== entry helpers
def resolve_student(ctx: JobContext, participant: rtc.RemoteParticipant) -> dict:
    """Student comes from the token (participant attributes / metadata) or job metadata."""
    data: dict = {}
    for blob in (ctx.job.metadata, participant.metadata):
        if blob:
            try:
                data.update(json.loads(blob))
            except ValueError:
                pass
    data.update({k: v for k, v in (participant.attributes or {}).items() if v})
    sid = data.get("student_id") or data.get("id") or participant.identity.split(":")[-1]
    student = find_student(sid) or {}
    student = {**student, **{k: data[k] for k in ("name", "year", "teacher", "passage_id") if k in data}}
    student.setdefault("id", sid)
    student.setdefault("name", sid.title())
    student.setdefault("passage_id", "mission-7")
    return student


def register_rpc(ctx: JobContext, session: AgentSession, rc: ReadingContext) -> None:
    """Touch / gesture inputs from the robot screen."""
    lp = ctx.room.local_participant

    @lp.register_rpc_method("start_reading")
    async def _start(data: rtc.RpcInvocationData) -> str:   # "Raise your hand" / tap
        if rc.phase == "greet":
            session.update_agent(ReadingAgent(rc))
            return "ok"
        return rc.phase

    @lp.register_rpc_method("not_me")
    async def _not_me(data: rtc.RpcInvocationData) -> str:
        agent = session.current_agent
        if isinstance(agent, GreeterAgent):
            await agent._not_me()
        return "ok"

    @lp.register_rpc_method("help")
    async def _help(data: rtc.RpcInvocationData) -> str:
        agent = session.current_agent
        if isinstance(agent, ReadingAgent):
            await agent.handle_help()
        return "ok"

    @lp.register_rpc_method("finish")
    async def _finish(data: rtc.RpcInvocationData) -> str:  # teacher / "I'm done" button
        agent = session.current_agent
        if isinstance(agent, ReadingAgent):
            await agent.finish("manual")
        return "ok"
