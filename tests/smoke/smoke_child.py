"""Plays 'Cathy': joins via the token server, publishes a mic track, sends scripted
transcripts, uses the Help RPC, and checks the robot-screen events."""
import asyncio, json, sys, time, urllib.request
import numpy as np
from livekit import rtc

EVENTS = []

async def main():
    tok = json.load(urllib.request.urlopen("http://localhost:8000/api/token?student=cathy"))
    room = rtc.Room()
    @room.on("data_received")
    def _d(pkt):
        if pkt.topic == "reading-robot":
            ev = json.loads(pkt.data); EVENTS.append(ev)
            if ev["type"] != "progress": print("  <-", json.dumps(ev)[:150])
    await room.connect(tok["url"], tok["token"])
    src = rtc.AudioSource(16000, 1)
    await room.local_participant.publish_track(rtc.LocalAudioTrack.create_audio_track("mic", src),
                                               rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
    async def silence():
        f = rtc.AudioFrame.create(16000, 1, 160)
        while True:
            await src.capture_frame(f); await asyncio.sleep(0.01)
    asyncio.create_task(silence())

    async def wait(pred, t=20):
        end = time.time() + t
        while time.time() < end:
            if any(pred(e) for e in EVENTS): return True
            await asyncio.sleep(0.1)
        raise TimeoutError("waited for event")

    async def say(text, interim=True):
        words = text.split()
        if interim:
            for k in range(1, len(words)):
                await room.local_participant.publish_data(json.dumps({"text": " ".join(words[:k]), "final": False}), topic="fake-stt")
                await asyncio.sleep(0.08)
        await room.local_participant.publish_data(json.dumps({"text": text, "final": True}), topic="fake-stt")
        await asyncio.sleep(0.4)

    await wait(lambda e: e.get("state") == "greet")
    await asyncio.sleep(2.5)
    await say("Ready!", interim=False)
    await wait(lambda e: e["type"] == "passage")
    await asyncio.sleep(2.5)                      # start line
    for u in ["Milo the cat had a big problem", "His fav favourite red sock was gone", "He looked under the bed",
              "He looked behind the door", "He even looked inside the fridge which was cold",
              "Then Milo heard a tiny speak", "A moose mouse was sleeping in the sock"]:
        await say(u)
    agent = next(p.identity for p in room.remote_participants.values() if p.kind == rtc.ParticipantKind.PARTICIPANT_KIND_AGENT)
    print("  rpc help ->", await room.local_participant.perform_rpc(destination_identity=agent, method="help", payload="{}"))
    await asyncio.sleep(2.0)
    await say("snoring like a little train")
    await wait(lambda e: e["type"] == "report", 30)
    await wait(lambda e: e["type"] == "report_sent", 15)
    r = next(e["report"] for e in EVENTS if e["type"] == "report")
    print("REPORT", {k: r[k] for k in ("words_attempted","errors","self_corrections","accuracy_pct","accuracy_band","finished_reason","asr_backend")})
    print("MISCUES", [(m["word"], m["type"]) for m in r["miscues"]])
    await wait(lambda e: e.get("state") == "sleep", 20)
    print("SMOKE OK", len(EVENTS), "events")
    await room.disconnect()

asyncio.run(main())
