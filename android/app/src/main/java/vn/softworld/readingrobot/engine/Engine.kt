package vn.softworld.readingrobot.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/*
 * Reading Robot scoring engine (Kotlin port of the Python package reading_robot).
 * Pure Kotlin, no Android dependencies, so it is unit-tested on the JVM against
 * reports produced by the Python engine (see src/test).
 */

// ------------------------------------------------------------------ config
data class ReadingConfig(
    val hesitationSeconds: Double = 3.0,
    val repetitionWindow: Int = 4,
    val lowPronunciationScore: Double = 60.0,
    val countMispronunciationAsError: Boolean = false,
    val mispronunciationErrorScore: Double = 35.0,
    val finishGraceSeconds: Double = 1.2,
    val stallPromptSeconds: Double = 7.0,
    val stallFinishSeconds: Double = 20.0,
    val earlyFinishRatio: Double = 0.85,
    val earlyFinishSilence: Double = 6.0,
    val maxSessionSeconds: Double = 300.0,
    val helpDebounceSeconds: Double = 3.0,
    val showErrorsLive: Boolean = false,
    val liveWcpmWindowSeconds: Double = 20.0,
    val starsPerSentence: Int = 1,
    val starsCleanSentenceBonus: Int = 1,
    val starsFinishBonus: Int = 3,
    val starsAccuracyBonus: Int = 2,
    val independentMin: Double = 95.0,
    val instructionalMin: Double = 90.0,
    val paceBands: Map<String, Pair<Double, Double>> = mapOf(
        "A" to (25.0 to 80.0), "B" to (35.0 to 110.0), "C" to (50.0 to 130.0), "D" to (60.0 to 150.0),
    ),
) {
    fun paceFor(level: String): Pair<Double, Double> =
        paceBands[(level.ifEmpty { "B" }).uppercase().take(1)] ?: paceBands.getValue("B")
}

/** Python-compatible rounding (round-half-even on the exact binary value). */
internal fun pyRound(x: Double, digits: Int): Double = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
internal fun pyRound0(x: Double): Long = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toLong()

// -------------------------------------------------------------------- text
object Text {
    private val SPELLING = mapOf(
        "favorite" to "favourite", "color" to "colour", "colors" to "colours", "neighbor" to "neighbour",
        "neighbors" to "neighbours", "honor" to "honour", "mom" to "mum", "gray" to "grey", "center" to "centre",
        "theater" to "theatre", "realize" to "realise", "realized" to "realised", "organize" to "organise",
        "traveled" to "travelled", "traveling" to "travelling", "flavor" to "flavour", "humor" to "humour",
        "behavior" to "behaviour", "jewelry" to "jewellery", "pajamas" to "pyjamas", "cozy" to "cosy", "practice" to "practise",
    )
    private val NUMBERS = mapOf(
        "0" to "zero", "1" to "one", "2" to "two", "3" to "three", "4" to "four", "5" to "five", "6" to "six",
        "7" to "seven", "8" to "eight", "9" to "nine", "10" to "ten", "11" to "eleven", "12" to "twelve",
        "20" to "twenty", "100" to "hundred",
    )
    private val HOMOPHONES = listOf(
        setOf("to", "too", "two"), setOf("there", "their", "theyre"), setOf("for", "four"), setOf("by", "buy", "bye"),
        setOf("hear", "here"), setOf("see", "sea"), setOf("won", "one"), setOf("new", "knew"), setOf("no", "know"),
        setOf("right", "write"), setOf("sun", "son"), setOf("ate", "eight"), setOf("blue", "blew"), setOf("red", "read"),
        setOf("tail", "tale"), setOf("whole", "hole"), setOf("wear", "where"), setOf("week", "weak"), setOf("bear", "bare"),
        setOf("flower", "flour"), setOf("its", "it's"), setOf("your", "youre"), setOf("mail", "male"),
    )
    private val HOMO: Map<String, Int> = buildMap { HOMOPHONES.forEachIndexed { i, g -> g.forEach { put(it, i) } } }
    val FILLERS = setOf("um", "umm", "uh", "uhh", "er", "erm", "hmm", "mm", "ah", "eh", "oh")
    val CONTROL = setOf("help")
    private val WORD_RE = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)*")
    private val SENTENCE_END = Regex("[.!?]+[\"'”’)]*$")
    private val COMBINING = Regex("\\p{M}+")

    fun normalise(word: String): String {
        var w = Normalizer.normalize(word, Normalizer.Form.NFKD).replace(COMBINING, "")
        w = w.lowercase().replace('’', '\'')
        w = w.replace(Regex("[^a-z0-9']"), "").trim('\'').replace("'", "")
        w = NUMBERS[w] ?: w
        return SPELLING[w] ?: w
    }

    fun splitHypothesis(text: String): List<String> =
        WORD_RE.findAll(text).map { normalise(it.value) }.filter { it.isNotEmpty() }.toList()

    fun sameWord(r: String, h: String): Boolean {
        if (r == h) return true
        val a = HOMO[r]; val b = HOMO[h]
        return a != null && a == b
    }

    fun editSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    fun isSentenceEnd(tok: String) = SENTENCE_END.containsMatchIn(tok)
}

