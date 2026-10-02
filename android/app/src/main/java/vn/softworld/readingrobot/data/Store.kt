package vn.softworld.readingrobot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.softworld.readingrobot.engine.Miscue
import vn.softworld.readingrobot.engine.ReadingReport
import vn.softworld.readingrobot.engine.ReportWord
import vn.softworld.readingrobot.engine.Student
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PassageDef(val id: String, val title: String, val level: String, val mission: Int?, val text: String,
                      val reactions: Map<Int, String> = emptyMap())

data class Settings(
    val locale: String = "en-AU",
    val engine: String = "auto",          // auto | google | offline
    val speechRate: Float = 0.95f,
    val pitch: Float = 1.15f,
    val showErrorsLive: Boolean = false,
    val hesitationSeconds: Double = 3.0,
    val teacherPin: String = "1234",
    val showHeard: Boolean = true,
)

/** Everything the app keeps on the tablet: roster, passages, settings and reports (one JSON document). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("reading-robot", Context.MODE_PRIVATE)

    var students: MutableList<Student> = mutableListOf(); private set
    var passages: MutableList<PassageDef> = mutableListOf(); private set
    var reports: MutableList<ReadingReport> = mutableListOf(); private set
    var settings = Settings(); private set

    init { load() }

    // ------------------------------------------------------------ defaults
    private fun seed() {
        students = mutableListOf(
            Student("cathy", "Cathy", "Year 2", "Ms. Patel", "mission-7"),
            Student("leo", "Leo", "Year 2", "Ms. Patel", "mission-8"),
        )
        passages = mutableListOf(
            PassageDef("mission-7", "The Lost Sock", "B", 7,
                "Milo the cat had a big problem. His favourite red sock was gone! He looked under the bed. He looked behind the door. He even looked inside the fridge, which was very cold. Then Milo heard a tiny squeak. A mouse was sleeping in the sock, snoring like a little train.",
                mapOf(0 to "Uh-oh, a big problem!", 1 to "Oh no, not the red sock!", 3 to "Not behind the door either...",
                    4 to "A cat in the fridge?! Keep going...", 5 to "A squeak? Who could that be?", 6 to "Ha! A snoring mouse!")),
            PassageDef("mission-8", "Rain Day", "B", 8,
                "It was raining on Saturday. Ava could not go to the park. She sat by the window and watched the drops race down the glass. Her dad made pancakes with blueberries. Then they built a big blanket fort in the lounge room. It was the best rainy day ever.",
                mapOf(1 to "Oh no, no park today!", 3 to "Pancakes! Yum!", 4 to "A blanket fort? So cool!")),
        )
        reports = mutableListOf(); settings = Settings()
    }

    private fun load() {
        val raw = prefs.getString("data", null)
        if (raw == null) { seed(); save(); return }
        try {
            val o = JSONObject(raw)
            students = o.getJSONArray("students").objs().map(::studentFrom).toMutableList()
            passages = o.getJSONArray("passages").objs().map(::passageFrom).toMutableList()
            reports = o.optJSONArray("reports")?.objs()?.map(::reportFrom)?.toMutableList() ?: mutableListOf()
            settings = settingsFrom(o.optJSONObject("settings") ?: JSONObject())
        } catch (e: Exception) { seed() }
    }

    fun save() { prefs.edit().putString("data", toJson().toString()).apply() }

    fun toJson(): JSONObject = JSONObject()
        .put("students", JSONArray(students.map(::studentJson)))
        .put("passages", JSONArray(passages.map(::passageJson)))
        .put("reports", JSONArray(reports.map(::reportJson)))
        .put("settings", settingsJson(settings))

    fun importJson(raw: String) {
        val o = JSONObject(raw)
        require(o.has("students") && o.has("passages")) { "Not a Reading Robot backup" }
        prefs.edit().putString("data", raw).apply(); load()
    }

    fun resetAll() { seed(); save() }

    // --------------------------------------------------------------- edits
    fun student(id: String) = students.firstOrNull { it.id == id }
    fun passage(id: String) = passages.firstOrNull { it.id == id }

    fun upsertStudent(s: Student): String {
        val id = s.id.ifEmpty { uniqueId(s.name, students.map { it.id }) }
        val i = students.indexOfFirst { it.id == id }
        val v = s.copy(id = id)
        if (i >= 0) students[i] = v.copy(streakDays = students[i].streakDays, starsTotal = students[i].starsTotal, lastDay = students[i].lastDay)
        else students += v
        save(); return id
    }
    fun deleteStudent(id: String) { students.removeAll { it.id == id }; save() }

    fun upsertPassage(p: PassageDef): String {
        val id = p.id.ifEmpty { uniqueId(p.title, passages.map { it.id }) }
        val i = passages.indexOfFirst { it.id == id }
        val v = p.copy(id = id, reactions = if (i >= 0 && p.reactions.isEmpty()) passages[i].reactions else p.reactions)
        if (i >= 0) passages[i] = v else passages += v
        save(); return id
    }
    fun deletePassage(id: String) { passages.removeAll { it.id == id }; save() }

    fun updateSettings(s: Settings) { settings = s; save() }

    fun addReport(r: ReadingReport) {
        reports.add(0, r); while (reports.size > 300) reports.removeAt(reports.size - 1)
        val i = students.indexOfFirst { it.id == r.studentId }
        if (i >= 0) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            val s = students[i]; val today = day.format(Date()); val y = day.format(Date(System.currentTimeMillis() - 86_400_000L))
            val streak = if (s.lastDay == today) s.streakDays else if (s.lastDay == y) s.streakDays + 1 else 1
            students[i] = s.copy(starsTotal = s.starsTotal + r.starsEarned, streakDays = streak, lastDay = today)
        }
        save()
    }
    fun deleteReport(id: String) { reports.removeAll { it.sessionId == id }; save() }

    fun reportsCsv(): String {
        val cols = listOf("created_at", "student", "year", "teacher", "passage", "level", "total_words", "words_attempted", "errors",
            "self_corrections", "accuracy_pct", "band", "wcpm", "completion_pct", "reading_seconds", "finished", "practice_words", "teacher_note")
        fun q(v: Any?) = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
        return (listOf(cols.joinToString(",")) + reports.map { r ->
            listOf(r.createdAt, r.studentName, r.year, r.teacher, r.passageTitle, r.level, r.totalWords, r.wordsAttempted, r.errors,
                r.selfCorrections, r.accuracyPct, r.accuracyBand, r.wcpm, r.completionPct, r.readingSeconds, r.finishedReason,
                r.practiceWords.joinToString(" "), r.teacherNote).joinToString(",") { q(it) }
        }).joinToString("\n")
    }

    // ------------------------------------------------------------- mapping
    companion object {
        fun uniqueId(base: String, taken: List<String>): String {
            val s = Normalizer.normalize(base, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }
            var id = s; var k = 2
            while (id in taken) id = "$s-${k++}"
            return id
        }

        private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
        private fun JSONObject.optStr(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
        private fun JSONObject.optDbl(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)

        fun studentJson(s: Student) = JSONObject().put("id", s.id).put("name", s.name).put("year", s.year).put("teacher", s.teacher)
            .put("passage_id", s.passageId).put("streak_days", s.streakDays).put("stars_total", s.starsTotal).put("last_day", s.lastDay)
        fun studentFrom(o: JSONObject) = Student(o.getString("id"), o.getString("name"), o.optString("year"), o.optString("teacher"),
            o.optString("passage_id"), o.optInt("streak_days"), o.optInt("stars_total"), o.optString("last_day"))

        fun passageJson(p: PassageDef) = JSONObject().put("id", p.id).put("title", p.title).put("level", p.level)
            .put("mission", p.mission ?: JSONObject.NULL).put("text", p.text)
            .put("reactions", JSONObject().apply { p.reactions.forEach { (k, v) -> put(k.toString(), v) } })
        fun passageFrom(o: JSONObject): PassageDef {
            val r = o.optJSONObject("reactions"); val m = mutableMapOf<Int, String>()
            r?.keys()?.forEach { k -> k.toIntOrNull()?.let { m[it] = r.getString(k) } }
            return PassageDef(o.getString("id"), o.getString("title"), o.optString("level", "B"),
                if (o.isNull("mission") || !o.has("mission")) null else o.getInt("mission"), o.getString("text"), m)
        }

        fun settingsJson(s: Settings) = JSONObject().put("locale", s.locale).put("engine", s.engine).put("speech_rate", s.speechRate.toDouble())
            .put("pitch", s.pitch.toDouble()).put("show_errors_live", s.showErrorsLive).put("hesitation_seconds", s.hesitationSeconds)
            .put("teacher_pin", s.teacherPin).put("show_heard", s.showHeard)
        fun settingsFrom(o: JSONObject) = Settings(o.optString("locale", "en-AU"), o.optString("engine", "auto"),
            o.optDouble("speech_rate", 0.95).toFloat(), o.optDouble("pitch", 1.15).toFloat(), o.optBoolean("show_errors_live", false),
            o.optDouble("hesitation_seconds", 3.0), o.optString("teacher_pin", "1234"), o.optBoolean("show_heard", true))

        fun reportJson(r: ReadingReport): JSONObject = JSONObject()
            .put("session_id", r.sessionId).put("student_id", r.studentId).put("student_name", r.studentName).put("year", r.year)
            .put("teacher", r.teacher).put("passage_id", r.passageId).put("passage_title", r.passageTitle).put("level", r.level)
            .put("created_at", r.createdAt).put("total_words", r.totalWords).put("words_attempted", r.wordsAttempted)
            .put("words_correct", r.wordsCorrect).put("errors", r.errors).put("self_corrections", r.selfCorrections)
            .put("accuracy_pct", r.accuracyPct).put("accuracy_band", r.accuracyBand).put("completion_pct", r.completionPct)
            .put("reading_seconds", r.readingSeconds).put("wcpm", r.wcpm ?: JSONObject.NULL).put("pace", r.pace)
            .put("stars_earned", r.starsEarned).put("star_rating", r.starRating).put("finished_reason", r.finishedReason)
            .put("error_rate", r.errorRate).put("sc_rate", r.scRate ?: JSONObject.NULL)
            .put("miscues", JSONArray(r.miscues.map {
                JSONObject().put("index", it.index).put("word", it.word).put("type", it.type).put("said", it.said ?: JSONObject.NULL)
                    .put("detail", it.detail ?: JSONObject.NULL).put("time", it.time ?: JSONObject.NULL)
            }))
            .put("practice_words", JSONArray(r.practiceWords))
            .put("words", JSONArray(r.words.map {
                JSONObject().put("i", it.i).put("w", it.w).put("status", it.status).put("said", it.said ?: JSONObject.NULL)
                    .put("start", it.start ?: JSONObject.NULL).put("acc", it.acc ?: JSONObject.NULL).put("sc", it.sc).put("so", it.so)
                    .put("rep", it.rep).put("hes", it.hes ?: JSONObject.NULL).put("lowpron", it.lowpron)
            }))
            .put("teacher_note", r.teacherNote).put("asr_backend", r.asrBackend)

        fun reportFrom(o: JSONObject) = ReadingReport(
            o.getString("session_id"), o.optString("student_id"), o.optString("student_name"), o.optString("year"), o.optString("teacher"),
            o.optString("passage_id"), o.optString("passage_title"), o.optString("level"), o.optString("created_at"),
            o.optInt("total_words"), o.optInt("words_attempted"), o.optInt("words_correct"), o.optInt("errors"), o.optInt("self_corrections"),
            o.optDouble("accuracy_pct"), o.optString("accuracy_band"), o.optDouble("completion_pct"), o.optDouble("reading_seconds"),
            o.optDbl("wcpm"), o.optString("pace"), o.optInt("stars_earned"), o.optInt("star_rating"), o.optString("finished_reason"),
            o.optString("error_rate"), o.optStr("sc_rate"),
            o.getJSONArray("miscues").objs().map { Miscue(it.getInt("index"), it.optString("word"), it.getString("type"), it.optStr("said"), it.optStr("detail"), it.optDbl("time")) },
            o.getJSONArray("practice_words").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.getJSONArray("words").objs().map { ReportWord(it.getInt("i"), it.getString("w"), it.getString("status"), it.optStr("said"),
                it.optDbl("start"), it.optDbl("acc"), it.optBoolean("sc"), it.optBoolean("so"), it.optBoolean("rep"), it.optDbl("hes"), it.optBoolean("lowpron")) },
            o.optString("teacher_note"), o.optString("asr_backend"),
        )
    }
}
