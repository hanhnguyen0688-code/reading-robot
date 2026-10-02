@file:OptIn(ExperimentalLayoutApi::class)

package vn.softworld.readingrobot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import vn.softworld.readingrobot.engine.Student
import vn.softworld.readingrobot.session.ReadingViewModel
import vn.softworld.readingrobot.session.Screen
import vn.softworld.readingrobot.session.UiState
import kotlin.math.max

private val noRipple = MutableInteractionSource()
@Composable private fun Modifier.tap(onClick: () -> Unit) = clickable(remember { MutableInteractionSource() }, null, onClick = onClick)

// ------------------------------------------------------------ small parts
@Composable
fun Pill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val d = LocalDim.current
    Row(
        modifier.shadow(d.d(8), RoundedCornerShape(d.d(40))).clip(RoundedCornerShape(d.d(40))).background(C.panel)
            .padding(horizontal = d.d(28), vertical = d.d(14)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(16)), content = content,
    )
}

@Composable
fun IconDot(size: Int, glyph: String) {
    val d = LocalDim.current
    Box(Modifier.size(d.d(size)).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFC6BCFF), Color(0xFF8F80EE)))),
        contentAlignment = Alignment.Center) { Text(glyph, fontSize = d.s(size * 0.5f), color = Color(0xFF1B1640), fontWeight = Heavy) }
}

@Composable
fun SquareIcon(size: Int, glyph: String) {
    val d = LocalDim.current
    Box(Modifier.size(d.d(size)).clip(RoundedCornerShape(d.d(size * 0.27f)))
        .background(Brush.verticalGradient(listOf(Color(0xFFC9C0FF), Color(0xFF8D7EF0)))), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = d.s(size * 0.48f), color = Color(0xFF1B1640), fontWeight = Heavy)
    }
}

@Composable
fun Bubble(modifier: Modifier = Modifier, tilt: Float = -1.2f, content: @Composable ColumnScope.() -> Unit) {
    val d = LocalDim.current
    Column(
        modifier.rotate(tilt).shadow(d.d(14), RoundedCornerShape(d.d(48))).clip(RoundedCornerShape(d.d(48))).background(C.bubble)
            .padding(horizontal = d.d(56), vertical = d.d(44)), content = content,
    )
}

@Composable
fun ActButton(top: String, big: String, glyph: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val d = LocalDim.current
    Row(
        modifier.shadow(d.d(10), RoundedCornerShape(d.d(44))).clip(RoundedCornerShape(d.d(44))).background(C.panel2)
            .border(d.d(4), C.violet2, RoundedCornerShape(d.d(44))).clickable(onClick = onClick)
            .padding(start = d.d(20), end = d.d(40), top = d.d(18), bottom = d.d(18)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(24)),
    ) {
        SquareIcon(92, glyph)
        Column { Text(top, color = Color(0xFFD9D6EE), fontSize = d.s(30), fontWeight = FontWeight.Bold); Text(big, color = C.ink, fontSize = d.s(50), fontWeight = Heavy) }
    }
}

