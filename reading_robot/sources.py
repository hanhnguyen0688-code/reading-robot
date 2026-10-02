"""Word sources: turn the child's audio into timed words for the aligner.

Two interchangeable backends:

* ``SessionSTTSource``  - reuses the AgentSession's STT transcripts (whatever
  TomAI Voice already uses: Deepgram, Azure, Google...). Zero extra setup,
  approximate word timing, no pronunciation score.
* ``AzurePronunciationSource`` - opens a second audio stream on the child's
  mic track and runs Azure Speech *scripted pronunciation assessment*
  (reference text = the passage). Exact word timing + per-word accuracy
  score. Recommended for the pilot.

Both emit the *full* current hypothesis (finals + interim tail) through the
``on_update`` callback; the aligner re-aligns everything each time.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Callable

from .aligner import HypWord
from .text import _WORD_RE, normalise

logger = logging.getLogger("reading-robot.sources")

UpdateCb = Callable[[list[HypWord]], None]

_EST_WORD_SECONDS = 0.32


def _words(text: str) -> list[tuple[str, str]]:
    out = []
    for m in _WORD_RE.finditer(text):
        n = normalise(m.group(0))
        if n:
            out.append((n, m.group(0)))
    return out


class WordSource:
    name = "base"

    def __init__(self, on_update: UpdateCb):
        self.on_update = on_update
        self.t0 = time.monotonic()
        self.mute_windows = []

    def now(self) -> float:
        return time.monotonic() - self.t0

    # --- echo suppression: words spoken while the robot talks are ignored
    _mute_from: float | None = None
    mute_windows: list[tuple[float, float]]

    def begin_mute(self) -> None:
        if self._mute_from is None:
            self._mute_from = self.now()

    def end_mute(self, tail: float = 0.4) -> None:
        if self._mute_from is not None:
            self.mute_windows.append((self._mute_from, self.now() + tail))
            self._mute_from = None

    def is_muted(self, t: float) -> bool:
        if self._mute_from is not None and t >= self._mute_from:
            return True
        return any(a <= t <= b for a, b in self.mute_windows)

    async def start(self) -> None:  # pragma: no cover - interface
        self.t0 = time.monotonic()

    async def aclose(self) -> None:  # pragma: no cover - interface
        pass


class SessionSTTSource(WordSource):
    """Fed from AgentSession ``user_input_transcribed`` events.

    STT providers stream a growing interim transcript for the current
    utterance, then a final. Each word gets the time it was *first seen*,
    which is accurate to roughly one interim interval (100-300 ms) - good
    enough for WCPM and hesitation detection.
    """

    name = "session_stt"

    def __init__(self, on_update: UpdateCb):
        super().__init__(on_update)
        self._finals: list[HypWord] = []
        self._tail_seen: list[float] = []      # first-seen time per interim word position

    def feed(self, transcript: str, is_final: bool) -> None:
        t = self.now()
        if self.is_muted(t):
            if is_final:
                self._tail_seen = []
            return
        ws = _words(transcript)
        while len(self._tail_seen) < len(ws):
            self._tail_seen.append(t)
        tail = []
        for k, (n, raw) in enumerate(ws):
            start = self._tail_seen[k]
            tail.append(HypWord(n, start, start + _EST_WORD_SECONDS, 0.8, None, is_final, raw))
        # keep timings monotonic
        last_end = self._finals[-1].end if self._finals else 0.0
        for h in tail:
            if h.start < last_end:
                h.start, h.end = last_end, last_end + _EST_WORD_SECONDS
            last_end = h.end
        if is_final:
            self._finals.extend(tail)
            self._tail_seen = []
            self.on_update(list(self._finals))
        else:
            self.on_update(self._finals + tail)

    async def start(self) -> None:
        self.t0 = time.monotonic()


class AzurePronunciationSource(WordSource):
    """Azure Speech scripted pronunciation assessment on the child's mic track."""

    name = "azure_pronunciation"

    def __init__(self, on_update: UpdateCb, *, track, reference_text: str, key: str,
                 region: str, language: str = "en-AU", loop: asyncio.AbstractEventLoop | None = None):
        super().__init__(on_update)
        self.track = track
        self.reference_text = reference_text
        self.key, self.region, self.language = key, region, language
        self.loop = loop or asyncio.get_event_loop()
        self._finals: list[HypWord] = []
        self._pump: asyncio.Task | None = None
        self._recognizer = None
        self._push = None
        self._stopped = False

    async def start(self) -> None:
        import azure.cognitiveservices.speech as sdk
        from livekit import rtc

        self.t0 = time.monotonic()
        speech_config = sdk.SpeechConfig(subscription=self.key, region=self.region)
        speech_config.speech_recognition_language = self.language
        # Children pause mid-sentence: don't cut segments too eagerly.
        speech_config.set_property(sdk.PropertyId.Speech_SegmentationSilenceTimeoutMs, "1200")
        fmt = sdk.audio.AudioStreamFormat(samples_per_second=16000, bits_per_sample=16, channels=1)
        self._push = sdk.audio.PushAudioInputStream(stream_format=fmt)
        audio_config = sdk.audio.AudioConfig(stream=self._push)
        self._recognizer = sdk.SpeechRecognizer(speech_config=speech_config, audio_config=audio_config)

        pa = sdk.PronunciationAssessmentConfig(
            reference_text=self.reference_text,
            grading_system=sdk.PronunciationAssessmentGradingSystem.HundredMark,
            granularity=sdk.PronunciationAssessmentGranularity.Phoneme,
            enable_miscue=False,   # not supported in continuous mode - we compute miscues ourselves
        )
        pa.apply_to(self._recognizer)

        self._recognizer.recognizing.connect(self._on_recognizing)
        self._recognizer.recognized.connect(self._on_recognized)
        self._recognizer.canceled.connect(lambda e: logger.warning("azure canceled: %s", e.cancellation_details))
        self._recognizer.start_continuous_recognition_async()

        stream = rtc.AudioStream.from_track(track=self.track, sample_rate=16000, num_channels=1)
        self._pump = asyncio.create_task(self._pump_audio(stream))

    async def _pump_audio(self, stream) -> None:
        try:
            async for ev in stream:
                if self._stopped:
                    break
                self._push.write(bytes(ev.frame.data))
        finally:
            await stream.aclose()

    # Azure SDK callbacks run on its own thread -> hop back to the event loop
    def _emit(self, words: list[HypWord]) -> None:
        self.loop.call_soon_threadsafe(self.on_update, words)

    def _on_recognizing(self, evt) -> None:
        t = self.now()
        if self.is_muted(t):
            return
        tail = [HypWord(n, t, t + _EST_WORD_SECONDS, 0.5, None, False, raw) for n, raw in _words(evt.result.text)]
        # interim text covers the whole current segment; space them out backwards
        for k, h in enumerate(tail):
            h.start = max(self._finals[-1].end if self._finals else 0.0, t - (len(tail) - k) * _EST_WORD_SECONDS)
            h.end = h.start + _EST_WORD_SECONDS
        self._emit(self._finals + tail)

    def _on_recognized(self, evt) -> None:
        import azure.cognitiveservices.speech as sdk

        raw = evt.result.properties.get(sdk.PropertyId.SpeechServiceResponse_JsonResult)
        if not raw:
            return
        try:
            nbest = json.loads(raw).get("NBest") or []
            words = nbest[0].get("Words", []) if nbest else []
        except (ValueError, IndexError):
            return
        for w in words:
            pa = w.get("PronunciationAssessment", {})
            if pa.get("ErrorType") == "Omission":     # only present with miscue on; skip defensively
                continue
            n = normalise(w.get("Word", ""))
            if not n:
                continue
            start = w.get("Offset", 0) / 1e7
            end = start + w.get("Duration", 0) / 1e7
            if self.is_muted(start):
                continue
            self._finals.append(HypWord(n, start, end, w.get("Confidence", 0.9) or 0.9,
                                        pa.get("AccuracyScore"), True, w.get("Word", "")))
        self._emit(list(self._finals))

    async def aclose(self) -> None:
        self._stopped = True
        if self._recognizer is not None:
            self._recognizer.stop_continuous_recognition_async()
        if self._push is not None:
            self._push.close()
        if self._pump:
            self._pump.cancel()
