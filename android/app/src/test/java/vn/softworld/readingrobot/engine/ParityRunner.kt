package vn.softworld.readingrobot.engine

/** Replays every fixture session through the Kotlin engine; returns null when all match Python. */
object ParityRunner {
    @Suppress("UNCHECKED_CAST")
    fun run(json: String): String? {
        val fx = MiniJson.parse(json) as Map<String, Any?>
        val passages = (fx["passages"] as List<Map<String, Any?>>).associate { p ->
            val id = p["id"] as String
            id to Passage.build(id, p["title"] as String, p["text"] as String, (p["level"] as? String) ?: "", (p["mission"] as? Double)?.toInt())
        }
        val student = Student("cathy", "Cathy", "Year 2", "Ms. Patel")
        var n = 0
        for (c in fx["cases"] as List<Map<String, Any?>>) {
            val name = c["name"] as String
            val tr = LiveTracker(passages.getValue(c["passage"] as String))
            val prog = mutableListOf<Progress>()
            for (s in c["steps"] as List<Map<String, Any?>>) {
                if (s.containsKey("told")) { tr.markTold((s["told"] as Double).toInt()); prog += tr.refresh(); continue }
                val hyp = (s["hyp"] as List<List<Any?>>).map { HypWord(it[0] as String, it[1] as Double, it[2] as Double, accuracy = it[3] as Double?) }
                prog += tr.update(hyp, s["t"] as Double)
            }
            val r = Reports.build(tr, student, "completed", "x", "2026-01-01T00:00:00Z")
            val exp = c["report"] as Map<String, Any?>
            fun chk(k: String, got: Any?) {
                val e = exp[k]
                val ok = when {
                    e is Double && got is Number -> e == got.toDouble()
                    e == null -> got == null
                    else -> e == got
                }
                if (!ok) throw AssertionError("$name: $k kotlin=$got python=$e")
            }
            chk("total_words", r.totalWords); chk("words_attempted", r.wordsAttempted); chk("words_correct", r.wordsCorrect)
            chk("errors", r.errors); chk("self_corrections", r.selfCorrections); chk("accuracy_pct", r.accuracyPct)
            chk("accuracy_band", r.accuracyBand); chk("completion_pct", r.completionPct); chk("reading_seconds", r.readingSeconds)
            chk("wcpm", r.wcpm); chk("pace", r.pace); chk("stars_earned", r.starsEarned); chk("star_rating", r.starRating)
            chk("error_rate", r.errorRate); chk("sc_rate", r.scRate); chk("practice_words", r.practiceWords)
            val st = c["statuses"] as List<String>
            if (r.words.map { it.status } != st) return "$name: statuses differ"
            val mis = (c["miscues"] as List<List<Any?>>).map { Triple((it[0] as Double).toInt(), it[1] as String, it[2] as String?) }
            val got = r.miscues.map { Triple(it.index, it.type, it.said) }
            if (got != mis) return "$name: miscues kotlin=$got python=$mis"
            if (r.teacherNote != c["teacher_note"]) return "$name: teacher note\n kotlin=${r.teacherNote}\n python=${c["teacher_note"]}"
            val every = (c["progress_every"] as Double).toInt()
            (c["progress"] as List<Map<String, Any?>>).forEachIndexed { k, p ->
                val g = prog[k * every]
                if (g.cursor != (p["cursor"] as Double).toInt()) return "$name: progress[$k].cursor"
                if (g.marks.joinToString("") != (p["marks"] as List<String>).joinToString("")) return "$name: progress[$k].marks"
                if (g.starsSession != (p["stars_session"] as Double).toInt()) return "$name: progress[$k].stars"
                if (g.wcpm != (p["wcpm"] as Double?)) return "$name: progress[$k].wcpm kotlin=${g.wcpm} python=${p["wcpm"]}"
                if (g.pace != p["pace"]) return "$name: progress[$k].pace"
                val ns = (p["new_sentences"] as List<Map<String, Any?>>).map { NewSentence((it["sentence"] as Double).toInt(), (it["errors"] as Double).toInt(), (it["stars"] as Double).toInt()) }
                if (g.newSentences != ns) return "$name: progress[$k].new_sentences"
            }
            n++
        }
        println("Kotlin engine parity OK: $n sessions identical to Python")
        return null
    }
}

fun main(args: Array<String>) {
    val res = ParityRunner.run(java.io.File(args[0]).readText())
    if (res != null) { System.err.println(res); kotlin.system.exitProcess(1) }
}