// ================================================================= HOME
@Composable
fun HomeScreen(vm: ReadingViewModel, ui: UiState, onTeacher: () -> Unit) {
    val d = LocalDim.current
    val students = remember(ui.dataVersion) { vm.store.students.toList() }
    Box(Modifier.fillMaxSize().padding(d.d(56))) {
        Pill(Modifier.align(Alignment.TopStart)) { IconDot(56, "🤖"); Text("Reading Robot", color = C.ink, fontSize = d.s(34), fontWeight = FontWeight.Bold) }
        Pill(Modifier.align(Alignment.TopEnd).clickable(onClick = onTeacher)) { Text("Teacher", color = C.ink, fontSize = d.s(28), fontWeight = FontWeight.Bold) }
        val content: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(d.d(18))) {
                Text("Who's reading today?", color = C.ink, fontSize = d.s(if (d.portrait) 84 else 92), fontWeight = Black,
                    textAlign = if (d.portrait) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
                Text("Tap your name to start.", color = Color(0xFFC9C6E6), fontSize = d.s(32),
                    textAlign = if (d.portrait) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(d.d(16)))
                if (students.isEmpty()) Text("No students yet. Tap Teacher to add your class.", color = C.err, fontSize = d.s(30))
                LazyVerticalGrid(GridCells.Fixed(if (d.portrait) 1 else 2), horizontalArrangement = Arrangement.spacedBy(d.d(24)),
                    verticalArrangement = Arrangement.spacedBy(d.d(24)), modifier = Modifier.fillMaxWidth()) {
                    items(students, key = { it.id }) { s -> KidTile(s, vm) { vm.startFor(s.id) } }
                }
            }
        }
        if (d.portrait) Column(Modifier.fillMaxSize().padding(top = d.d(110)), horizontalAlignment = Alignment.CenterHorizontally) {
            Robot(Modifier.size(d.d(300), d.d(320)))
            Spacer(Modifier.height(d.d(30)))
            content(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxSize().padding(top = d.d(110)), verticalAlignment = Alignment.Top) {
            Robot(Modifier.padding(top = d.d(100)).size(d.d(560), d.d(600)))
            Spacer(Modifier.width(d.d(60)))
            content(Modifier.weight(1f).padding(top = d.d(40)))
        }
    }
}

