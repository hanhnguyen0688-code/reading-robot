@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package vn.softworld.readingrobot.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.softworld.readingrobot.data.PassageDef
import vn.softworld.readingrobot.data.Settings
import vn.softworld.readingrobot.engine.Passage
import vn.softworld.readingrobot.engine.ReadingReport
import vn.softworld.readingrobot.engine.Student
import vn.softworld.readingrobot.session.ReadingViewModel

private val TYPE = mapOf("substitution" to "Substitution", "omission" to "Omission", "told" to "Told (asked for help)", "insertion" to "Insertion",
    "self_correction" to "Self-correction", "sounded_out" to "Sounded out", "repetition" to "Repetition", "hesitation" to "Hesitation",
    "pronunciation" to "Pronunciation to practise")
private val BAND = mapOf("independent" to "Independent ≥95%", "instructional" to "Instructional 90–94%", "frustration" to "Frustration <90%")
private val Panel = Color(0xFF1F1E2C)
private val Line = Color(0xFF2F2D42)
private val Dim2 = Color(0xFF9C99B6)

val TeacherColors = darkColorScheme(primary = Color(0xFFA99CF5), onPrimary = Color(0xFF1B1640), background = Color(0xFF16151F),
    surface = Color(0xFF1F1E2C), onSurface = Color(0xFFEFEDF9), surfaceVariant = Color(0xFF2C2848), secondaryContainer = Color(0xFF2C2848))

@Composable
fun PinDialog(pin: String, onOk: () -> Unit, onCancel: () -> Unit) {
    var v by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Teacher area") },
        text = {
            Column {
                Text("Enter the teacher PIN", color = Dim2)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(v, { v = it.filter(Char::isDigit).take(8); bad = false; if (v == pin) onOk() }, singleLine = true, isError = bad,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    supportingText = { if (bad) Text("Wrong PIN") })
            }
        },
        confirmButton = { TextButton({ if (v == pin) onOk() else bad = true }) { Text("Open") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}

@Composable
fun TeacherScreen(vm: ReadingViewModel, version: Int, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Reading Robot · Teacher", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Button(onClose) { Text("Back to robot") }
            }
            TabRow(tab) {
                listOf("Reports", "Students", "Passages", "Settings").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Spacer(Modifier.height(12.dp))
            key(version) {
                when (tab) {
                    0 -> ReportsTab(vm)
                    1 -> StudentsTab(vm)
                    2 -> PassagesTab(vm)
                    else -> SettingsTab(vm)
                }
            }
        }
    }
}

private fun share(ctx: android.content.Context, title: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, title))
}

// ------------------------------------------------------------------ reports
@Composable
private fun ReportsTab(vm: ReadingViewModel) {
    val ctx = LocalContext.current
    val reports = vm.store.reports
    if (reports.isEmpty()) { Text("No reports yet. A report appears here as soon as a child finishes reading.", color = Dim2, modifier = Modifier.padding(30.dp)); return }
    var sel by remember { mutableIntStateOf(0) }
    val wide = LocalConfigurationWide()
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ share(ctx, "Reading reports (CSV)", vm.store.reportsCsv()) }) { Text("Export CSV") }
                Spacer(Modifier.width(8.dp)); Text("${reports.size} on this tablet", color = Dim2, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).padding(6.dp).heightIn(max = if (wide) 2000.dp else 220.dp)) {
                items(reports.size) { i ->
                    val r = reports[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (i == sel) Color(0xFF2C2848) else Color.Transparent)
                        .clickable { sel = i }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.studentName, fontWeight = FontWeight.Bold)
                            Text("${r.passageTitle} · ${r.createdAt.take(16).replace('T', ' ')}", color = Dim2, fontSize = 12.sp)
                        }
                        BandChip(r.accuracyBand, "${r.accuracyPct}%")
                    }
                }
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { m -> ReportDetail(reports[sel.coerceIn(0, reports.size - 1)], vm, m) }
    if (wide) Row(Modifier.fillMaxSize()) { list(Modifier.width(300.dp)); Spacer(Modifier.width(14.dp)); detail(Modifier.weight(1f)) }
    else Column(Modifier.fillMaxSize()) { list(Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp)); detail(Modifier.weight(1f)) }
}

