package com.werd.app.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.rounded.Diversity3
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Counts
import com.werd.app.data.Dashboard
import com.werd.app.data.Stats
import com.werd.app.data.WerdUi
import com.werd.app.ui.components.OrnateHeader
import com.werd.app.ui.components.SectionTitle
import com.werd.app.ui.components.StatTile
import com.werd.app.ui.components.currentLocale
import com.werd.app.ui.components.dayShort
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.ui.theme.WerdPalette
import com.werd.app.util.Days
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class Agg(
    val totalRecitations: Int,
    val totalCompletions: Int,
    val perfectDays: Int,
    val bestDayStreak: Int,
    val bestWerdStreak: Int,
    val weekRate: Float,
    val early: Boolean,
    val night: Boolean,
    /** day -> (done, due) */
    val perDay: Map<Long, Pair<Int, Int>>,
)

private fun createdDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

private fun aggregate(d: Dashboard): Agg {
    val today = d.today
    val completed = d.sessions.filter { it.completed }
    val doneSet = completed.map { it.werdId to it.day }.toHashSet()
    val firstDay = (d.werds.minOfOrNull { createdDay(it.werd.createdAt) } ?: today).coerceAtMost(completed.minOfOrNull { it.day } ?: today)
    val perDay = HashMap<Long, Pair<Int, Int>>()
    var perfect = 0
    var day = maxOf(firstDay, today - 400)
    while (day <= today) {
        val due = d.werds.filter { Days.isInMask(it.werd.goalDaysMask, day) && createdDay(it.werd.createdAt) <= day }
        val done = d.werds.count { (it.werd.id to day) in doneSet }
        perDay[day] = done to due.size
        if (due.isNotEmpty() && due.all { (it.werd.id to day) in doneSet } && (day != today || done > 0)) perfect++
        day++
    }
    // best run of days with ≥1 completion
    val days = completed.map { it.day }.toSortedSet()
    var best = 0; var run = 0; var prev = Long.MIN_VALUE
    for (x in days) { run = if (x == prev + 1) run + 1 else 1; best = maxOf(best, run); prev = x }
    var due7 = 0; var done7 = 0
    for (i in 0 until 7) {
        val (dn, du) = perDay[today - i] ?: (0 to 0)
        if (i == 0 && dn < du) { done7 += dn; due7 += dn; continue }
        done7 += minOf(dn, du); due7 += du
    }
    val hours = completed.mapNotNull { it.completedAt }.map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour }
    return Agg(
        totalRecitations = d.sessions.sumOf { Counts.parse(it.counts).values.sum() },
        totalCompletions = completed.size,
        perfectDays = perfect,
        bestDayStreak = best,
        bestWerdStreak = d.werds.maxOfOrNull { it.bestStreak } ?: 0,
        weekRate = if (due7 == 0) 0f else done7.toFloat() / due7,
        early = hours.any { it in 3..6 },
        night = hours.any { it >= 22 || it < 3 },
        perDay = perDay,
    )
}

private data class Badge(val icon: String, val title: Int, val desc: Int, val earned: Boolean, val progress: Float)