@Composable
private fun KidTile(s: Student, vm: ReadingViewModel, onClick: () -> Unit) {
    val d = LocalDim.current
    val p = vm.store.passage(s.passageId)
    Row(
        Modifier.fillMaxWidth().shadow(d.d(10), RoundedCornerShape(d.d(40))).clip(RoundedCornerShape(d.d(40))).background(C.panel2)
            .border(d.d(4), Color(0xFF4A4B62), RoundedCornerShape(d.d(40))).clickable(onClick = onClick).padding(d.d(22)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(26)),
    ) {
        Box(Modifier.size(d.d(104)).clip(RoundedCornerShape(d.d(32))).background(Brush.verticalGradient(listOf(Color(0xFFD3CBFF), Color(0xFF9384F2)))),
            contentAlignment = Alignment.Center) { Text(s.name.take(1).uppercase(), fontSize = d.s(54), fontWeight = Black, color = Color(0xFF1B1640)) }
        Column(Modifier.weight(1f)) {
            Text(s.name, color = C.ink, fontSize = d.s(48), fontWeight = Heavy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(s.year.ifEmpty { null }, p?.let { (it.mission?.let { m -> "Mission $m · " } ?: "") + it.title } ?: "no passage").joinToString(" · "),
                color = Color(0xFFAEABC9), fontSize = d.s(26), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ================================================================ GREET (3a)
@Composable
fun GreetScreen(vm: ReadingViewModel, ui: UiState) {
    val d = LocalDim.current
    val s = ui.student ?: return
    Box(Modifier.fillMaxSize().padding(d.d(56))) {
        Pill(Modifier.align(Alignment.TopStart)) { IconDot(56, "🤖"); Text("Reading Robot", color = C.ink, fontSize = d.s(34), fontWeight = FontWeight.Bold) }
        Row(Modifier.align(Alignment.TopEnd).shadow(d.d(10), RoundedCornerShape(d.d(44))).clip(RoundedCornerShape(d.d(44))).background(C.panel)
            .padding(start = d.d(14), end = d.d(36), top = d.d(14), bottom = d.d(14)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.d(26))) {
            Box(Modifier.size(d.d(170), d.d(110)).clip(RoundedCornerShape(d.d(28))).background(Color(0xFF26272D)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(d.d(84)).border(d.d(4), Color(0xFFB1A6FF), RoundedCornerShape(d.d(20))), contentAlignment = Alignment.Center) {
                    Text(s.name.take(1).uppercase(), color = Color(0xFFC9C6E6), fontSize = d.s(44), fontWeight = Heavy)
                }
            }
            Column {
                Text("I CAN SEE YOU!", color = C.inkDim, fontSize = d.s(22), fontWeight = FontWeight.Bold, letterSpacing = d.s(3))
                Text("${s.name}${if (s.year.isNotEmpty()) " · ${s.year}" else ""}", color = C.ink, fontSize = d.s(38), fontWeight = Heavy)
            }
        }
        val talking = ui.talking
        val main: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(d.d(24))) {
                Bubble {
                    Text("Hi ${s.name}!", color = C.bubbleInk, fontSize = d.s(112), fontWeight = Black, lineHeight = d.s(118))
                    Spacer(Modifier.height(d.d(18)))
                    Text("Ready to read to me? I polished my ears just for you!", color = Color(0xFF2D2B3C), fontSize = d.s(44), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(d.d(10)))
                Text("TO START, JUST...", color = C.violet, fontSize = d.s(26), fontWeight = Heavy, letterSpacing = d.s(3))
                val buttons: @Composable () -> Unit = {
                    ActButton("Say", "\"Ready!\"", "🎤") { vm.tap("start_reading") }
                    Box(Modifier.size(d.d(72)).clip(CircleShape).background(Color(0xFF4A4B58)), contentAlignment = Alignment.Center) {
                        Text("or", color = Color(0xFFD6D4E4), fontSize = d.s(28), fontWeight = FontWeight.Bold)
                    }
                    ActButton("Raise your", "hand", "✋") { vm.tap("start_reading") }
                }
                if (d.portrait) Column(verticalArrangement = Arrangement.spacedBy(d.d(16)), horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(20))) { buttons() }
                Text("Not ${s.name}? Say \"That's not me!\"", color = Color(0xFFA9A8C4), fontSize = d.s(30), modifier = Modifier.clickable { vm.tap("not_me") })
                Row(horizontalArrangement = Arrangement.spacedBy(d.d(22))) {
                    Pill { IconDot(50, "🔥"); Text(if (s.streakDays > 0) "${s.streakDays} day streak" else "First read!", color = C.ink, fontSize = d.s(32), fontWeight = FontWeight.Bold) }
                    Pill { IconDot(50, "★"); Text("${s.starsTotal}", color = C.ink, fontSize = d.s(32), fontWeight = FontWeight.Bold) }
                }
                MicLine(ui)
            }
        }
        if (d.portrait) Column(Modifier.fillMaxSize().padding(top = d.d(200)), horizontalAlignment = Alignment.CenterHorizontally) {
            Robot(Modifier.size(d.d(380), d.d(400)), if (talking) Face.TALKING else Face.HAPPY, ui.level)
            Spacer(Modifier.height(d.d(30))); main(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxSize().padding(top = d.d(140))) {
            Robot(Modifier.padding(top = d.d(150)).size(d.d(620), d.d(660)), if (talking) Face.TALKING else Face.HAPPY, ui.level)
            Spacer(Modifier.width(d.d(40))); main(Modifier.weight(1f).padding(top = d.d(80)))
        }
    }
}

/** A quiet status line: which ears are on and what the robot heard. Helps testing in a real classroom. */
@Composable
fun MicLine(ui: UiState) {
    val d = LocalDim.current
    val label = when {
        ui.mic.startsWith("error") -> "Ears: problem (${ui.mic.removePrefix("error:")})"
        ui.mic == "listening" -> "Listening" + if (ui.engineName == "vosk_offline") " (offline ears)" else ""
        ui.mic == "starting" -> "Getting my ears ready…"
        else -> ""
    }
    if (label.isNotEmpty() || ui.heard.isNotEmpty())
        Text(listOf(label, ui.heard).filter { it.isNotEmpty() }.joinToString("  ·  "), color = Color(0xFF9C98C8), fontSize = d.s(24), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

// ============================================================ READING (3b/3c)
@Composable
fun ReadingScreen(vm: ReadingViewModel, ui: UiState) {
    val d = LocalDim.current
    val passage = ui.passage ?: return
    val prog = ui.progress
    val showMarks = vm.store.settings.showErrorsLive
    Box(Modifier.fillMaxSize().padding(horizontal = d.d(if (d.portrait) 30 else 56), vertical = d.d(40))) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(20))) {
                val dot by animateFloatAsState(1f + ui.level * 1.2f, label = "dot")
                Pill(Modifier.clickable { vm.tap("listen") }) {
                    Box(Modifier.scale(dot).size(d.d(16)).clip(CircleShape).background(if (ui.mic.startsWith("error")) C.err else Color(0xFFE8E4FF)))
                    Text("🎤", fontSize = d.s(28))
                }
                if (!ui.liveStarted) Pill {
                    Text("Mission ${passage.mission ?: ""}", color = C.ink, fontSize = d.s(32), fontWeight = FontWeight.Bold)
                    Text("·", color = C.inkDim, fontSize = d.s(32)); Text(passage.title, color = C.violet, fontSize = d.s(32), fontWeight = FontWeight.Bold)
                } else ProgressTrack(Modifier.weight(1f), passage.sentences.map { it.last() }, passage.wordCount, prog?.cursor ?: -1, prog?.sentencesDone ?: emptyList())
                if (!ui.liveStarted) Spacer(Modifier.weight(1f))
                Pill { IconDot(50, "★"); Text("${(ui.student?.starsTotal ?: 0) + (prog?.starsSession ?: 0)}", color = C.ink, fontSize = d.s(32), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(d.d(56)))
            // ---- body
            val passageCard: @Composable (Modifier) -> Unit = { m -> PassageCard(m, ui, showMarks) }
            val side: @Composable (Modifier) -> Unit = { m -> SidePanel(m, vm, ui) }
            if (d.portrait) Column(Modifier.fillMaxSize()) { passageCard(Modifier.fillMaxWidth().weight(1f)); Spacer(Modifier.height(d.d(36))); side(Modifier.fillMaxWidth()) }
            else Row(Modifier.fillMaxSize()) { passageCard(Modifier.weight(1f).fillMaxHeight()); Spacer(Modifier.width(d.d(44))); side(Modifier.width(d.d(420)).fillMaxHeight()) }
        }
        Toast(ui, Modifier.align(Alignment.TopCenter).padding(top = d.d(120)))
    }
}

@Composable
private fun PassageCard(modifier: Modifier, ui: UiState, showMarks: Boolean) {
    val d = LocalDim.current
    val passage = ui.passage!!; val prog = ui.progress
    Box(modifier) {
        Column(Modifier.fillMaxSize().padding(top = d.d(36)).shadow(d.d(20), RoundedCornerShape(d.d(60))).clip(RoundedCornerShape(d.d(60)))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3B44), Color(0xFF2A2B32)))).verticalScroll(rememberScrollState())
            .padding(horizontal = d.d(70), vertical = d.d(80))) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(d.d(18)), verticalArrangement = Arrangement.spacedBy(d.d(14))) {
                passage.words.forEach { w ->
                    val mark = prog?.marks?.getOrNull(w.index) ?: 'p'
                    val isCur = ui.liveStarted && prog != null && w.index == prog.next && prog.cursor < passage.wordCount - 1
                    val color = when { isCur -> Color.White; mark == 'p' -> C.inkFaint; else -> C.ink }
                    val markColor = when (mark) { 's', 'o' -> C.err; 'w' -> C.warn; 't' -> C.told; else -> null }
                    val drawMark = markColor != null && (showMarks || mark == 't')
                    Text(w.display, color = color, fontSize = d.s(if (d.portrait) 56 else 60), fontWeight = if (isCur) Heavy else FontWeight.Medium,
                        modifier = Modifier
                            .then(if (isCur) Modifier.rotate(-1.5f).shadow(d.d(8), RoundedCornerShape(d.d(18))).clip(RoundedCornerShape(d.d(18)))
                                .background(Brush.verticalGradient(listOf(Color(0xFFB3A7FF), C.violet2))).padding(horizontal = d.d(16)) else Modifier)
                            .then(if (drawMark && !isCur) Modifier.drawBehind { squiggle(markColor!!, 4f * d.u) } else Modifier))
                }
            }
        }
        Text("👟 LEVEL ${passage.level.ifEmpty { "-" }} · ${passage.wordCount} WORDS", color = Color.White, fontSize = d.s(28), fontWeight = Heavy,
            letterSpacing = d.s(3), modifier = Modifier.padding(start = d.d(60)).shadow(d.d(8), RoundedCornerShape(d.d(30))).clip(RoundedCornerShape(d.d(30)))
                .background(Brush.verticalGradient(listOf(Color(0xFFB9AEFF), Color(0xFF8C7DEF)))).padding(horizontal = d.d(32), vertical = d.d(16)))
    }
}

