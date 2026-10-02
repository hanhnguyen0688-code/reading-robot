"""Real LiveKit worker using the production agent classes, with fake speech I/O."""
import asyncio, json, os, sys, logging
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
os.environ.setdefault("READING_ASR_BACKEND", "session_stt")
from dotenv import load_dotenv; load_dotenv()
from livekit.agents import AgentServer, AgentSession, JobContext, cli
from livekit import rtc
import fakes
from reading_robot.agent import GreeterAgent, ReadingContext, register_rpc, resolve_student
from reading_robot.config import ReadingConfig

server = AgentServer(http_proxy=None)  # sandbox: bypass corporate proxy for local server

@server.rtc_session(agent_name="reading-robot")
async def entrypoint(ctx: JobContext):
    await ctx.connect()
    fakes.QUEUE = asyncio.Queue()
    @ctx.room.on("data_received")
    def _d(pkt: rtc.DataPacket):
        if pkt.topic == "fake-stt":
            d = json.loads(pkt.data); fakes.QUEUE.put_nowait((d["text"], d["final"]))
    p = await ctx.wait_for_participant()
    student = resolve_student(ctx, p)
    rc = ReadingContext(job=ctx, student=student, passage_id=student["passage_id"],
                        cfg=ReadingConfig(finish_grace_seconds=0.8), child_identity=p.identity)
    session = AgentSession(stt=fakes.ScriptedSTT(), tts=fakes.SilentTTS(), llm=None, vad=None,
                           turn_detection="stt", user_away_timeout=None, min_endpointing_delay=0.2)
    register_rpc(ctx, session, rc)
    await session.start(agent=GreeterAgent(rc), room=ctx.room)

if __name__ == "__main__":
    cli.run_app(server)
