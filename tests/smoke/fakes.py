"""Test doubles: scripted STT (transcripts pushed from a queue) + silent TTS."""
import asyncio, uuid
from livekit.agents import stt, tts, APIConnectOptions, DEFAULT_API_CONNECT_OPTIONS

QUEUE: "asyncio.Queue[tuple[str,bool]]" = None  # set by the agent entrypoint


class ScriptedSTT(stt.STT):
    def __init__(self):
        super().__init__(capabilities=stt.STTCapabilities(streaming=True, interim_results=True))

    async def _recognize_impl(self, buffer, *, language=None, conn_options=DEFAULT_API_CONNECT_OPTIONS):
        raise NotImplementedError

    def stream(self, *, language=None, conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS):
        return _Stream(stt=self, conn_options=conn_options)


class _Stream(stt.RecognizeStream):
    async def _run(self) -> None:
        async def drain():
            async for _ in self._input_ch:
                pass
        d = asyncio.create_task(drain())
        speaking = False
        try:
            while True:
                text, final = await QUEUE.get()
                if not speaking:
                    self._event_ch.send_nowait(stt.SpeechEvent(type=stt.SpeechEventType.START_OF_SPEECH))
                    speaking = True
                alt = [stt.SpeechData(language="en-AU", text=text, confidence=0.9)]
                kind = stt.SpeechEventType.FINAL_TRANSCRIPT if final else stt.SpeechEventType.INTERIM_TRANSCRIPT
                self._event_ch.send_nowait(stt.SpeechEvent(type=kind, alternatives=alt))
                if final:
                    self._event_ch.send_nowait(stt.SpeechEvent(type=stt.SpeechEventType.END_OF_SPEECH))
                    speaking = False
        finally:
            d.cancel()


class SilentTTS(tts.TTS):
    def __init__(self):
        super().__init__(capabilities=tts.TTSCapabilities(streaming=False), sample_rate=24000, num_channels=1)

    def synthesize(self, text, *, conn_options=DEFAULT_API_CONNECT_OPTIONS):
        return _Chunk(tts=self, input_text=text, conn_options=conn_options)


class _Chunk(tts.ChunkedStream):
    async def _run(self, output_emitter) -> None:
        output_emitter.initialize(request_id=uuid.uuid4().hex, sample_rate=24000, num_channels=1, mime_type="audio/pcm")
        secs = min(2.0, 0.03 * len(self.input_text))          # short "speech"
        output_emitter.push(b"\x00\x00" * int(24000 * secs))
        output_emitter.flush()
