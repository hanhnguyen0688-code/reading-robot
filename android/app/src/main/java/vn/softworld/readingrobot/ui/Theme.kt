package vn.softworld.readingrobot.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------- palette
object C {
    val bg0 = Color(0xFF14152A)
    val bg1 = Color(0xFF2A2B6B)
    val glow = Color(0xFF3A3B8C)
    val panel = Color(0xFF2F3038)
    val panel2 = Color(0xFF24252C)
    val ink = Color(0xFFF3F2FB)
    val inkDim = Color(0xFF8D8CA6)
    val inkFaint = Color(0xFF6F6E84)
    val violet = Color(0xFFA99CF5)
    val violet2 = Color(0xFF8B7CF0)
    val violetLight = Color(0xFFC6BCFF)
    val violetDeep = Color(0xFF6D5FD6)
    val bubble = Color(0xFFECEAF8)
    val bubbleInk = Color(0xFF16151F)
    val warn = Color(0xFFC7B8FF)
    val err = Color(0xFFFF9FB2)
    val told = Color(0xFF7FE0C4)
    val grey = Color(0xFF55566A)
}

/**
 * Design units. Screens are designed on a 1920x1240 (landscape) or 1080x1920 (portrait) canvas like the
 * mockups; [u] converts those design pixels to dp for the real screen so proportions stay identical.
 */
class Dim(val u: Float, val fontScale: Float, val portrait: Boolean) {
    fun d(px: Number): Dp = (px.toFloat() * u).dp
    fun s(px: Number): TextUnit = (px.toFloat() * u / fontScale).sp
}
val LocalDim = compositionLocalOf { Dim(0.5f, 1f, false) }

val Heavy = FontWeight.ExtraBold
val Black = FontWeight.Black

/** The navy background with soft light rays from the mockups. */
@Composable
fun StageBackground(content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "rays")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "a")
    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(C.glow, C.bg1, C.bg0), radius = 2200f, center = Offset(600f, 450f)))
            .drawBehind {
                val c = Offset(size.width * 0.35f, size.height * 0.42f)
                val r = size.maxDimension * 1.2f
                rotate(angle, c) {
                    var a = 0f
                    while (a < 360f) {
                        drawArc(Color.White.copy(alpha = 0.035f), a, 6f, true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
                        a += 14f
                    }
                }
            }
    ) { content() }
}

enum class Face { HAPPY, LISTENING, TALKING, SLEEP }

/** The Reading Robot, drawn on a 660x700 grid. */
@Composable
fun Robot(modifier: Modifier, face: Face = Face.HAPPY, level: Float = 0f) {
    val t = rememberInfiniteTransition(label = "robot")
    val blink by t.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4500; 1f at 0; 1f at 4200; 0.1f at 4350; 1f at 4500
    }), label = "blink")
    val talk by t.animateFloat(0f, 1f, infiniteRepeatable(tween(280), RepeatMode.Reverse), label = "talk")
    val listen by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "listen")
    Canvas(modifier) {
        val k = minOf(size.width / 660f, size.height / 700f)
        val ox = (size.width - 660f * k) / 2f; val oy = (size.height - 700f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
        fun sz(w: Float, h: Float) = Size(w * k, h * k)
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, brush: Brush) =
            drawRoundRect(brush, p(x, y), sz(w, h), CornerRadius(r * k))
        val metal = Brush.verticalGradient(listOf(Color(0xFF8C8E9E), Color(0xFF55576A)), startY = oy + 100 * k, endY = oy + 520 * k)
        // antenna
        rr(322f, 0f, 16f, 110f, 8f, Brush.linearGradient(listOf(Color(0xFF5B5D6C), Color(0xFF5B5D6C))))
        drawCircle(Brush.radialGradient(listOf(Color.White, C.violetLight, C.violet2), center = p(318f, -6f), radius = 40 * k), 31 * k, p(330f, 0f))
        // ears + head
        rr(0f, 210f, 70f, 180f, 40f, Brush.verticalGradient(listOf(Color(0xFF6B6D7C), Color(0xFF4B4D5C))))
        rr(590f, 210f, 70f, 180f, 40f, Brush.verticalGradient(listOf(Color(0xFF6B6D7C), Color(0xFF4B4D5C))))
        rr(50f, 100f, 560f, 420f, 200f, metal)
        rr(106f, 156f, 448f, 294f, 150f, Brush.radialGradient(listOf(Color(0xFF2B2A3A), Color(0xFF16151F)), center = p(330f, 270f), radius = 300 * k))
        // eyes
        val eyeH = when (face) { Face.SLEEP -> 14f; Face.LISTENING -> 122f * (1f - 0.12f * listen); else -> 122f * blink }
        val eyeY = if (face == Face.SLEEP) 252f else 190f + (122f - eyeH) / 2f
        val eyeBrush = Brush.verticalGradient(listOf(Color(0xFFECE8FF), C.violet))
        for (x in listOf(194f, 382f)) {
            rr(x, eyeY, 84f, eyeH, 42f, eyeBrush)
            if (face != Face.SLEEP && eyeH > 40f) drawCircle(Color.White, 12 * k, p(x + 30f, eyeY + 28f))
        }
        // cheeks
        drawOval(C.violet.copy(alpha = 0.32f), p(150f, 290f), sz(90f, 28f))
        drawOval(C.violet.copy(alpha = 0.32f), p(420f, 290f), sz(90f, 28f))
        // mouth
        val mw = when (face) { Face.TALKING -> 110f; Face.SLEEP -> 70f; else -> 150f }
        val mh = when (face) { Face.TALKING -> 22f + 24f * talk; Face.SLEEP -> 20f; else -> 70f }
        drawArc(C.violetLight, 0f, 180f, false, p(330f - mw / 2, 380f - mh), sz(mw, mh * 2), style = Stroke(12 * k, cap = StrokeCap.Round))
        // body
        rr(190f, 540f, 280f, 120f, 45f, Brush.verticalGradient(listOf(Color(0xFF6F7181), Color(0xFF4A4C5A))))
        listOf(272f, 330f, 388f).forEachIndexed { i, x ->
            drawCircle(if (i == 0 || level > 0.3f * i) Color(0xFFB5A9FF) else Color(0xFF8D8F9E), 14 * k, p(x, 600f))
        }
    }
}

/** A small wavy underline, as used in the mockup for words to practise. */
fun DrawScope.squiggle(color: Color, thickness: Float) {
    val y = size.height - thickness * 1.5f
    val path = Path().apply {
        moveTo(0f, y)
        var x = 0f; val wl = thickness * 4f
        while (x <= size.width) { lineTo(x, y + sin(x / wl * 2 * PI).toFloat() * thickness); x += 2f }
    }
    drawPath(path, color, style = Stroke(thickness))
}
