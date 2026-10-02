package vn.softworld.readingrobot.session

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.softworld.readingrobot.data.PassageDef
import vn.softworld.readingrobot.data.Store
import vn.softworld.readingrobot.engine.HypWord
import vn.softworld.readingrobot.engine.LiveTracker
import vn.softworld.readingrobot.engine.Passage
import vn.softworld.readingrobot.engine.Progress
import vn.softworld.readingrobot.engine.ReadingConfig
import vn.softworld.readingrobot.engine.ReadingReport
import vn.softworld.readingrobot.engine.Reports
import vn.softworld.readingrobot.engine.Student
import vn.softworld.readingrobot.engine.Text
import vn.softworld.readingrobot.speech.GoogleSpeechEngine
import vn.softworld.readingrobot.speech.RobotVoice
import vn.softworld.readingrobot.speech.SpeechCallback
import vn.softworld.readingrobot.speech.SpeechEngine
import vn.softworld.readingrobot.speech.TimedWord
import vn.softworld.readingrobot.speech.VoskSpeechEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

enum class Screen { HOME, GREET, READING, FINISH, SLEEP, NOT_ME }

data class UiState(
    val screen: Screen = Screen.HOME,
    val student: Student? = null,
    val passage: Passage? = null,
    val progress: Progress? = null,
    val liveStarted: Boolean = false,       // first word recognised: 3b -> 3c
    val robotLine: String = "",
    val comment: String = "",
    val toast: String = "",
    val toastId: Int = 0,
    val mic: String = "idle",
    val level: Float = 0f,
    val heard: String = "",
    val talking: Boolean = false,
    val report: ReadingReport? = null,
    val reportSent: Boolean = false,
    val error: String? = null,
    val napLeft: Int = 0,
    val engineName: String = "",
    val dataVersion: Int = 0,
)

object Lines {
    fun greeting(n: String) = "Hi $n! Ready to read to me? I polished my ears just for you!"
    const val START = "Start reading out loud whenever you're ready! I'm all ears... well, all microphones."
    fun closing(n: String) = "Great work $n. I'll send some key information to your teacher so they know how you went. You can head back to your desk now."
    const val NOT_ME = "Oops, sorry! Please ask your teacher to send the right reader to me."
    const val RETRY = "When you're ready, just say Ready, or tap the button!"
    val STALL = listOf("Take your time. If a word is tricky, just say Help!", "You're doing great. Keep going, or say Help if you're stuck.")
    const val CANT_HEAR = "Hmm, I can't hear you yet. Read a little louder, or tap my ear and try again."
    fun told(w: String) = "That word is $w."
    val CHEERS = listOf("Nice reading!", "You're on a roll!", "Keep going...", "Ooh, what happens next?")
}

private val READY = setOf("ready", "yes", "yeah", "yep", "ok", "okay", "start", "go")
private val NOT_ME = listOf("not me", "thats not me", "wrong name", "im not")
private const val EST = 0.32

