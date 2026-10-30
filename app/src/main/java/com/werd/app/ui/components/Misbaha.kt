package com.werd.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Two ways to count on a misbaha (the focus counter; chosen in the reader or in Settings):
 *  - MisbahaRing: a loop of 33 beads and the imam that turns one bead under the counting mark with every count;
 *    the imam's long shahid and tassel ride round with it and hang like a pendulum, swinging as the loop moves.
 *  - MisbahaPull: the string hangs down; the thumb pulls the next bead across the gap and it drops onto the counted ones.
 * Beads are the same size whatever the target: long targets go round in loops of 33, or run off the top of the string.
 */

private const val LOOP = 33
private const val U = LOOP + 2.3f      // chain positions: beads 0…32, then the imam
private const val IMAM = LOOP + 1.15f

/** Where the loop rests after [count] counts: the next bead under the mark; the imam there when a loop is complete. */
private fun ringTarget(count: Int): Float {
    if (count <= 0) return 0f
    val rd = (count - 1) / LOOP
    val inR = count - rd * LOOP
    return rd * U + if (inR >= LOOP) IMAM else inR.toFloat()
}

class MisbahaColors(val bead: Color, val empty: Color, val current: Color, val gold: Color, val string: Color,
                    val tray: Color, val dark: Boolean)