@Composable
private fun SidePanel(modifier: Modifier, vm: ReadingViewModel, ui: UiState) {
    val d = LocalDim.current
    val arrange = if (d.portrait) Arrangement.spacedBy(d.d(24)) else Arrangement.spacedBy(d.d(30))
    if (!ui.liveStarted) {
        // 3b: robot invites the child to start; the ear is a real button that restarts listening
        val content: @Composable () -> Unit = {
            Bubble(Modifier.widthIn(max = d.d(460)), tilt = 1.5f) {
                Text(ui.robotLine.ifEmpty { "Start reading out loud whenever you're ready!" }, color = C.bubbleInk, fontSize = d.s(34),
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            EarButton(vm, ui)
        }
        if (d.portrait) Row(modifier, horizontalArrangement = arrange, verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { content() } }
        else Column(modifier.padding(top = d.d(80)), verticalArrangement = arrange, horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        val meter: @Composable (Modifier) -> Unit = { m -> SpeedMeter(m, ui.progress?.wcpm, ui.progress?.paceText ?: "Warming up...") }
        val talk: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(d.d(24))) {
                Bubble(Modifier.fillMaxWidth(), tilt = -1.4f) {
                    Text(ui.comment.ifEmpty { "Great start! Keep going..." }, color = C.bubbleInk, fontSize = d.s(31), fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().shadow(d.d(10), RoundedCornerShape(d.d(36))).clip(RoundedCornerShape(d.d(36))).background(C.panel)
                    .clickable { vm.tap("help") }.padding(d.d(24)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(14))) {
                    IconDot(44, "?"); Text("Stuck? Say \"Help!\"", color = C.ink, fontSize = d.s(30), fontWeight = FontWeight.Bold)
                }
                MicLine(ui)
            }
        }
        if (d.portrait) Row(modifier, horizontalArrangement = arrange) { meter(Modifier.weight(1f)); talk(Modifier.weight(1f)) }
        else Column(modifier, verticalArrangement = arrange) { meter(Modifier.fillMaxWidth()); talk(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun EarButton(vm: ReadingViewModel, ui: UiState) {
    val d = LocalDim.current
    val err = ui.mic.startsWith("error")
    val off = ui.mic == "idle"
    val scale by animateFloatAsState(1f + ui.level * 0.14f, spring(stiffness = Spring.StiffnessMediumLow), label = "ear")
    val label = when { err -> "TAP TO TRY AGAIN"; ui.mic == "starting" -> "WAKING UP…"; off -> "TAP TO LISTEN"; else -> "LISTENING" }
    val core = when { err -> listOf(Color(0xFFFF9FB2), Color(0xFFD9637F)); off -> listOf(Color(0xFF6C6A86), Color(0xFF55546D)); else -> listOf(Color(0xFFB1A5FF), C.violet2) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.d(18))) {
        Box(Modifier.size(d.d(340)).scale(scale).clip(CircleShape).background(core[1].copy(alpha = 0.18f)).clickable { vm.tap("listen") }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(d.d(270)).clip(CircleShape).background(Brush.radialGradient(core)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(d.d(90)).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Text("👂", fontSize = d.s(46)) }
                    Spacer(Modifier.height(d.d(14)))
                    Text(label, color = Color.White, fontSize = d.s(if (label.length > 10) 26 else 34), fontWeight = Heavy, textAlign = TextAlign.Center)
                }
            }
        }
        MicLine(ui)
    }
}

@Composable
private fun ProgressTrack(modifier: Modifier, sentenceEnds: List<Int>, total: Int, cursor: Int, done: List<Int>) {
    val d = LocalDim.current
    val pct by animateFloatAsState(((cursor + 1).toFloat() / max(1, total)).coerceIn(0f, 1f), tween(500), label = "pct")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(20))) {
        BoxWithConstraints(Modifier.weight(1f).height(d.d(80))) {
            val w = maxWidth
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(d.d(30)).clip(RoundedCornerShape(d.d(15))).background(Color(0xFF1E1F26)))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(pct).height(d.d(30)).clip(RoundedCornerShape(d.d(15)))
                .background(Brush.horizontalGradient(listOf(Color(0xFFB4A8FF), Color(0xFF9F92F7)))))
            sentenceEnds.forEachIndexed { s, end ->
                if (end == total - 1) return@forEachIndexed
                val x = w * ((end + 1).toFloat() / total)
                val ok = s in done
                Box(Modifier.align(Alignment.CenterStart).offset(x = x - d.d(23)).size(d.d(46)).clip(CircleShape)
                    .background(if (ok) Color(0xFFE9E5FF) else C.grey), contentAlignment = Alignment.Center) {
                    Text("★", color = if (ok) Color(0xFF2A2360) else Color(0xFF8C8CA4), fontSize = d.s(24))
                }
            }
            Row(Modifier.align(Alignment.CenterStart).offset(x = w * pct - d.d(46)).size(d.d(92), d.d(76)).shadow(d.d(6), RoundedCornerShape(d.d(30)))
                .clip(RoundedCornerShape(d.d(30))).background(Brush.verticalGradient(listOf(Color(0xFF9799A8), Color(0xFF686A7A)))),
                horizontalArrangement = Arrangement.spacedBy(d.d(14), Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                repeat(2) { Box(Modifier.size(d.d(16), d.d(26)).clip(RoundedCornerShape(d.d(9))).background(Color(0xFFEFECFF))) }
            }
        }
        Box(Modifier.size(d.d(78)).clip(RoundedCornerShape(d.d(24))).background(if (pct >= 1f) C.violet else Color(0xFF4C4D5C)), contentAlignment = Alignment.Center) {
            Text("🏆", fontSize = d.s(40))
        }
    }
}