data class RefWord(val index: Int, val display: String, val norm: String, val sentence: Int, var sentenceEnd: Boolean)

data class Passage(
    val id: String, val title: String, val text: String, val level: String = "", val mission: Int? = null,
    val reactions: Map<Int, String> = emptyMap(),
    val words: List<RefWord>, val sentences: List<List<Int>>,
) {
    val wordCount get() = words.size

    companion object {
        /** Tokenise a passage. Word count is computed, never typed by hand. */
        fun build(id: String, title: String, text: String, level: String = "", mission: Int? = null,
                  reactions: Map<Int, String> = emptyMap()): Passage {
            val words = mutableListOf<RefWord>(); var sentence = 0
            for (tok in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val norm = Text.normalise(tok); if (norm.isEmpty()) continue
                val end = Text.isSentenceEnd(tok)
                words += RefWord(words.size, tok, norm, sentence, end)
                if (end) sentence++
            }
            if (words.isNotEmpty() && !words.last().sentenceEnd) words.last().sentenceEnd = true
            val sentences = mutableListOf<MutableList<Int>>()
            for (w in words) { while (sentences.size <= w.sentence) sentences += mutableListOf<Int>(); sentences[w.sentence] += w.index }
            return Passage(id, title, text, level, mission, reactions, words, sentences)
        }
    }
}

// ----------------------------------------------------------------- aligner
data class HypWord(val norm: String, val start: Double, val end: Double, val confidence: Double = 1.0,
                   val accuracy: Double? = null, val final: Boolean = true)

enum class Status(val key: String) { PENDING("pending"), CORRECT("correct"), SUBSTITUTION("substitution"), OMISSION("omission"), TOLD("told") }

data class WordResult(
    val index: Int, var status: Status = Status.PENDING, var said: String? = null, var start: Double? = null,
    var end: Double? = null, var accuracy: Double? = null, var selfCorrected: Boolean = false, var soundedOut: Boolean = false,
    var repeated: Boolean = false, var hesitation: Double? = null, var lowPronunciation: Boolean = false,
) {
    val isError get() = status == Status.SUBSTITUTION || status == Status.OMISSION || status == Status.TOLD
    val attempted get() = status != Status.PENDING
}

data class Insertion(val afterIndex: Int, val said: String, val start: Double)

data class AlignmentState(
    val words: List<WordResult>, val insertions: List<Insertion>, val cursor: Int,
    val hypCount: Int, val firstStart: Double?, val lastEnd: Double?,
)

private enum class Op { MATCH, SUB, DEL, INS }
private data class Step(val op: Op, val r: Int, val h: Int)

class StreamingAligner(val passage: Passage, val cfg: ReadingConfig = ReadingConfig()) {
    private val ref = passage.words.map { it.norm }
    val told = sortedSetOf<Int>()
    private var hyp: List<HypWord> = emptyList()
    var state: AlignmentState = empty(); private set

    private fun empty() = AlignmentState(ref.indices.map { WordResult(it) }, emptyList(), -1, 0, null, null)