@Composable
fun misbahaColors(): MisbahaColors {
    val x = LocalExtra.current
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.2f
    return MisbahaColors(x.bead, x.beadEmpty, x.beadCurrent, x.gold, cs.outlineVariant,
        if (dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest, dark)
}

// ------------------------------------------------------------------ drawing

private fun DrawScope.bead(c: Offset, r: Float, col: Color, dark: Boolean, alpha: Float = 1f) {
    drawCircle(Color.Black, r * 0.96f, c + Offset(r * 0.1f, r * 0.3f), alpha = alpha * if (dark) 0.40f else 0.13f)
    drawCircle(
        Brush.radialGradient(
            0f to lerp(col, Color.White, 0.62f), 0.3f to lerp(col, Color.White, 0.12f), 0.75f to col, 1f to lerp(col, Color.Black, 0.38f),
            center = c + Offset(-r * 0.38f, -r * 0.42f), radius = r * 1.45f),
        r, c, alpha = alpha)
    drawCircle(lerp(col, Color.Black, 0.42f), r, c, alpha = alpha, style = Stroke(max(1f, r * 0.08f)))
}

private fun DrawScope.goldDisc(x: Float, y: Float, rx: Float, ry: Float, gold: Color, alpha: Float = 1f) {
    drawOval(Brush.horizontalGradient(0f to lerp(gold, Color.Black, 0.3f), 0.38f to lerp(gold, Color.White, 0.45f), 1f to lerp(gold, Color.Black, 0.35f),
        startX = x - rx, endX = x + rx), Offset(x - rx, y - ry), Size(2 * rx, 2 * ry), alpha = alpha)
    drawOval(lerp(gold, Color.Black, 0.45f), Offset(x - rx, y - ry), Size(2 * rx, 2 * ry), alpha = alpha, style = Stroke(1f))
}

private fun DrawScope.ringMark(c: Offset, r: Float, col: Color, breathe: Float, live: Boolean, fill: Color) {
    if (live) drawCircle(fill.copy(alpha = 0.22f), r * 1.5f * breathe, c)
    drawCircle(col, r * (1.34f + if (live) 0.06f * (breathe - 1f) / 0.35f else 0f), c, style = Stroke(max(1.6f, r * 0.2f)))
}

/** The imam bead at (x, y) and, hanging at angle [ang] (radians from straight down), the shahid, finial and tassel. */
private fun DrawScope.misbahaHead(x: Float, y: Float, r: Float, imamR: Float, ang: Float, lag: Float, glow: Float, col: MisbahaColors) {
    val body = lerp(col.bead, Color.Black, if (col.dark) 0.05f else 0.12f)
    if (glow > 0.01f) drawCircle(Brush.radialGradient(listOf(col.gold.copy(alpha = 0.5f * glow), Color.Transparent), Offset(x, y), r * 6), r * 6, Offset(x, y))
    val deg = Math.toDegrees(ang.toDouble()).toFloat()
    withTransform({ translate(x, y); rotate(deg, Offset.Zero); translate(0f, imamR * 0.95f) }) {
        bead(Offset(0f, r * 0.3f), r * 0.36f, col.gold, col.dark)
        goldDisc(0f, r * 0.82f, r * 0.82f, r * 0.24f, col.gold)
        val top = r * 1.02f
        val len = r * 5.6f
        fun w(u: Float): Float {
            fun g(c: Float, s: Float) = exp(-((u - c) / s) * ((u - c) / s))
            return r * (0.5f - 0.26f * u + 0.3f * g(0.13f, 0.09f) + 0.16f * g(0.58f, 0.055f) + 0.1f * g(0.86f, 0.05f))
        }
        val shahid = Path().apply {
            for (i in 0..48) { val u = i / 48f; if (i == 0) moveTo(w(u), top) else lineTo(w(u), top + u * len) }
            for (i in 48 downTo 0) { val u = i / 48f; lineTo(-w(u), top + u * len) }
            close()
        }
        drawPath(shahid, Brush.horizontalGradient(0f to lerp(body, Color.Black, 0.4f), 0.32f to lerp(body, Color.White, 0.4f), 0.55f to body,
            1f to lerp(body, Color.Black, 0.45f), startX = -r * 0.8f, endX = r * 0.8f))
        drawPath(shahid, lerp(body, Color.Black, 0.45f), style = Stroke(1f))
        for (u in floatArrayOf(0.3f, 0.72f, 0.97f)) goldDisc(0f, top + u * len, w(u) * 1.04f, r * 0.11f, col.gold)
        val dot = lerp(col.gold, Color.White, 0.2f)
        for (u in floatArrayOf(0.42f, 0.5f)) for (f in floatArrayOf(-0.45f, 0f, 0.45f)) drawCircle(dot, r * 0.07f, Offset(w(u) * f, top + u * len))
        val fy = top + len + r * 0.36f
        bead(Offset(0f, fy), r * 0.38f, body, col.dark)
        bead(Offset(0f, fy + r * 0.56f), r * 0.2f, col.gold, col.dark)
        val cy = fy + r * 0.74f
        val ch = r * 0.8f
        val cap = Path().apply { moveTo(-r * 0.24f, cy); lineTo(r * 0.24f, cy); lineTo(r * 0.5f, cy + ch); lineTo(-r * 0.5f, cy + ch); close() }
        drawPath(cap, Brush.horizontalGradient(0f to lerp(col.gold, Color.Black, 0.3f), 0.4f to lerp(col.gold, Color.White, 0.35f),
            1f to lerp(col.gold, Color.Black, 0.35f), startX = -r * 0.5f, endX = r * 0.5f))
        for (f in floatArrayOf(0.3f, 0.62f)) {
            val yy = cy + ch * f
            val hw = r * (0.24f + 0.26f * f)
            drawLine(lerp(col.gold, Color.Black, 0.4f), Offset(-hw, yy), Offset(hw, yy), 1f)
        }
        val ty = cy + ch
        val tl = r * 6.2f
        val shades = listOf(lerp(col.gold, Color.White, 0.28f), col.gold, lerp(col.gold, Color.Black, 0.18f))
        for (k in 0 until 23) {
            val f = k / 22f - 0.5f
            val ln = tl * (0.9f + 0.1f * cos(k * 2.3f))
            val p = Path().apply {
                moveTo(f * r * 0.85f, ty)
                quadraticTo(f * r * 1.4f + lag * 0.3f, ty + ln * 0.5f, f * r * 2.4f + lag, ty + ln)
            }
            drawPath(p, shades[k % 3], style = Stroke(max(1f, r * 0.12f), cap = StrokeCap.Round))
        }
    }
    bead(Offset(x, y), imamR, body, col.dark)
    withTransform({ translate(x, y); rotate(deg, Offset.Zero) }) {
        drawArc(lerp(col.gold, Color.White, 0.15f), 0f, 180f, false, Offset(-imamR * 0.55f, imamR * 0.57f), Size(imamR * 1.1f, imamR * 0.3f),
            style = Stroke(max(1f, imamR * 0.08f)))
    }
}

/** Places [content] centred on (cx, cy) of the parent. */
@Composable
private fun CenteredAt(cx: Float, cy: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { ms, cs ->
        val ps = ms.map { it.measure(cs.copy(minWidth = 0, minHeight = 0)) }
        layout(cs.maxWidth, cs.maxHeight) { ps.forEach { it.place((cx - it.width / 2f).toInt(), (cy - it.height / 2f).toInt()) } }
    }
}

// ------------------------------------------------------------------ the turning loop

/**
 * The round counter: 33 beads and the imam on a loop that turns one bead under the gold mark with each count.
 * [content] (the numbers) sits in the middle of the loop. Tap anywhere to count.
 */
@Composable
fun MisbahaRing(
    count: Int, target: Int, live: Boolean, done: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier,
    description: String = "", content: @Composable () -> Unit = {},
) {
    val col = misbahaColors()
    val density = LocalDensity.current.density
    val rot = remember { Animatable(ringTarget(count)) }
    var sw by remember { mutableFloatStateOf(0f) }
    var swV by remember { mutableFloatStateOf(0f) }
    val geo = remember { FloatArray(3) } // cx, cy, R (px) — read by the physics loop
    val glow = remember { Animatable(if (done) 0.3f else 0f) }
    val breathe = if (live) rememberInfiniteTransition(label = "live").animateFloat(1f, 1.35f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe").value else 1f
    LaunchedEffect(count) {
        launch { rot.animateTo(ringTarget(count), spring(dampingRatio = 0.73f, stiffness = 170f)) }
        if (done) launch { glow.snapTo(1f); glow.animateTo(0.3f, tween(2200)) } else glow.snapTo(0f)
        // the head hangs from the travelling imam: gravity plus the imam's own acceleration
        var last = 0L
        var px = Float.NaN; var py = 0f; var vx = 0f; var vy = 0f; var ax = 0f; var ay = 0f
        val g = 2400f * density
        while (true) {
            val t = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.033f)
            last = t
            val (cx, cy, rr) = Triple(geo[0], geo[1], geo[2])
            if (rr <= 0f) continue
            var d = ((IMAM - rot.value) % U + U) % U
            if (d > U / 2) d -= U
            val a = -PI.toFloat() / 2 + d * 2 * PI.toFloat() / U
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (dt > 0f && !px.isNaN()) {
                val nvx = (x - px) / dt
                val nvy = (y - py) / dt
                ax += (((nvx - vx) / dt).coerceIn(-7000f * density, 7000f * density) - ax) * 0.45f
                ay += (((nvy - vy) / dt).coerceIn(-7000f * density, 7000f * density) - ay) * 0.45f
                vx = nvx; vy = nvy
                val l = max(1f, rr * 0.08f * 8f)
                val acc = -((g - ay * 0.5f) * sin(sw) - ax * 0.5f * cos(sw)) / l - swV * 2.4f
                swV += acc * dt
                sw += swV * dt
            }
            px = x; py = y
            if (!rot.isRunning && abs(swV) < 0.002f && abs(sw) < 0.002f && last != 0L && dt > 0f) break
        }
    }
    BoxWithConstraints(modifier.semantics { contentDescription = description }
        .clickable(remember { MutableInteractionSource() }, null) { onTap() }) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val pad = 14.dp.value * density
        val R = max(40f * density, min(W * 0.41f, (H - pad) / 2.75f)) // the tassel may hang past the bottom when the imam is low
        val r = (PI.toFloat() * R / U) * 0.88f
        val cx = W / 2
        val cy = pad + r * 2.4f + R
        geo[0] = cx; geo[1] = cy; geo[2] = R
        Canvas(Modifier.fillMaxSize()) {
            // the tray the numbers sit on
            drawCircle(col.tray, R - r * 1.9f, Offset(cx, cy))
            drawCircle(col.string, R, Offset(cx, cy), style = Stroke(max(1.2f, r * 0.16f)))
            val my = cy - R - r * 1.75f
            drawPath(Path().apply { moveTo(cx - r * 0.5f, my - r * 0.5f); lineTo(cx + r * 0.5f, my - r * 0.5f); lineTo(cx, my + r * 0.2f); close() }, col.gold)
            val rd = if (count <= 0) 0 else (count - 1) / LOOP
            val len = min(LOOP, target - rd * LOOP)
            val inR = count - rd * LOOP
            fun pos(c: Float): Offset {
                var d = ((c - rot.value) % U + U) % U
                if (d > U / 2) d -= U
                val a = -PI / 2 + d * 2 * PI / U
                return Offset(cx + (R * cos(a)).toFloat(), cy + (R * sin(a)).toFloat())
            }
            val beads = (0 until LOOP).map { k -> k to pos(k.toFloat()) }.sortedBy { it.second.y }
            for ((k, p) in beads) {
                val counted = k < inR
                bead(p, r, if (counted) col.bead else col.empty, col.dark, if (!counted && k >= len) 0.3f else 1f)
                if (k == inR && inR < len && !done) ringMark(p, r, col.current, breathe, live, col.bead)
            }
            val h = pos(IMAM)
            misbahaHead(h.x, h.y, r, r * 1.5f, sw, -swV * r * 0.8f, glow.value, col)
        }
        CenteredAt(cx, cy, content)
    }
}