@Composable
private fun LocalConfigurationWide(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 820

@Composable
private fun BandChip(band: String, text: String) {
    val (bg, fg) = when (band) { "independent" -> Color(0xFF14352A) to Color(0xFF5FD6A4); "instructional" -> Color(0xFF3A2C12) to Color(0xFFF2C063); else -> Color(0xFF3D1A25) to Color(0xFFFF8FA8) }
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun ReportDetail(r: ReadingReport, vm: ReadingViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.studentName} · ${r.year}", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("${r.passageTitle} · Level ${r.level} · ${r.finishedReason} · ${r.asrBackend}", color = Dim2, fontSize = 13.sp)
            }
            BandChip(r.accuracyBand, BAND[r.accuracyBand] ?: r.accuracyBand)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Accuracy", "${r.accuracyPct}%", "${r.wordsAttempted - r.errors}/${r.wordsAttempted} · error rate ${r.errorRate}")
            Kpi("WCPM", r.wcpm?.toString() ?: "—", "${r.readingSeconds}s · ${r.pace.replace('_', ' ')}")
            Kpi("Errors", "${r.errors}", "SC ${r.selfCorrections} · SC rate ${r.scRate ?: "—"}")
            Kpi("Completion", "${r.completionPct}%", "of ${r.totalWords} words")
        }
        Head("Teacher note")
        Text(r.teacherNote, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2848)).padding(12.dp))
        Head("Running record")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.words.forEach { w ->
                val (bg, fg, deco) = when (w.status) {
                    "substitution" -> Triple(Color(0xFF3D1A25), Color(0xFFFF8FA8), TextDecoration.LineThrough)
                    "omission" -> Triple(Color(0xFF3D1A25), Color(0xFFFF8FA8).copy(alpha = 0.7f), TextDecoration.LineThrough)
                    "told" -> Triple(Color(0xFF123333), Color(0xFF6FE0D6), TextDecoration.None)
                    "pending" -> Triple(Color.Transparent, Color(0xFF6C6A86), TextDecoration.None)
                    else -> Triple(Color.Transparent, Color(0xFFEFEDF9), if (w.lowpron || w.so || w.hes != null) TextDecoration.Underline else TextDecoration.None)
                }
                val sup = listOfNotNull(if (w.sc) "SC" else null, if (w.rep) "R" else null, if (w.hes != null) "⏸" else null).joinToString(" ")
                Column {
                    if (w.status == "substitution" && w.said != null) Text(w.said, color = Color(0xFFFF8FA8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row {
                        Text(w.w, color = fg, fontSize = 19.sp, textDecoration = deco, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 3.dp))
                        if (sup.isNotEmpty()) Text(sup, color = Color(0xFFA99CF5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text("red = substitution / omission · teal = told · underlined = practise · SC self-correction · R repetition · ⏸ pause", color = Dim2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Head("Practice words")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.practiceWords.isEmpty()) Text("—")
            r.practiceWords.forEach { Text(it, color = Color(0xFFC4BAFF), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).background(Color(0xFF2C2848)).padding(horizontal = 10.dp, vertical = 3.dp)) }
        }
        Head("Miscues")
        val ms = r.miscues.filter { it.type != "repetition" }
        if (ms.isEmpty()) Text("None")
        ms.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text("${m.index + 1}", color = Dim2, modifier = Modifier.width(36.dp))
                Text(m.word.ifEmpty { "—" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
                Text(TYPE[m.type] ?: m.type, modifier = Modifier.weight(1f))
                Text(listOfNotNull(m.said?.let { "said “$it”" }, m.detail).joinToString(" · "), color = Dim2, fontSize = 13.sp)
            }
            HorizontalDivider(color = Line)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ share(ctx, "Reading report: ${r.studentName}", reportText(r)) }) { Text("Share with teacher") }
            OutlinedButton({ vm.deleteReport(r.sessionId) }) { Text("Delete report", color = Color(0xFFFF9FB2)) }
        }
    }
}