    fun markTold(i: Int) { if (i in ref.indices) { told += i; state = align() } }   // cursor moves past the told word at once

    fun update(h: List<HypWord>): AlignmentState {
        var x = h.filter { it.norm.isNotEmpty() && it.norm !in Text.FILLERS }
        if (ref.none { it in Text.CONTROL }) x = x.filter { it.norm !in Text.CONTROL }
        hyp = x; state = align(); return state
    }

    private fun subCost(r: String, h: String) = if (Text.sameWord(r, h)) 0.0 else if (Text.editSimilarity(r, h) >= 0.5) 0.55 else 1.0

    private fun insCost(i: Int, h: String): Double {
        for (k in max(0, i - cfg.repetitionWindow) until i) if (Text.sameWord(ref[k], h)) return 0.4
        if (i < ref.size) {
            val nxt = ref[i]
            if (nxt.startsWith(h) && h != nxt) return 0.5
            if (Text.editSimilarity(nxt, h) >= 0.5) return 0.7
        }
        return 1.0
    }

    private fun align(): AlignmentState {
        val n = ref.size; val m = hyp.size
        if (m == 0) {
            val st = empty()
            told.forEach { st.words[it].status = Status.TOLD }
            return st.copy(cursor = if (told.isEmpty()) -1 else told.last())
        }
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        val bt = Array(n + 1) { CharArray(m + 1) }
        dp[0][0] = 0.0
        for (j in 1..m) { dp[0][j] = dp[0][j - 1] + insCost(0, hyp[j - 1].norm); bt[0][j] = 'i' }
        for (i in 1..n) {
            val d = if ((i - 1) in told) 0.0 else 1.0
            dp[i][0] = dp[i - 1][0] + d; bt[i][0] = 'd'
            val ri = ref[i - 1]
            for (j in 1..m) {
                var best = dp[i - 1][j - 1] + subCost(ri, hyp[j - 1].norm); var how = 'g'
                var c = dp[i - 1][j] + d; if (c < best) { best = c; how = 'd' }
                c = dp[i][j - 1] + insCost(i, hyp[j - 1].norm); if (c < best) { best = c; how = 'i' }
                dp[i][j] = best; bt[i][j] = how
            }
        }
        var endI = 0
        for (i in 1..n) if (dp[i][m] < dp[endI][m]) endI = i      // ties keep the shorter prefix
        val steps = ArrayList<Step>(); var i = endI; var j = m
        while (i > 0 || j > 0) {
            when (bt[i][j]) {
                'g' -> { steps += Step(if (Text.sameWord(ref[i - 1], hyp[j - 1].norm)) Op.MATCH else Op.SUB, i - 1, j - 1); i--; j-- }
                'd' -> { steps += Step(Op.DEL, i - 1, -1); i-- }
                else -> { steps += Step(Op.INS, i - 1, j - 1); j-- }
            }
        }
        steps.reverse()
        return classify(steps, endI)
    }

    private fun nextRefMatched(steps: List<Step>, k: Int): Int? {
        for (s in steps.subList(k + 1, steps.size)) {
            if (s.op == Op.MATCH) return s.r
            if (s.op == Op.SUB || s.op == Op.DEL) return null
        }
        return null
    }

