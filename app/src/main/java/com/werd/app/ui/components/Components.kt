package com.werd.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.util.Days
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ geometric pattern

private fun starPath(cx: Float, cy: Float, r: Float): Path {
    val p = Path()
    val inner = r * 0.7654f
    for (k in 0 until 16) {
        val rr = if (k % 2 == 0) r else inner
        val a = (k * 22.5 - 90) * PI / 180
        val x = cx + rr * cos(a).toFloat()
        val y = cy + rr * sin(a).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** Draws a tiling of eight-pointed stars (khatam) joined by small diamonds. */
fun DrawScope.drawIslamicPattern(color: Color, cell: Float, strokeWidth: Float = 1.2f) {
    val stroke = Stroke(width = strokeWidth)
    var y = 0f
    var row = 0
    while (y < size.height + cell) {
        var x = if (row % 2 == 0) 0f else cell / 2
        while (x < size.width + cell) {
            drawPath(starPath(x, y, cell * 0.36f), color, style = stroke)
            drawCircle(color, radius = cell * 0.09f, center = Offset(x, y), style = stroke)
            // diamond between stars
            val d = Path().apply {
                val dx = x + cell / 2; val dy = y
                moveTo(dx, dy - cell * 0.1f); lineTo(dx + cell * 0.1f, dy)
                lineTo(dx, dy + cell * 0.1f); lineTo(dx - cell * 0.1f, dy); close()
            }
            if (row % 2 == 0) drawPath(d, color, style = stroke)
            x += cell
        }
        y += cell * 0.5f
        row++
    }
}

fun Modifier.islamicPattern(color: Color, cell: Dp = 44.dp): Modifier = drawBehind {
    drawIslamicPattern(color, cell.toPx())
}

/** Emerald banner with gold geometric ornament, used as the screen header. */
@Composable
fun OrnateHeader(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val x = LocalExtra.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.verticalGradient(listOf(x.headerTop, x.headerBottom)))
            // the girih ornament belongs to the Classic colours; the other themes keep the header plain
            .then(if (x.headerTop != x.headerBottom) Modifier.islamicPattern(x.pattern.copy(alpha = 0.16f)) else Modifier),
        content = content,
    )
}

/** Small decorative divider: line ✦ line. */
@Composable
fun OrnamentDivider(modifier: Modifier = Modifier, color: Color = LocalExtra.current.gold) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.35f)))
        Spacer(Modifier.width(10.dp))
        BeadString(total = 3, done = 3, bead = 6.dp, gap = 5.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.35f)))
    }
}

// ------------------------------------------------------------------ progress ring

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    stroke: Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(s), topLeft = Offset(s / 2, s / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s))
            drawArc(color, -90f, 360f * p, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s))
        }
        content()
    }
}

// ------------------------------------------------------------------ weekday chips

@Composable
fun DayChips(mask: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Days.weekOrder(currentLocale()).forEach { dow ->
            val on = Days.maskHas(mask, dow)
            val bit = 1 shl (dow.value - 1)
            Surface(
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onChange(mask xor bit) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        dayShort(dow),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
    if (locale.language.isEmpty()) Unit
}

@Composable
fun currentLocale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

@Composable
fun dayShort(d: DayOfWeek): String {
    val l = currentLocale()
    return if (l.language == "ar") d.getDisplayName(TextStyle.FULL, l).removePrefix("ال").take(3)
    else d.getDisplayName(TextStyle.SHORT, l).take(3)
}

// ------------------------------------------------------------------ count stepper

@Composable
fun CountStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, highlight: Boolean = false) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Remove, null, Modifier.size(18.dp))
        }
        Box(
            Modifier.padding(horizontal = 6.dp).widthIn(min = 52.dp)
                .border(1.dp, if (highlight) LocalExtra.current.gold else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { s ->
                    val digits = s.filter { it.isDigit() }.take(5)
                    text = digits
                    digits.toIntOrNull()?.let { if (it > 0) onChange(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.width(48.dp),
            )
        }
        FilledTonalIconButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ misc

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LocalExtra.current.bead))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, LocalExtra.current.cardBorder),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ColorDots(selected: Int, colors: List<Color>, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c)
                    .border(if (i == selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape)
                    .clickable { onSelect(i) },
            )
        }
    }
}