// ------------------------------------------------------------------ pulling beads down

/**
 * The string hangs down the middle with a gap where the thumb is. Dragging down anywhere pulls the next bead across
 * the gap; it drops onto the counted beads and the whole string slides down one bead. One bead per pull; a second
 * bead in the same pull needs a long, heavy pull. [content] (the numbers) sits at the top inline-start corner.
 */
@Composable
fun MisbahaPull(
    count: Int, target: Int, live: Boolean, done: Boolean, onCount: () -> Unit, modifier: Modifier = Modifier,
    description: String = "", content: @Composable () -> Unit = {},
) {
    val col = misbahaColors()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val latestOnCount by rememberUpdatedState(onCount)
    val latestDone by rememberUpdatedState(done)
    // the chain: beads, with a gold spacer after every 33 (as on a 99-bead misbaha)
    val chain = remember(target) {
        val spacer = ArrayList<Boolean>()
        val beadAt = IntArray(target.coerceAtLeast(0))
        for (n in 1..target) {
            if (n > 1 && (n - 1) % LOOP == 0) spacer.add(true)
            beadAt[n - 1] = spacer.size
            spacer.add(false)
        }
        spacer to beadAt
    }
    val (isSpacer, beadAt) = chain
    fun sOf(c: Int) = if (c <= 0 || beadAt.isEmpty()) -1 else beadAt[min(c, beadAt.size) - 1]
    val geo = remember { FloatArray(5) } // gy, G, p, br, sx
    val shift = remember { Animatable(0f) }
    val falls = remember { mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>() }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val hold = remember { PullHold(count) }
    val sw = remember { Animatable(0f) }
    val glow = remember { Animatable(if (done) 0.3f else 0f) }
    val breathe = if (live) rememberInfiniteTransition(label = "live").animateFloat(1f, 1.35f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe").value else 1f

    LaunchedEffect(count) {
        val prev = hold.last
        hold.last = count
        val (gy, G, p) = Triple(geo[0], geo[1], geo[2])
        if (count == prev + 1 && p > 0f) {
            val sPrev = sOf(prev)
            val sNew = sOf(count)
            val m = sNew - sPrev
            val old = shift.value
            for (i in sPrev + 1..sNew) {
                val oldY = gy - G / 2 - (i - sPrev - 1) * p - old + hold.commitDy
                val newY = gy + G / 2 + (sNew - i) * p - (old + m * p)
                val a = Animatable(oldY - newY)
                falls[i] = a
                launch { a.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 520f)); falls.remove(i) }
            }
            hold.commitDy = 0f
            shift.snapTo(old + m * p)
            launch { shift.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 260f)) }
            launch { sw.animateTo(0f, spring(dampingRatio = 0.12f, stiffness = 30f), initialVelocity = 0.5f) }
        } else {
            hold.commitDy = 0f
            falls.clear()
            shift.snapTo(0f)
        }
        if (done) launch { glow.snapTo(1f); glow.animateTo(0.3f, tween(2200)) } else glow.snapTo(0f)
    }

    BoxWithConstraints(modifier.clipToBounds().semantics { contentDescription = description }
        .pointerInput(target) {
            var dy = 0f
            var n = 0
            val vt = VelocityTracker()
            fun commit() {
                hold.commitDy = dragDy
                dragDy = 0f
                latestOnCount()
            }
            detectVerticalDragGestures(
                onDragStart = { dy = 0f; n = 0; vt.resetTracking() },
                onDragEnd = {
                    val maxD = geo[1] - geo[2]
                    val v = vt.calculateVelocity().y
                    if (!latestDone && n == 0 && (dragDy > maxD * 0.42f || (v > 450f * density && dragDy > 10f * density))) commit()
                    else scope.launch { animate(dragDy, 0f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)) { x, _ -> dragDy = x } }
                },
                onDragCancel = { scope.launch { animate(dragDy, 0f) { x, _ -> dragDy = x } } },
            ) { change, amount ->
                vt.addPosition(change.uptimeMillis, change.position)
                change.consume()
                dy = max(0f, dy + amount)
                val maxD = geo[1] - geo[2]
                if (latestDone) { dragDy = min(dy, 60f * density) * 0.2f; return@detectVerticalDragGestures }
                dragDy = if (n == 0) (if (dy <= maxD) dy else maxD + (dy - maxD) * 0.15f) else min(maxD, dy * 0.36f)
                val need = if (n == 0) maxD * 0.92f else maxD * 2.75f
                if (dy >= need) { commit(); n++; dy = 0f }
            }
        }) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val br = (H * 0.034f).coerceIn(14f * density, 21f * density)
        val p = br * 2.08f
        val G = p * 3.3f
        val gy = H * 0.36f
        val sx = W * 0.5f
        geo[0] = gy; geo[1] = G; geo[2] = p; geo[3] = br; geo[4] = sx
        val s = sOf(count)
        Canvas(Modifier.fillMaxSize()) {
            val nb = run { var j = s + 1; while (j < isSpacer.size && isSpacer[j]) j++; if (j < isSpacer.size) j else -1 }
            fun slot(i: Int) = (if (i <= s) gy + G / 2 + (s - i) * p else gy - G / 2 - (i - s - 1) * p) - shift.value
            fun yOf(i: Int): Float {
                var y = slot(i) + (falls[i]?.value ?: 0f)
                if (i > s && nb >= 0) {
                    if (i <= nb) y += dragDy else if (i == nb + 1) y += dragDy * 0.18f else if (i == nb + 2) y += dragDy * 0.06f
                }
                return y
            }
            val imamY = slot(-1) + br * 0.3f
            drawLine(col.string, Offset(sx, 0f), Offset(sx, imamY), max(1.2f, br * 0.16f))
            // a hint in the gap until the first beads are pulled
            if (!done && count < 3 && dragDy == 0f) {
                val a = 0.5f
                for (o in floatArrayOf(-6f, 4f)) {
                    val y = gy + o * density
                    val x0 = sx - br * 1.9f
                    drawLine(col.empty.copy(alpha = a), Offset(x0 - 8 * density, y - 5 * density), Offset(x0, y + 2 * density), 2 * density, StrokeCap.Round)
                    drawLine(col.empty.copy(alpha = a), Offset(x0, y + 2 * density), Offset(x0 + 8 * density, y - 5 * density), 2 * density, StrokeCap.Round)
                }
            }
            for (i in isSpacer.indices.reversed()) {
                val y = yOf(i)
                if (y < -p * 1.2f || y > size.height + p) continue
                val fade = if (y < 70 * density) max(0f, (y + p) / (70 * density + p)) else 1f
                if (isSpacer[i]) {
                    goldDisc(sx, y, br * 0.95f, br * 0.4f, col.gold, fade)
                    continue
                }
                bead(Offset(sx, y), br, if (i <= s) col.bead else col.empty, col.dark, fade)
                if (i == nb && !done) ringMark(Offset(sx, y), br, col.current, breathe, live, col.bead)
            }
            misbahaHead(sx, imamY, br * 0.7f, br * 1.3f, sw.value, -sw.velocity * br * 0.8f, glow.value, col)
        }
        content()
    }
}

private class PullHold(var last: Int) { var commitDy = 0f }