    private fun classify(steps: List<Step>, endI: Int): AlignmentState {
        val words = ref.indices.map { WordResult(it) }
        val insertions = mutableListOf<Insertion>()
        var prevEnd: Double? = null
        steps.forEachIndexed { k, s ->
            when (s.op) {
                Op.MATCH, Op.SUB -> {
                    val h = hyp[s.h]; val w = words[s.r]
                    w.said = h.norm; w.start = h.start; w.end = h.end; w.accuracy = h.accuracy
                    w.status = if (s.op == Op.MATCH) Status.CORRECT else Status.SUBSTITUTION
                    if (s.op == Op.MATCH && h.accuracy != null && h.accuracy < cfg.lowPronunciationScore) {
                        w.lowPronunciation = true
                        if (cfg.countMispronunciationAsError && h.accuracy < cfg.mispronunciationErrorScore) w.status = Status.SUBSTITUTION
                    }
                    if (prevEnd != null && h.start - prevEnd!! >= cfg.hesitationSeconds) w.hesitation = pyRound(h.start - prevEnd!!, 2)
                    prevEnd = h.end
                }
                Op.DEL -> words[s.r].status = Status.OMISSION
                Op.INS -> {
                    val h = hyp[s.h]; val nxt = nextRefMatched(steps, k)
                    var rep: Int? = null
                    var r = s.r
                    while (r > max(-1, s.r - cfg.repetitionWindow)) { if (r >= 0 && Text.sameWord(ref[r], h.norm)) { rep = r; break }; r-- }
                    when {
                        rep != null -> words[rep].repeated = true
                        nxt != null && ref[nxt].startsWith(h.norm) && h.norm != ref[nxt] -> words[nxt].soundedOut = true
                        nxt != null && Text.editSimilarity(ref[nxt], h.norm) >= 0.5 -> words[nxt].selfCorrected = true
                        else -> insertions += Insertion(s.r, h.norm, h.start)
                    }
                    if (prevEnd != null && h.start - prevEnd!! >= cfg.hesitationSeconds && nxt != null) words[nxt].hesitation = pyRound(h.start - prevEnd!!, 2)
                    prevEnd = h.end
                }
            }
        }
        told.forEach { words[it].status = Status.TOLD; words[it].selfCorrected = false }
        val cursor = max(endI - 1, if (told.isEmpty()) -1 else told.last())
        return AlignmentState(words, insertions, cursor, hyp.size, hyp.firstOrNull()?.start, hyp.lastOrNull()?.end)
    }
}

// ----------------------------------------------------------------- tracker
data class NewSentence(val sentence: Int, val errors: Int, val stars: Int)

data class Progress(
    val cursor: Int, val next: Int, val marks: List<Char>, val sentencesDone: List<Int>, val newSentences: List<NewSentence>,
    val starsSession: Int, val wcpm: Double?, val pace: String, val paceText: String, val percent: Long,
)

val PACE_TEXT = mapOf("warming_up" to "Warming up...", "slow" to "Nice and steady", "just_right" to "Just right!", "fast" to "Whoa, speedy!")

fun accuracyBand(acc: Double, cfg: ReadingConfig) =
    if (acc >= cfg.independentMin) "independent" else if (acc >= cfg.instructionalMin) "instructional" else "frustration"

fun paceLabel(wcpm: Double?, level: String, cfg: ReadingConfig): String {
    if (wcpm == null) return "warming_up"
    val (lo, hi) = cfg.paceFor(level)
    return if (wcpm < lo) "slow" else if (wcpm > hi) "fast" else "just_right"
}

class LiveTracker(val passage: Passage, val cfg: ReadingConfig = ReadingConfig(), clockStart: Double = 0.0) {
    val aligner = StreamingAligner(passage, cfg)
    private val awarded = sortedSetOf<Int>()
    var stars = 0; private set
    var lastProgressAt = clockStart; private set
    private var lastCursor = -1
    val state get() = aligner.state

    fun markTold(i: Int) = aligner.markTold(i)

    fun update(hyp: List<HypWord>, now: Double): Progress {
        val st = aligner.update(hyp)
        if (st.cursor > lastCursor) { lastCursor = st.cursor; lastProgressAt = now }
        return progress(st)
    }

    fun refresh() = progress(aligner.state)

    fun liveWcpm(st: AlignmentState): Double? {
        val correct = st.words.filter { it.status == Status.CORRECT && it.end != null }
        if (correct.size < 4 || st.lastEnd == null) return null
        val win = cfg.liveWcpmWindowSeconds; val tEnd = st.lastEnd
        val recent = correct.count { it.end!! >= tEnd - win }
        val t0 = max(tEnd - win, st.firstStart ?: 0.0); val span = tEnd - t0
        if (span < 3.0) return null
        return pyRound(recent / span * 60.0, 1)
    }