@Composable
private fun SpeedMeter(modifier: Modifier, wcpm: Double?, text: String) {
    val d = LocalDim.current
    val deg by animateFloatAsState(if (wcpm == null) -80f else ((wcpm / 160.0) * 160 - 80).toFloat().coerceIn(-80f, 80f), spring(dampingRatio = 0.5f), label = "needle")
    Column(modifier.shadow(d.d(14), RoundedCornerShape(d.d(50))).clip(RoundedCornerShape(d.d(50)))
        .background(Brush.verticalGradient(listOf(Color(0xFF34353D), Color(0xFF2A2B31)))).padding(vertical = d.d(30), horizontal = d.d(20)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SPEED-O-METER", color = Color(0xFFB2B0C8), fontSize = d.s(22), fontWeight = Heavy, letterSpacing = d.s(3))
        Canvas(Modifier.padding(top = d.d(16)).size(d.d(300), d.d(170))) {
            val r = size.width / 2f * 0.82f; val c = Offset(size.width / 2f, size.height * 0.95f)
            val sw = r * 0.33f
            val tl = Offset(c.x - r, c.y - r); val s2 = Size(r * 2, r * 2)
            drawArc(C.grey, 180f, 180f, false, tl, s2, style = Stroke(sw))
            drawArc(C.violet, 234f, 72f, false, tl, s2, style = Stroke(sw))          // "just right" zone
            val a = Math.toRadians((deg - 90).toDouble())
            drawLine(Color(0xFFE9E5FF), c, Offset(c.x + (r * 0.85f * Math.cos(a)).toFloat(), c.y + (r * 0.85f * Math.sin(a)).toFloat()), r * 0.06f, StrokeCap.Round)
            drawCircle(Color(0xFFC9C0FF), r * 0.15f, c)
        }
        Text(text, color = C.ink, fontSize = d.s(40), fontWeight = Heavy)
    }
}

@Composable
private fun Toast(ui: UiState, modifier: Modifier) {
    val d = LocalDim.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ui.toastId) { if (ui.toastId > 0) { visible = true; delay(1800); visible = false } }
    AnimatedVisibility(visible, modifier, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = fadeOut() + scaleOut()) {
        Row(Modifier.rotate(2f).shadow(d.d(10), RoundedCornerShape(d.d(34))).clip(RoundedCornerShape(d.d(34)))
            .background(Brush.verticalGradient(listOf(Color(0xFFBDB3FF), Color(0xFF9A8CF5)))).padding(horizontal = d.d(36), vertical = d.d(22)),
            horizontalArrangement = Arrangement.spacedBy(d.d(16)), verticalAlignment = Alignment.CenterVertically) {
            Text("★", color = Color.White, fontSize = d.s(34)); Text(ui.toast, color = Color.White, fontSize = d.s(34), fontWeight = Heavy)
        }
    }
}