private fun reportText(r: ReadingReport) = buildString {
    append("Reading Robot report\n${r.studentName} (${r.year}) · ${r.passageTitle} (Level ${r.level})\n${r.createdAt}\n\n")
    append("Accuracy ${r.accuracyPct}% (${r.accuracyBand}) · ${r.wcpm ?: "—"} WCPM · ${r.errors} errors · ${r.selfCorrections} self-corrections\n\n")
    append(r.teacherNote).append("\n\nMiscues:\n")
    r.miscues.filter { it.type != "repetition" }.forEach { append("- ${it.word.ifEmpty { it.said ?: "" }}: ${TYPE[it.type]}${if (it.type == "substitution") " (said \"${it.said}\")" else ""}\n") }
}

@Composable private fun Head(t: String) = Text(t.uppercase(), color = Dim2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
private fun Kpi(label: String, value: String, sub: String) {
    Column(Modifier.widthIn(min = 140.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label.uppercase(), color = Dim2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = Dim2, fontSize = 12.sp)
    }
}

// ----------------------------------------------------------------- students
@Composable
private fun StudentsTab(vm: ReadingViewModel) {
    var editing by remember { mutableStateOf<Student?>(null) }
    val passages = vm.store.passages
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Each child taps their name on the robot. Pick the passage they read next.", color = Dim2, modifier = Modifier.weight(1f))
            Button({ editing = Student("", "", "Year 2", vm.store.students.firstOrNull()?.teacher ?: "", passages.firstOrNull()?.id ?: "") }) { Text("Add student") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.store.students, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable { editing = s }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${s.year} · ${s.teacher} · ${vm.store.passage(s.passageId)?.title ?: "no passage"}", color = Dim2, fontSize = 13.sp)
                    }
                    Text("★ ${s.starsTotal} · ${s.streakDays}d", color = Dim2)
                }
            }
        }
    }
    editing?.let { s ->
        var name by remember(s) { mutableStateOf(s.name) }; var year by remember(s) { mutableStateOf(s.year) }
        var teacher by remember(s) { mutableStateOf(s.teacher) }; var pid by remember(s) { mutableStateOf(s.passageId) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (s.id.isEmpty()) "Add student" else "Edit ${s.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true)
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("Teacher") }, singleLine = true)
                    Text("Passage", color = Dim2, fontSize = 13.sp)
                    passages.forEach { p -> Row(Modifier.fillMaxWidth().clickable { pid = p.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(pid == p.id, { pid = p.id }); Text("${p.title} (Level ${p.level})") } }
                }
            },
            confirmButton = { TextButton({ if (name.isNotBlank()) { vm.saveStudent(s.copy(name = name.trim(), year = year, teacher = teacher, passageId = pid)); editing = null } }) { Text("Save") } },
            dismissButton = { Row {
                if (s.id.isNotEmpty()) TextButton({ vm.deleteStudent(s.id); editing = null }) { Text("Delete", color = Color(0xFFFF9FB2)) }
                TextButton({ editing = null }) { Text("Cancel") } } })
    }
}

// ----------------------------------------------------------------- passages
@Composable
private fun PassagesTab(vm: ReadingViewModel) {
    var editing by remember { mutableStateOf<PassageDef?>(null) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Word counts are calculated from the text. Sentences end at . ! or ?", color = Dim2, modifier = Modifier.weight(1f))
            Button({ editing = PassageDef("", "", "B", null, "") }) { Text("Add passage") }
        }
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF9FB2))
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.store.passages, key = { it.id }) { p ->
                val n = Passage.build(p.id, p.title, p.text).wordCount
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable { editing = p }.padding(14.dp)) {
                    Text(p.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Mission ${p.mission ?: "–"} · Level ${p.level} · $n words", color = Dim2, fontSize = 13.sp)
                    Text(p.text, color = Color(0xFFC9C6E6), fontSize = 13.sp, maxLines = 2)
                }
            }
        }
    }
    editing?.let { p ->
        var title by remember(p) { mutableStateOf(p.title) }; var level by remember(p) { mutableStateOf(p.level) }
        var mission by remember(p) { mutableStateOf(p.mission?.toString() ?: "") }; var text by remember(p) { mutableStateOf(p.text) }
        val n = Passage.build("x", title, text).wordCount
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (p.id.isEmpty()) "Add passage" else "Edit passage") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(mission, { mission = it.filter(Char::isDigit) }, label = { Text("Mission") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(level, { level = it.uppercase().take(1) }, label = { Text("Level A–D") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(text, { text = it }, label = { Text("Text the child reads") }, minLines = 5)
                    Text("$n words", color = Dim2)
                }
            },
            confirmButton = { TextButton({
                if (title.isNotBlank() && n >= 5) { vm.savePassage(p.copy(title = title.trim(), level = level.ifEmpty { "B" }, mission = mission.toIntOrNull(), text = text.trim())); editing = null }
            }) { Text("Save") } },
            dismissButton = { Row {
                if (p.id.isNotEmpty()) TextButton({
                    if (vm.store.students.any { it.passageId == p.id }) msg = "A student is assigned this passage. Change their passage first."
                    else vm.deletePassage(p.id)
                    editing = null
                }) { Text("Delete", color = Color(0xFFFF9FB2)) }
                TextButton({ editing = null }) { Text("Cancel") } } })
    }
}