    fun progress(st: AlignmentState): Progress {
        val newS = mutableListOf<NewSentence>()
        passage.sentences.forEachIndexed { s, idxs ->
            if (s in awarded || st.cursor < idxs.last()) return@forEachIndexed
            val errs = idxs.count { st.words[it].isError }
            val gained = cfg.starsPerSentence + if (errs == 0) cfg.starsCleanSentenceBonus else 0
            awarded += s; stars += gained; newS += NewSentence(s, errs, gained)
        }
        val marks = st.words.map {
            when {
                it.status == Status.PENDING -> 'p'
                it.status == Status.SUBSTITUTION -> 's'
                it.status == Status.OMISSION -> 'o'
                it.status == Status.TOLD -> 't'
                it.lowPronunciation || it.soundedOut -> 'w'
                else -> 'r'
            }
        }
        val wcpm = liveWcpm(st); val pace = paceLabel(wcpm, passage.level, cfg)
        return Progress(st.cursor, min(st.cursor + 1, passage.wordCount - 1), marks, awarded.toList(), newS, stars, wcpm,
            pace, PACE_TEXT.getValue(pace), pyRound0(100.0 * (st.cursor + 1) / max(1, passage.wordCount)))
    }

    fun reachedEnd() = aligner.state.cursor >= passage.wordCount - 1
}

// ------------------------------------------------------------------ report
data class Student(val id: String, val name: String, val year: String = "", val teacher: String = "", val passageId: String = "",
                   val streakDays: Int = 0, val starsTotal: Int = 0, val lastDay: String = "")

data class Miscue(val index: Int, val word: String, val type: String, val said: String? = null, val detail: String? = null, val time: Double? = null)

data class ReportWord(val i: Int, val w: String, val status: String, val said: String?, val start: Double?, val acc: Double?,
                      val sc: Boolean, val so: Boolean, val rep: Boolean, val hes: Double?, val lowpron: Boolean)

data class ReadingReport(
    val sessionId: String, val studentId: String, val studentName: String, val year: String, val teacher: String,
    val passageId: String, val passageTitle: String, val level: String, val createdAt: String,
    val totalWords: Int, val wordsAttempted: Int, val wordsCorrect: Int, val errors: Int, val selfCorrections: Int,
    val accuracyPct: Double, val accuracyBand: String, val completionPct: Double, val readingSeconds: Double,
    val wcpm: Double?, val pace: String, val starsEarned: Int, val starRating: Int, val finishedReason: String,
    val errorRate: String, val scRate: String?, val miscues: List<Miscue>, val practiceWords: List<String>,
    val words: List<ReportWord>, var teacherNote: String = "", val asrBackend: String = "",
)