// ================================================================ FINISH (3d)
@Composable
fun FinishScreen(vm: ReadingViewModel, ui: UiState) {
    val d = LocalDim.current
    val r = ui.report ?: return
    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(r.sessionId) { popped = true }
    Column(Modifier.fillMaxSize().padding(d.d(40)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(d.d(10))) {
            (0..2).forEach { i ->
                val s by animateFloatAsState(if (popped) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 200f - i * 40f), label = "star$i")
                Text("★", color = if (i < r.starRating) Color(0xFFE9E5FF) else Color(0xFF4B4C63), fontSize = d.s(if (i == 1) 230 else 180),
                    modifier = Modifier.scale(s).rotate(listOf(-8f, 0f, 8f)[i]))
            }
        }
        Text("Great work, ${r.studentName}!", color = Color.White, fontSize = d.s(if (d.portrait) 84 else 104), fontWeight = Black, textAlign = TextAlign.Center,
            modifier = Modifier.rotate(-1.5f).shadow(d.d(16), RoundedCornerShape(d.d(44))).clip(RoundedCornerShape(d.d(44)))
                .background(Brush.verticalGradient(listOf(Color(0xFFBDB2FF), Color(0xFF9284F2)))).padding(horizontal = d.d(64), vertical = d.d(26)))
        Spacer(Modifier.height(d.d(36)))
        Text("I'll send some key information to your teacher so they know how you went. You can head back to your desk now.",
            color = Color(0xFFE8E6F8), fontSize = d.s(42), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = d.d(1100)))
        Spacer(Modifier.height(d.d(50)))
        val tiles: @Composable () -> Unit = {
            Tile("📖", "WORDS READ", "${r.wordsAttempted}")
            Tile("★", "STARS WON", "+${r.starsEarned}")
            Tile("➤", "SENT TO", if (ui.reportSent) "${r.teacher.ifEmpty { "Teacher" }} ✓" else "${r.teacher.ifEmpty { "Teacher" }} …")
        }
        if (d.portrait) Column(verticalArrangement = Arrangement.spacedBy(d.d(22))) { tiles() } else Row(horizontalArrangement = Arrangement.spacedBy(d.d(30))) { tiles() }
        Spacer(Modifier.height(d.d(50)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(40))) {
            ActButton("", "Wave bye-bye!", "👋") { vm.tap("bye") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(20))) {
                Box(Modifier.size(d.d(76)), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(Color(0xFF3D3E55), 0f, 360f, false, style = Stroke(7f * d.u * 2))
                        drawArc(Color(0xFFB5A9FF), -90f, 360f * ui.napLeft / 8f, false, style = Stroke(7f * d.u * 2, cap = StrokeCap.Round))
                    }
                    Text("${ui.napLeft}", color = C.ink, fontSize = d.s(30), fontWeight = FontWeight.Bold)
                }
                Text("Robot nap in ${ui.napLeft}s...", color = Color(0xFFBDB6F2), fontSize = d.s(32), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Tile(glyph: String, label: String, value: String) {
    val d = LocalDim.current
    Row(Modifier.shadow(d.d(12), RoundedCornerShape(d.d(40))).clip(RoundedCornerShape(d.d(40)))
        .background(Brush.verticalGradient(listOf(Color(0xFF3A3B44), Color(0xFF2B2C33)))).padding(start = d.d(26), end = d.d(44), top = d.d(28), bottom = d.d(28)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.d(24))) {
        SquareIcon(78, glyph)
        Column { Text(label, color = Color(0xFFB6B4CC), fontSize = d.s(22), fontWeight = Heavy, letterSpacing = d.s(3)); Text(value, color = C.ink, fontSize = d.s(48), fontWeight = Heavy) }
    }
}

// ============================================================ SLEEP / NOT ME
@Composable
fun SleepScreen(vm: ReadingViewModel) {
    val d = LocalDim.current
    Box(Modifier.fillMaxSize().tap { vm.goHome() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Top) {
                Robot(Modifier.size(d.d(560), d.d(600)), Face.SLEEP)
                Text("Z z z", color = C.violet, fontSize = d.s(90), fontWeight = Black)
            }
            Spacer(Modifier.height(d.d(40)))
            Text("Tap to wake me up", color = Color(0xFFBDB6F2), fontSize = d.s(34), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NotMeScreen(ui: UiState) {
    val d = LocalDim.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Robot(Modifier.size(d.d(500), d.d(540)), if (ui.talking) Face.TALKING else Face.HAPPY)
        Spacer(Modifier.height(d.d(30)))
        Bubble(Modifier.widthIn(max = d.d(950)), tilt = 0f) {
            Text("Oops! Please ask your teacher to send the right reader to me.", color = C.bubbleInk, fontSize = d.s(46), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ErrorBanner(ui: UiState, onDismiss: () -> Unit, modifier: Modifier) {
    val d = LocalDim.current
    AnimatedVisibility(ui.error != null, modifier) {
        Text(ui.error ?: "", color = Color(0xFFFFE3EA), fontSize = d.s(30), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(d.d(40)).clip(RoundedCornerShape(d.d(30))).background(Color(0xFF3B2440))
                .border(d.d(3), C.err, RoundedCornerShape(d.d(30))).clickable(onClick = onDismiss).padding(horizontal = d.d(34), vertical = d.d(22)))
    }
}

@Composable
fun KioskRoot(vm: ReadingViewModel, ui: UiState, onTeacher: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (ui.screen) {
            Screen.HOME -> HomeScreen(vm, ui, onTeacher)
            Screen.GREET -> GreetScreen(vm, ui)
            Screen.READING -> ReadingScreen(vm, ui)
            Screen.FINISH -> FinishScreen(vm, ui)
            Screen.SLEEP -> SleepScreen(vm)
            Screen.NOT_ME -> NotMeScreen(ui)
        }
        ErrorBanner(ui, vm::dismissError, Modifier.align(Alignment.BottomCenter))
    }
}
