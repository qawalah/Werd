package com.werd.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.werd.app.ui.theme.LocalExtra
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A werd as a string of beads: one bead per line. Counted lines are filled, the line being said has a ring,
 * the rest are outlined: state is carried by shape as well as colour (readable in dim light).
 * Long werds keep the first [max] beads and say how many more.
 */
@Composable
fun BeadString(
    total: Int,
    done: Int,
    modifier: Modifier = Modifier,
    current: Int = -1,
    bead: Dp = 10.dp,
    gap: Dp = 4.dp,
    max: Int = 24,
    fill: Color = LocalExtra.current.bead,
    empty: Color = LocalExtra.current.beadEmpty,
    ring: Color = LocalExtra.current.beadCurrent,
    doneSet: Set<Int>? = null,
) {
    if (total <= 0) return
    val shown = min(total, max)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(modifier.semantics { contentDescription = "$done / $total" }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(bead * shown + gap * (shown - 1)).height(bead)) {
            val d = bead.toPx(); val g = gap.toPx(); val r = d / 2
            for (i in 0 until shown) {
                val x0 = i * (d + g) + r
                val cx = if (rtl) size.width - x0 else x0
                val c = Offset(cx, size.height / 2)
                val isDone = doneSet?.contains(i) ?: (i < done)
                when {
                    i == current -> drawCircle(ring, r - 1.2.dp.toPx(), c, style = Stroke(2.2.dp.toPx()))
                    isDone -> drawCircle(fill, r, c)
                    else -> drawCircle(empty, r - 1.dp.toPx(), c, style = Stroke(1.4.dp.toPx()))
                }
            }
        }
        if (total > shown) {
            Spacer(Modifier.width(6.dp))
            Text("+${total - shown}", style = MaterialTheme.typography.labelSmall, color = empty)
        }
    }
}

/**
 * The round counter as a misbaha loop: up to 33 beads around the count, with the larger imam bead on top.
 * Each count moves one bead past the imam; targets above 33 go round again (the loop number is shown under the count).
 */
@Composable
fun BeadRing(
    count: Int,
    target: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp,
    fill: Color = LocalExtra.current.bead,
    empty: Color = LocalExtra.current.beadEmpty,
    ring: Color = LocalExtra.current.beadCurrent,
    /** The voice is saying the current bead now: it breathes. */
    live: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val breathe = if (live) androidx.compose.animation.core.rememberInfiniteTransition(label = "live").animateFloat(
        1f, 1.35f, androidx.compose.animation.core.infiniteRepeatable(tween(700, easing = FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe").value else 1f
    val loop = if (target <= 33) target.coerceAtLeast(1) else 33
    val inLoop = when {
        target <= 0 -> 0
        count >= target -> loop
        target <= 33 -> count
        else -> count % 33
    }
    // the newest bead slides in from the imam's side
    val slide = remember { Animatable(1f) }
    LaunchedEffect(count) { slide.snapTo(0f); slide.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f)) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val n = loop
            val beadR = (size.minDimension / 2) * (if (n > 20) 0.052f else if (n > 10) 0.07f else 0.085f)
            // leave room above the loop for the imam bead and its thread
            val R = size.minDimension / 2 - beadR * 3.6f
            val c = Offset(size.width / 2, size.height / 2 + beadR * 1.2f)
            val dir = if (rtl) -1f else 1f      // beads travel the way the line is read
            // the imam bead and its short thread
            val top = Offset(c.x, c.y - R - beadR * 2.4f)
            drawLine(fill, Offset(c.x, c.y - R - beadR), Offset(c.x, top.y + beadR * 1.2f), strokeWidth = 2.dp.toPx())
            drawOval(fill, Offset(top.x - beadR * 0.95f, top.y - beadR * 1.3f), Size(beadR * 1.9f, beadR * 2.6f))
            for (i in 0 until n) {
                val a = -PI / 2 + dir * 2 * PI * (i + 0.5) / n
                val p = Offset(c.x + (R * cos(a)).toFloat(), c.y + (R * sin(a)).toFloat())
                when {
                    i < inLoop - 1 || (i == inLoop - 1 && inLoop == n && count >= target) -> drawCircle(fill, beadR, p)
                    i == inLoop - 1 -> drawCircle(fill, beadR * (0.6f + 0.4f * slide.value), p)
                    i == inLoop && count < target -> {
                        if (live) drawCircle(fill.copy(alpha = 0.25f), beadR * breathe, p)
                        drawCircle(ring, beadR - 1.dp.toPx(), p, style = Stroke(2.4.dp.toPx()))
                    }
                    else -> drawCircle(empty, beadR - 1.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        content()
    }
}