object Reports {
    fun build(tracker: LiveTracker, student: Student, finishedReason: String, sessionId: String, createdAt: String, asrBackend: String = ""): ReadingReport {
        val cfg = tracker.cfg; val p = tracker.passage; val st = tracker.state; val words = st.words
        val nAtt = words.count { it.attempted }
        val nErr = words.count { it.isError } + st.insertions.size
        val correct = words.count { it.status == Status.CORRECT }
        val acc = if (nAtt > 0) pyRound(100.0 * max(0, nAtt - nErr) / nAtt, 1) else 0.0
        val scs = words.count { it.selfCorrected }
        val timed = words.filter { it.start != null }
        val secs = if (timed.size >= 2) timed.maxOf { it.end!! } - timed.minOf { it.start!! } else 0.0
        val wcpm = if (secs >= 5) pyRound(correct / secs * 60, 1) else null
        val finished = tracker.reachedEnd()
        var stars = tracker.stars + if (finished) cfg.starsFinishBonus else 0
        val band = accuracyBand(acc, cfg)
        if (band == "independent" && nAtt > 0) stars += cfg.starsAccuracyBonus
        val rating = if (band == "independent" && finished) 3 else if (band != "frustration") 2 else 1

        val miscues = mutableListOf<Miscue>()
        for (w in words) {
            val ref = p.words[w.index].norm
            if (w.isError) miscues += Miscue(w.index, ref, w.status.key, w.said, null, w.start)
            if (w.selfCorrected) miscues += Miscue(w.index, ref, "self_correction", time = w.start)
            if (w.soundedOut) miscues += Miscue(w.index, ref, "sounded_out", time = w.start)
            if (w.repeated) miscues += Miscue(w.index, ref, "repetition", time = w.start)
            w.hesitation?.let { if (it != 0.0) miscues += Miscue(w.index, ref, "hesitation", detail = String.format(java.util.Locale.ROOT, "%.1fs pause", BigDecimal(it).setScale(1, RoundingMode.HALF_EVEN)), time = w.start) }
            if (w.lowPronunciation && w.status == Status.CORRECT) miscues += Miscue(w.index, ref, "pronunciation", detail = "score ${pyRound0(w.accuracy ?: 0.0)}/100", time = w.start)
        }
        for (ins in st.insertions) miscues += Miscue(ins.afterIndex, "", "insertion", ins.said, null, ins.start)
        miscues.sortWith(compareBy<Miscue> { it.index }.thenBy { it.type })

        val practice = mutableListOf<String>()
        for (w in words) {
            if (w.isError || w.lowPronunciation || w.soundedOut || (w.hesitation ?: 0.0) >= cfg.hesitationSeconds) {
                val ref = p.words[w.index].norm; if (ref !in practice) practice += ref
            }
        }
        val report = ReadingReport(
            sessionId, student.id, student.name, student.year, student.teacher, p.id, p.title, p.level, createdAt,
            p.wordCount, nAtt, correct, nErr, scs, acc, band, pyRound(100.0 * nAtt / p.wordCount, 1), pyRound(secs, 1),
            wcpm, paceLabel(wcpm, p.level, cfg), stars, rating, finishedReason,
            if (nErr > 0) "1:${pyRound0(nAtt.toDouble() / nErr)}" else "0",
            if (scs > 0) "1:${pyRound0((nErr + scs).toDouble() / scs)}" else null,
            miscues, practice,
            words.map { ReportWord(it.index, p.words[it.index].display, it.status.key, it.said, it.start, it.accuracy,
                it.selfCorrected, it.soundedOut, it.repeated, it.hesitation, it.lowPronunciation) },
            asrBackend = asrBackend,
        )
        report.teacherNote = teacherNote(report)
        return report
    }

    fun teacherNote(d: ReadingReport): String {
        val pace = d.wcpm?.let { "${pyRound0(it)} words correct per minute" } ?: "pace not measured"
        val done = if (d.completionPct >= 99) "finished the passage" else "read ${pyRound0(d.completionPct)}% of the passage"
        val s1 = "${d.studentName} $done with ${pyRound0(d.accuracyPct)}% accuracy (${d.accuracyBand} level), $pace."
        val by = LinkedHashMap<String, MutableList<String>>()
        d.miscues.forEach { by.getOrPut(it.type) { mutableListOf() } += if (it.type == "insertion") (it.said ?: "") else it.word }
        val parts = mutableListOf<String>()
        if (by["substitution"] != null) parts += "substituted " + d.miscues.filter { it.type == "substitution" }.take(2).joinToString(", ") { "'${it.said}' for '${it.word}'" }
        by["omission"]?.let { parts += "skipped " + it.take(3).joinToString(", ") { w -> "'$w'" } }
        by["told"]?.let { parts += "needed help with " + it.take(3).joinToString(", ") { w -> "'$w'" } }
        val s2 = if (parts.isNotEmpty()) "${d.studentName} ${parts.joinToString("; ")}." else "No errors were recorded."
        val strengths = mutableListOf<String>()
        if (d.selfCorrections > 0) strengths += "self-corrected ${d.selfCorrections} time${if (d.selfCorrections > 1) "s" else ""}"
        by["sounded_out"]?.let { strengths += "used sounding out on " + it.take(2).joinToString(", ") { w -> "'$w'" } }
        val s3 = if (strengths.isNotEmpty()) "Strengths: ${strengths.joinToString(" and ")}." else ""
        val s4 = if (d.practiceWords.isNotEmpty()) "Suggested practice words: ${d.practiceWords.take(6).joinToString(", ")}." else ""
        return listOf(s1, s2, s3, s4).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
