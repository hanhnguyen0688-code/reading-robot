"""Reading Robot worker.

    python agent_main.py dev        # local dev, hot reload
    python agent_main.py start      # production

Provider choice is env-driven so it can match the existing TomAI Voice stack.
"""
from __future__ import annotations

import logging
import os

from dotenv import load_dotenv
from livekit.agents import AgentServer, AgentSession, JobContext, JobProcess, cli
from livekit.plugins import silero

from reading_robot.agent import GreeterAgent, ReadingContext, register_rpc, resolve_student
from reading_robot.config import ReadingConfig

load_dotenv()
logger = logging.getLogger("reading-robot")

server = AgentServer()


def prewarm(proc: JobProcess) -> None:
    # Kids pause between words: longer silence before VAD ends a turn.
    proc.userdata["vad"] = silero.VAD.load(min_silence_duration=0.8)


server.setup_fnc = prewarm


def build_stt():
    provider = os.getenv("STT_PROVIDER", "azure")
    locale = os.getenv("READING_LOCALE", "en-AU")
    if provider == "deepgram":
        from livekit.plugins import deepgram
        # NOTE: no keyword boosting with passage words - it would hide real misreadings.
        return deepgram.STT(model=os.getenv("DEEPGRAM_MODEL", "nova-3"), language=locale,
                            interim_results=True, smart_format=False, punctuate=False, filler_words=True)
    from livekit.plugins import azure
    return azure.STT(language=locale)


def build_tts():
    provider = os.getenv("TTS_PROVIDER", "azure")
    if provider == "openai":
        from livekit.plugins import openai
        return openai.TTS(model="gpt-4o-mini-tts", voice=os.getenv("TTS_VOICE", "coral"),
                          instructions="Speak like a warm, playful robot friend talking to a 7-year-old. Clear and slow.")
    from livekit.plugins import azure
    return azure.TTS(voice=os.getenv("TTS_VOICE", "en-AU-NatashaNeural"), language="en-AU")


def build_llm():
    from livekit.plugins import openai
    return openai.LLM(model=os.getenv("LLM_MODEL", "gpt-4o-mini"), temperature=0.4)


@server.rtc_session(agent_name=os.getenv("AGENT_NAME", "reading-robot"))
async def entrypoint(ctx: JobContext) -> None:
    await ctx.connect()
    participant = await ctx.wait_for_participant()
    student = resolve_student(ctx, participant)
    logger.info("reader: %s passage=%s", student.get("name"), student.get("passage_id"))

    rc = ReadingContext(job=ctx, student=student, passage_id=student["passage_id"],
                        cfg=ReadingConfig(), child_identity=participant.identity)
    session = AgentSession(
        vad=ctx.proc.userdata["vad"],
        stt=build_stt(),
        llm=build_llm(),
        tts=build_tts(),
        user_away_timeout=None,      # silence while reading is normal
    )
    register_rpc(ctx, session, rc)
    await session.start(agent=GreeterAgent(rc), room=ctx.room)


if __name__ == "__main__":
    cli.run_app(server)