@Composable
fun StatsScreen(app: WerdApp, onOpen: (Long) -> Unit, onFamily: () -> Unit = {}) {
    val dash by app.repo.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val d = dash?.tallyOnly() ?: return
    val agg = remember(d) { aggregate(d) }
    val x = LocalExtra.current
    val streakBest = maxOf(agg.bestDayStreak, agg.bestWerdStreak)
    val badges = listOf(
        Badge("🌱", R.string.b_first, R.string.b_first_d, agg.totalCompletions >= 1, agg.totalCompletions.coerceAtMost(1).toFloat()),
        Badge("🔥", R.string.b_s3, R.string.b_s3_d, streakBest >= 3, streakBest / 3f),
        Badge("⭐", R.string.b_s7, R.string.b_s7_d, streakBest >= 7, streakBest / 7f),
        Badge("🌙", R.string.b_s30, R.string.b_s30_d, streakBest >= 30, streakBest / 30f),
        Badge("👑", R.string.b_s100, R.string.b_s100_d, streakBest >= 100, streakBest / 100f),
        Badge("📿", R.string.b_r1k, R.string.b_r1k_d, agg.totalRecitations >= 1000, agg.totalRecitations / 1000f),
        Badge("💎", R.string.b_r10k, R.string.b_r10k_d, agg.totalRecitations >= 10_000, agg.totalRecitations / 10_000f),
        Badge("🕋", R.string.b_r100k, R.string.b_r100k_d, agg.totalRecitations >= 100_000, agg.totalRecitations / 100_000f),
        Badge("✨", R.string.b_p7, R.string.b_p7_d, agg.perfectDays >= 7, agg.perfectDays / 7f),
        Badge("🌅", R.string.b_early, R.string.b_early_d, agg.early, if (agg.early) 1f else 0f),
        Badge("🌌", R.string.b_night, R.string.b_night_d, agg.night, if (agg.night) 1f else 0f),
        Badge("🏆", R.string.b_c100, R.string.b_c100_d, agg.totalCompletions >= 100, agg.totalCompletions / 100f),
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            OrnateHeader {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tab_stats), style = MaterialTheme.typography.headlineSmall, color = Color.White,
                            modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = onFamily, colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Diversity3, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fam_title))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.LocalFireDepartment, null,
                            tint = LocalExtra.current.heroBead, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.days_n, d.dayStreak), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.current_streak), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${(agg.weekRate * 100).toInt()}%", color = x.pattern, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.this_week), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${agg.totalRecitations}", stringResource(R.string.total_recitations), Modifier.weight(1f))
                    StatTile("${agg.totalCompletions}", stringResource(R.string.total_completions), Modifier.weight(1f), accent = x.gold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("$streakBest", stringResource(R.string.best_streak), Modifier.weight(1f), accent = x.gold)
                    StatTile("${agg.perfectDays}", stringResource(R.string.perfect_days), Modifier.weight(1f))
                }
            }
        }
        item { Card { SectionTitle(stringResource(R.string.last_7_days)); Spacer(Modifier.height(10.dp)); WeekChart(d.today, agg.perDay) } }
        item { Card { SectionTitle(stringResource(R.string.consistency)); Spacer(Modifier.height(10.dp)); Heatmap(d.today, agg.perDay) } }

        item { SectionTitle(stringResource(R.string.todays_goals), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) }
        val due = d.dueToday
        if (due.isEmpty()) item {
            Text(stringResource(R.string.nothing_due), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
        items(due, key = { "g${it.werd.id}" }) { w -> GoalRow(w) { onOpen(w.werd.id) } }

        item { SectionTitle(stringResource(R.string.per_werd), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) }
        items(d.werds, key = { "w${it.werd.id}" }) { w -> WerdStats(w, d.today) }

        item { SectionTitle(stringResource(R.string.achievements), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) }
        items(badges.chunked(3)) { row ->
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b -> BadgeTile(b, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, LocalExtra.current.cardBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun WeekChart(today: Long, perDay: Map<Long, Pair<Int, Int>>) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val days = (6 downTo 0).map { today - it }
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        days.forEach { day ->
            val (done, due) = perDay[day] ?: (0 to 0)
            val frac = if (due == 0) (if (done > 0) 1f else 0f) else (done.toFloat() / due).coerceAtMost(1f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(if (due > 0 || done > 0) "$done/$due" else "–", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.width(22.dp).height(100.dp)) {
                    drawRoundRect(track, size = size, cornerRadius = CornerRadius(10f, 10f))
                    val h = size.height * frac
                    drawRoundRect(if (frac >= 1f) primary else primary.copy(alpha = 0.6f), topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h), cornerRadius = CornerRadius(10f, 10f))
                }
                Spacer(Modifier.height(4.dp))
                Text(dayShort(LocalDate.ofEpochDay(day).dayOfWeek), fontSize = 11.sp,
                    fontWeight = if (day == today) FontWeight.Bold else null)
            }
        }
    }
}

@Composable
private fun Heatmap(today: Long, perDay: Map<Long, Pair<Int, Int>>) {
    val weeks = 17
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val locale = currentLocale()
    val order = Days.weekOrder(locale)
    val todayDow = LocalDate.ofEpochDay(today).dayOfWeek
    val todayRow = order.indexOf(todayDow)
    val start = today - todayRow - (weeks - 1) * 7L
    Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f)) {
        val gap = 4f
        val cell = (size.width - gap * (weeks - 1)) / weeks
        for (c in 0 until weeks) for (r in 0 until 7) {
            val day = start + c * 7 + r
            if (day > today) continue
            val (done, due) = perDay[day] ?: (0 to 0)
            val f = if (due == 0) (if (done > 0) 1f else 0f) else done.toFloat() / due
            val color = if (f <= 0f) empty else primary.copy(alpha = 0.25f + 0.75f * f.coerceAtMost(1f))
            val xPos = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - (c + 1) * cell - c * gap else c * (cell + gap)
            drawRoundRect(color, topLeft = Offset(xPos, r * (cell + gap)), size = Size(cell, cell), cornerRadius = CornerRadius(cell / 4, cell / 4))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.less), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(0f, 0.3f, 0.6f, 1f).forEach { f ->
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).then(Modifier)) {
                Canvas(Modifier.fillMaxSize()) { drawRect(if (f == 0f) empty else primary.copy(alpha = 0.25f + 0.75f * f)) }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.more), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GoalRow(w: WerdUi, onClick: () -> Unit) {
    val accent = WerdPalette[w.werd.colorIndex % WerdPalette.size]
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (w.completedToday) LocalExtra.current.done else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, LocalExtra.current.cardBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (w.completedToday) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.werd.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val linesDone = w.items.count { (w.counts[it.id] ?: 0) >= it.target }
                Text(stringResource(if (w.completedToday) R.string.goal_met else R.string.goal_progress, linesDone, w.items.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { w.progress }, color = accent, trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
            }
        }
    }
}

@Composable
private fun WerdStats(w: WerdUi, today: Long) {
    val accent = WerdPalette[w.werd.colorIndex % WerdPalette.size]
    val rate = Stats.rate(w.werd.goalDaysMask, w.completedDays, today, 30, createdDay(w.werd.createdAt))
    Surface(
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, LocalExtra.current.cardBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(w.werd.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Mini("${w.streak}", stringResource(R.string.streak))
                Mini("${w.bestStreak}", stringResource(R.string.best))
                Mini("${(rate * 100).toInt()}%", stringResource(R.string.rate_30))
                Mini("${w.completions}", stringResource(R.string.completions))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (13 downTo 0).forEach { i ->
                    val day = today - i
                    val due = Days.isInMask(w.werd.goalDaysMask, day)
                    val done = day in w.completedDays
                    Box(Modifier.weight(1f).height(8.dp).clip(CircleShape)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(when {
                                done -> accent
                                due -> accent.copy(alpha = 0.15f)
                                else -> Color.Transparent
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Mini(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BadgeTile(b: Badge, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (b.earned) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (b.earned) LocalExtra.current.gold.copy(alpha = 0.6f) else LocalExtra.current.cardBorder),
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp).alpha(if (b.earned) 1f else 0.55f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(b.icon, fontSize = 28.sp)
            Text(stringResource(b.title), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
            Text(stringResource(b.desc), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (!b.earned) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { b.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
            }
        }
    }
}