// ----------------------------------------------------------------- settings
@Composable
private fun SettingsTab(vm: ReadingViewModel) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(vm.store.settings) }
    var micText by remember { mutableStateOf("") }
    var stopMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    var saved by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { stopMic?.invoke() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Robot's ears (speech recognition)", fontWeight = FontWeight.Bold)
        listOf("auto" to "Automatic: Google when online, offline ears otherwise", "google" to "Google / Android (needs internet on most tablets)",
            "offline" to "Offline ears (built in, works without internet or Google)").forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { s = s.copy(engine = k) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.engine == k, { s = s.copy(engine = k) }); Text(label)
            }
        }
        Text("Reading accent", fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("en-AU", "en-GB", "en-US", "en-NZ").forEach { l -> FilterChip(s.locale == l, { s = s.copy(locale = l) }, label = { Text(l) }) }
        }
        Text("Speaking speed: ${"%.2f".format(s.speechRate)}", fontWeight = FontWeight.Bold)
        Slider(s.speechRate, { s = s.copy(speechRate = it) }, valueRange = 0.6f..1.4f)
        Text("Pause counted as hesitation: ${"%.1f".format(s.hesitationSeconds)} s", fontWeight = FontWeight.Bold)
        Slider(s.hesitationSeconds.toFloat(), { s = s.copy(hesitationSeconds = (it * 2).toInt() / 2.0) }, valueRange = 1f..8f)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.showErrorsLive, { s = s.copy(showErrorsLive = it) }); Spacer(Modifier.width(10.dp)); Text("Show error marks while the child reads") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.showHeard, { s = s.copy(showHeard = it) }); Spacer(Modifier.width(10.dp)); Text("Show what the robot hears (useful for testing)") }
        OutlinedTextField(s.teacherPin, { s = s.copy(teacherPin = it.filter(Char::isDigit).take(8)) }, label = { Text("Teacher PIN (4–8 digits)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ if (s.teacherPin.length >= 4) { vm.saveSettings(s); saved = "Settings saved" } else saved = "PIN must be 4–8 digits" }) { Text("Save settings") }
            Text(saved, color = Dim2)
        }
        HorizontalDivider(color = Line)
        Text("Check the tablet", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ vm.saveSettings(s); scope.launch { vm.testVoice() } }) { Text("Test robot voice") }
            OutlinedButton({ vm.saveSettings(s); stopMic?.invoke(); micText = "Starting…"; stopMic = vm.micTest { micText = it } }) { Text("Test microphone") }
        }
        if (micText.isNotEmpty()) Text(micText, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF4A4866), RoundedCornerShape(10.dp)).padding(12.dp))
        Text("Offline ears: " + when { vn.softworld.readingrobot.speech.VoskSpeechEngine.model != null -> "ready"
            vn.softworld.readingrobot.speech.VoskSpeechEngine.loadError != null -> "error (${vn.softworld.readingrobot.speech.VoskSpeechEngine.loadError})"; else -> "loading…" } +
            " · Google ears: " + if (vn.softworld.readingrobot.speech.GoogleSpeechEngine.available(LocalContext.current)) "available" else "not on this tablet", color = Dim2, fontSize = 13.sp)
        HorizontalDivider(color = Line)
        var resetArmed by remember { mutableStateOf(false) }
        OutlinedButton({ if (resetArmed) { vm.store.resetAll(); vm.bumpData(); resetArmed = false } else resetArmed = true }) {
            Text(if (resetArmed) "Tap again to erase everything" else "Reset app", color = Color(0xFFFF9FB2))
        }
        Spacer(Modifier.height(30.dp))
    }
}