/** The Reading Robot session (same flow as the LiveKit agent), plus access to on-device data for the teacher area. */
class ReadingViewModel(app: Application) : AndroidViewModel(app), SpeechCallback {
    val store = Store(app)
    private val voice = RobotVoice(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var engine: SpeechEngine? = null
    private var phase = "idle"
    private var cfg = ReadingConfig()
    private var tracker: LiveTracker? = null
    private var finals = mutableListOf<HypWord>()
    private var tailSeen = mutableListOf<Double>()
    private var t0 = 0.0
    private var muteUntil = 0L
    private var speaking = false
    private var heardAny = false
    private var lastSpeechAt = 0L
    private var startedAt = 0L
    private var lastHelpAt = 0L
    private var stallPrompts = 0
    private var endReachedAt: Long? = null
    private val jobs = mutableListOf<Job>()

    init { VoskSpeechEngine.preload(app) }

    private fun nowMs() = SystemClock.elapsedRealtime()
    private fun clock() = nowMs() / 1000.0 - t0
    private fun set(f: (UiState) -> UiState) = _ui.update(f)
    fun bumpData() = set { it.copy(dataVersion = it.dataVersion + 1) }

    // ------------------------------------------------------------ engine
    private fun online(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun chooseEngine(): SpeechEngine {
        val app = getApplication<Application>()
        return when (store.settings.engine) {
            "google" -> GoogleSpeechEngine(app)
            "offline" -> VoskSpeechEngine(app)
            else -> if (GoogleSpeechEngine.available(app) && online()) GoogleSpeechEngine(app) else VoskSpeechEngine(app)
        }
    }

    private fun startListening() {
        heardAny = false
        val e = engine ?: chooseEngine().also { engine = it }
        set { it.copy(engineName = e.name) }
        e.start(store.settings.locale, this)
    }

    /** Auto mode: if Google can't work here, switch to the offline ears without stopping the session. */
    private fun fallBackToOffline(): Boolean {
        if (store.settings.engine != "auto" || engine is VoskSpeechEngine) return false
        engine?.release(); engine = VoskSpeechEngine(getApplication())
        set { it.copy(engineName = engine!!.name, comment = "Switching to my offline ears...") }
        engine!!.start(store.settings.locale, this)
        return true
    }

    // ----------------------------------------------------------- talking
    private suspend fun say(text: String) {
        set { it.copy(robotLine = text, talking = true, comment = if (it.screen == Screen.READING) text else it.comment) }
        speaking = true; engine?.pause(true)
        try { voice.speak(text, store.settings.locale, store.settings.speechRate, store.settings.pitch) }
        finally {
            speaking = false; muteUntil = nowMs() + 450
            set { it.copy(talking = false) }
            delay(300); engine?.pause(false)
        }
    }
    private fun muted() = speaking || nowMs() < muteUntil

    private fun later(ms: Long, f: suspend () -> Unit) { jobs += viewModelScope.launch { delay(ms); f() } }
    private fun clearJobs() { jobs.forEach { it.cancel() }; jobs.clear() }

    // ------------------------------------------------------------ 1. greet
    fun startFor(studentId: String) {
        val s = store.student(studentId) ?: return
        val def = store.passage(s.passageId)
        if (def == null) { showError("${s.name} has no passage yet. Ask your teacher to pick one."); return }
        clearJobs(); engine?.release(); engine = null
        val st = store.settings
        cfg = ReadingConfig(showErrorsLive = st.showErrorsLive, hesitationSeconds = st.hesitationSeconds)
        val passage = Passage.build(def.id, def.title, def.text, def.level, def.mission, def.reactions)
        phase = "greet"
        set { UiState(screen = Screen.GREET, student = s, passage = passage, dataVersion = it.dataVersion) }
        startListening()
        viewModelScope.launch { say(Lines.greeting(s.name)) }
        later(20_000) { if (phase == "greet") say(Lines.RETRY) }
        later(60_000) { if (phase == "greet") end() }
    }

    fun tap(action: String) {
        when (action) {
            "start_reading" -> if (phase == "greet") startReading()
            "not_me" -> if (phase == "greet") notMe()
            "help" -> if (phase == "reading") viewModelScope.launch { help() }
            "listen" -> restartListening()
            "finish" -> if (phase == "reading") viewModelScope.launch { finish("manual") }
            "bye", "wake" -> end()
        }
    }

    private fun restartListening() {
        if (phase !in setOf("greet", "starting", "reading")) return
        set { it.copy(mic = "starting") }
        engine?.stop()
        later(400) { if (phase in setOf("greet", "starting", "reading")) startListening() }
    }

    private fun notMe() {
        phase = "not_me"; clearJobs()
        set { it.copy(screen = Screen.NOT_ME) }
        viewModelScope.launch { say(Lines.NOT_ME); delay(2500); end() }
    }

    // ------------------------------------------------------ 2 + 3. reading
    private fun startReading() {
        if (phase != "greet") return
        phase = "starting"; clearJobs()
        set { it.copy(screen = Screen.READING, liveStarted = false, progress = null, heard = "", comment = "") }
        viewModelScope.launch {
            say(Lines.START)
            tracker = LiveTracker(_ui.value.passage!!, cfg, nowMs() / 1000.0)
            finals = mutableListOf(); tailSeen = mutableListOf(); t0 = nowMs() / 1000.0
            startedAt = nowMs(); lastSpeechAt = startedAt; stallPrompts = 0; lastHelpAt = 0; endReachedAt = null
            phase = "reading"
            set { it.copy(progress = tracker!!.refresh()) }
            jobs += viewModelScope.launch { while (isActive && phase == "reading") { delay(500); watchdog() } }
        }
    }

    private fun feedPartial(text: String, final: Boolean) {
        val tr = tracker ?: return
        val t = clock(); val ws = Text.splitHypothesis(text)
        while (tailSeen.size < ws.size) tailSeen += t
        var lastEnd = finals.lastOrNull()?.end ?: 0.0
        val tail = ws.mapIndexed { k, n ->
            val start = max(tailSeen[k], lastEnd); val h = HypWord(n, start, start + EST); lastEnd = h.end; h
        }
        val hyp = if (final) { finals.addAll(tail); tailSeen = mutableListOf(); finals.toList() } else finals + tail
        apply(tr.update(hyp, nowMs() / 1000.0))
    }

    /** Offline recogniser gives real word times: keep their spacing, anchor the last word at "now". */
    private fun feedTimed(words: List<TimedWord>) {
        val tr = tracker ?: return
        if (words.isEmpty()) return
        val offset = clock() - words.last().end
        var lastEnd = finals.lastOrNull()?.end ?: 0.0
        for (w in words) {
            val n = Text.normalise(w.word); if (n.isEmpty()) continue
            val start = max(w.start + offset, lastEnd); val end = max(w.end + offset, start + 0.05)
            finals += HypWord(n, start, end, w.conf); lastEnd = end
        }
        tailSeen = mutableListOf()
        apply(tr.update(finals.toList(), nowMs() / 1000.0))
    }

    private fun apply(p: Progress) {
        val tr = tracker ?: return
        val passage = tr.passage
        var comment: String? = null; var toast: String? = null
        for (ns in p.newSentences) {
            toast = if (ns.errors == 0) "+${ns.stars} Sentence smashed!" else "+${ns.stars} Sentence done!"
            comment = passage.reactions[ns.sentence] ?: Lines.CHEERS.random()
        }
        set {
            it.copy(progress = p, liveStarted = it.liveStarted || p.cursor >= 0,
                comment = comment ?: it.comment,
                toast = toast ?: it.toast, toastId = if (toast != null) it.toastId + 1 else it.toastId)
        }
        if (tr.reachedEnd() && endReachedAt == null) endReachedAt = nowMs()
    }

    private suspend fun help() {
        val tr = tracker ?: return
        if (phase != "reading" || nowMs() - lastHelpAt < cfg.helpDebounceSeconds * 1000) return
        val idx = tr.state.cursor + 1
        if (idx >= tr.passage.wordCount || tr.passage.words[idx].norm == "help") return
        lastHelpAt = nowMs(); tr.markTold(idx)
        apply(tr.refresh())
        say(Lines.told(tr.passage.words[idx].norm))
    }

    private suspend fun watchdog() {
        val tr = tracker ?: return
        if (phase != "reading" || speaking) return
        val now = nowMs()
        val idle = (now - max((tr.lastProgressAt * 1000).toLong(), startedAt)) / 1000.0
        val silent = (now - lastSpeechAt) / 1000.0
        val ratio = (tr.state.cursor + 1).toDouble() / tr.passage.wordCount
        when {
            endReachedAt != null && now - endReachedAt!! >= cfg.finishGraceSeconds * 1000 -> finish("completed")
            ratio >= cfg.earlyFinishRatio && silent >= cfg.earlyFinishSilence -> finish("stopped_near_end")
            (now - startedAt) / 1000.0 >= cfg.maxSessionSeconds -> finish("time_limit")
            idle >= cfg.stallFinishSeconds && silent >= cfg.stallFinishSeconds -> finish("stalled")
            idle >= cfg.stallPromptSeconds * (stallPrompts + 1) && stallPrompts < Lines.STALL.size -> {
                val line = if (heardAny) Lines.STALL[stallPrompts] else Lines.CANT_HEAR
                stallPrompts++; say(line)
            }
        }
    }

    // ----------------------------------------------------------- 4. finish
    private suspend fun finish(reason: String) {
        if (phase != "reading") return
        phase = "finish"; clearJobs(); engine?.stop()
        val s = _ui.value.student!!
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val report = Reports.build(tracker!!, s, reason, "${s.id}-${System.currentTimeMillis().toString(36)}", fmt.format(Date()), engine?.name ?: "")
        store.addReport(report)
        set { it.copy(screen = Screen.FINISH, report = report, reportSent = false, napLeft = 8, dataVersion = it.dataVersion + 1) }
        say(Lines.closing(s.name))
        set { it.copy(reportSent = true) }
        for (i in 7 downTo 0) { delay(1000); if (phase != "finish") return; set { it.copy(napLeft = i) } }
        end()
    }

    fun end() {
        if (phase == "ended" || phase == "idle") { set { it.copy(screen = Screen.HOME) }; return }
        phase = "ended"; clearJobs(); voice.stop(); engine?.release(); engine = null
        set { it.copy(screen = Screen.SLEEP, mic = "idle", level = 0f) }
        viewModelScope.launch { delay(3500); if (phase == "ended") { phase = "idle"; set { UiState(dataVersion = it.dataVersion + 1) } } }
    }

    fun goHome() { if (phase == "idle" || phase == "ended") { phase = "idle"; set { UiState(dataVersion = it.dataVersion + 1) } } else end() }

    private fun showError(text: String) {
        set { it.copy(error = text) }
        viewModelScope.launch { delay(7000); set { s -> if (s.error == text) s.copy(error = null) else s } }
    }
    fun dismissError() = set { it.copy(error = null) }

    // ------------------------------------------------------ SpeechCallback
    override fun onPartial(text: String) = onSpeech(text, false, null)
    override fun onFinal(text: String, words: List<TimedWord>?) = onSpeech(text, true, words)

    private fun onSpeech(text: String, final: Boolean, timed: List<TimedWord>?) {
        viewModelScope.launch {
            if (muted() || phase == "finish" || phase == "idle" || phase == "ended") return@launch
            val words = Text.splitHypothesis(text)
            if (words.isNotEmpty()) { heardAny = true; if (store.settings.showHeard) set { it.copy(heard = "“$text”") } }
            if (phase == "greet") {
                val low = text.lowercase()
                if ("read to me" in low || "polished" in low || words.size > 6) return@launch
                if (!final && words.none { it in READY }) return@launch
                val flat = low.replace("'", "").replace("’", "")
                if (NOT_ME.any { it in flat }) { notMe(); return@launch }
                if (words.any { it in READY }) startReading()
                return@launch
            }
            if (phase == "reading") {
                lastSpeechAt = nowMs()
                if ("help" in words && words.size <= 3) { help(); return@launch }
                if (final && timed != null) feedTimed(timed) else feedPartial(text, final)
            }
        }
    }

    override fun onLevel(level: Float) = set { it.copy(level = level) }
    override fun onState(state: String) = set { it.copy(mic = state) }

    override fun onError(code: String) {
        viewModelScope.launch {
            set { it.copy(mic = "error:$code") }
            when (code) {
                "no-recognizer", "network" -> if (!fallBackToOffline()) showError(
                    if (code == "network") "Speech recognition needs the internet. Check the Wi-Fi, or choose Offline ears in Teacher › Settings."
                    else "This tablet has no speech recognition service. Choose Offline ears in Teacher › Settings.")
                "mic-denied" -> showError("Microphone permission is off. Allow the microphone for Reading Robot in Android Settings › Apps.")
                "model" -> showError("The offline ears could not start (${VoskSpeechEngine.loadError ?: "model"}). Try Google ears in Teacher › Settings.")
            }
        }
    }

    // ------------------------------------------------------- teacher data
    fun saveStudent(s: Student) { store.upsertStudent(s); bumpData() }
    fun deleteStudent(id: String) { store.deleteStudent(id); bumpData() }
    fun savePassage(p: PassageDef) { store.upsertPassage(p); bumpData() }
    fun deletePassage(id: String) { store.deletePassage(id); bumpData() }
    fun deleteReport(id: String) { store.deleteReport(id); bumpData() }
    fun saveSettings(s: vn.softworld.readingrobot.data.Settings) { store.updateSettings(s); bumpData() }

    suspend fun testVoice() = voice.speak("Hi! I am Reading Robot. Ready to read to me?", store.settings.locale, store.settings.speechRate, store.settings.pitch)

    /** Microphone test for the teacher: listens for one sentence with the chosen engine. */
    fun micTest(onText: (String) -> Unit): () -> Unit {
        val e = chooseEngine()
        e.start(store.settings.locale, object : SpeechCallback {
            override fun onPartial(text: String) = onText("… $text")
            override fun onFinal(text: String, words: List<TimedWord>?) { onText("✓ $text  (${e.name})") }
            override fun onLevel(level: Float) {}
            override fun onState(state: String) { if (state == "listening") onText("Listening with ${e.name}… say a sentence.") }
            override fun onError(code: String) = onText("Error: $code (${e.name})")
        })
        return { e.release() }
    }

    override fun onCleared() { clearJobs(); engine?.release(); voice.shutdown() }
}
