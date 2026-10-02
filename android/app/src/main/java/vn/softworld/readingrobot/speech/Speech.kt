package vn.softworld.readingrobot.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "ReadingRobotSpeech"

/** A word with its time inside the utterance (seconds), when the recogniser provides it. */
data class TimedWord(val word: String, val start: Double, val end: Double, val conf: Double)

interface SpeechCallback {
    fun onPartial(text: String)
    fun onFinal(text: String, words: List<TimedWord>?)
    fun onLevel(level: Float)
    fun onState(state: String)            // listening | idle | starting | error:<code>
    fun onError(code: String)             // mic-denied | no-recognizer | network | model
}

interface SpeechEngine {
    val name: String
    fun start(lang: String, cb: SpeechCallback)
    fun stop()
    /** Pause while the robot talks so its own voice is never scored. */
    fun pause(paused: Boolean)
    fun release()
}

// ===================================================================== Google
/** Android's built-in recogniser (Google / Samsung). Restarts after every utterance to listen continuously. */
class GoogleSpeechEngine(private val context: Context) : SpeechEngine {
    override val name = "android_speechrecognizer"
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var cb: SpeechCallback? = null
    private var lang = "en-AU"
    private var wanted = false
    private var paused = false
    private var lastLevelAt = 0L
    private var networkErrors = 0

    companion object {
        fun available(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun start(lang: String, cb: SpeechCallback) {
        main.post { this.lang = lang; this.cb = cb; wanted = true; paused = false; begin() }
    }

    override fun stop() { main.post { wanted = false; cancel(); cb?.onState("idle") } }

    override fun pause(paused: Boolean) {
        main.post {
            this.paused = paused
            if (paused) cancel() else if (wanted) main.postDelayed({ begin() }, 150)
        }
    }

    override fun release() { main.post { wanted = false; recognizer?.destroy(); recognizer = null; muteBeep(false) } }

    private fun begin() {
        if (!wanted || paused) return
        if (!available(context)) { cb?.onError("no-recognizer"); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener); recognizer = it }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // children pause mid-sentence: ask for a longer silence before the utterance ends
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        muteBeep(true)
        try { r.startListening(i) } catch (e: Exception) { Log.w(TAG, "startListening", e); restart(500) }
    }

    private fun cancel() { try { recognizer?.cancel() } catch (_: Exception) { } }

    private fun restart(ms: Long) { main.postDelayed({ if (wanted && !paused) begin() }, ms) }

    private fun deliver(b: Bundle?, final: Boolean) {
        if (paused) return
        val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (final) cb?.onFinal(text, null) else cb?.onPartial(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { cb?.onState("listening") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val now = System.currentTimeMillis(); if (now - lastLevelAt < 120) return
            lastLevelAt = now; cb?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) = deliver(partialResults, false)
        override fun onResults(results: Bundle?) { networkErrors = 0; deliver(results, true); restart(50) }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { cb?.onError("mic-denied"); return }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy(); recognizer = null; restart(600); return      // stuck: start fresh
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    if (++networkErrors >= 3) { networkErrors = 0; cb?.onError("network"); return }
                    restart(1500); return
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { restart(150); return }   // normal pauses
                else -> { cb?.onState("error:$error"); restart(800) }
            }
        }
    }

    /** Silence the recogniser's start/stop beep (it plays on the notification stream). */
    private fun muteBeep(mute: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) { }
    }
}

// ====================================================================== Vosk
/**
 * Offline recogniser bundled in the app (Vosk, English model in assets/model-en-us).
 * Works with no Google services and no internet, and gives word start/end times.
 */
class VoskSpeechEngine(private val context: Context) : SpeechEngine {
    override val name = "vosk_offline"
    private val main = Handler(Looper.getMainLooper())
    private var service: SpeechService? = null
    private var cb: SpeechCallback? = null
    private var lastPartial = ""

    companion object {
        @Volatile var model: Model? = null; private set
        @Volatile var loadError: String? = null; private set
        private val waiting = mutableListOf<() -> Unit>()
        @Volatile private var loading = false

        /** Unpack the model from assets once (a few seconds the first time), then keep it in memory. */
        fun preload(context: Context, done: (() -> Unit)? = null) {
            synchronized(this) {
                if (model != null) { done?.invoke(); return }
                done?.let { waiting += it }
                if (loading) return
                loading = true
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            StorageService.unpack(context.applicationContext, "model-en-us", "model",
                { m -> synchronized(this) { model = m; loading = false; val w = waiting.toList(); waiting.clear(); w } .forEach { it() } },
                { e -> Log.e(TAG, "vosk model", e); synchronized(this) { loadError = e.message ?: "model"; loading = false; val w = waiting.toList(); waiting.clear(); w }.forEach { it() } })
        }
    }

    override fun start(lang: String, cb: SpeechCallback) {
        this.cb = cb
        cb.onState("starting")
        preload(context) { main.post { open() } }
    }

    private fun open() {
        val m = model ?: run { cb?.onError("model"); return }
        try {
            service?.shutdown()
            val rec = Recognizer(m, 16000f).apply { setWords(true) }
            service = SpeechService(rec, 16000f).also { it.startListening(listener) }
            cb?.onState("listening")
        } catch (e: Exception) {
            Log.e(TAG, "vosk start", e)
            cb?.onError(if (e.message?.contains("permission", true) == true) "mic-denied" else "model")
        }
    }

    override fun stop() { main.post { service?.stop(); service?.shutdown(); service = null; cb?.onState("idle") } }
    override fun pause(paused: Boolean) { main.post { service?.setPause(paused); if (!paused) lastPartial = "" } }
    override fun release() { stop() }

    private val listener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val t = hypothesis?.let { JSONObject(it).optString("partial") }.orEmpty().trim()
            if (t.isNotEmpty() && t != lastPartial) { lastPartial = t; cb?.onLevel(0.7f); cb?.onPartial(t) }
        }
        override fun onResult(hypothesis: String?) = final(hypothesis)
        override fun onFinalResult(hypothesis: String?) = final(hypothesis)
        override fun onError(exception: Exception?) { Log.e(TAG, "vosk", exception); cb?.onState("error:vosk") }
        override fun onTimeout() {}
    }

    private fun final(json: String?) {
        lastPartial = ""
        val o = json?.let { JSONObject(it) } ?: return
        val text = o.optString("text").trim(); if (text.isEmpty()) return
        val arr = o.optJSONArray("result")
        val words = if (arr == null) null else (0 until arr.length()).map {
            val w = arr.getJSONObject(it); TimedWord(w.getString("word"), w.getDouble("start"), w.getDouble("end"), w.optDouble("conf", 1.0))
        }
        cb?.onFinal(text, words)
    }
}

// ======================================================================= TTS
/** The robot's voice. speak() suspends until the sentence has been said (with a safety timeout). */
class RobotVoice(context: Context) {
    @Volatile private var ready = false
    private val pending = HashMap<String, () -> Unit>()
    private var seq = 0
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.language = Locale("en", "AU")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { finish(utteranceId) }
        })
    }

    private fun finish(id: String?) { val f = synchronized(pending) { pending.remove(id) }; f?.invoke() }

    suspend fun speak(text: String, locale: String, rate: Float, pitch: Float) {
        if (!ready) { kotlinx.coroutines.delay(400); if (!ready) return }
        val loc = Locale.forLanguageTag(locale)
        if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.language = loc
        tts.setSpeechRate(rate); tts.setPitch(pitch)
        val id = "u${++seq}"
        withTimeoutOrNull(2500L + text.length * 90L) {
            suspendCancellableCoroutine { cont ->
                synchronized(pending) { pending[id] = { if (cont.isActive) cont.resume(Unit) } }
                cont.invokeOnCancellation { synchronized(pending) { pending.remove(id) } }
                val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
            }
        }
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.shutdown() }
}
