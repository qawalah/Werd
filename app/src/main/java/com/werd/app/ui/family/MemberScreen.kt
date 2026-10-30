package com.werd.app.ui.family

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.cloud.Progress
import com.werd.app.ui.sources.cloudError
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One family member's last 30 days: which days were complete, which were missed, and each werd on that day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberScreen(app: WerdApp, userId: String, name: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<List<Progress>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(false) }
    val errText = cloudError(error)
    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.cloud.history(userId, 30) } }.onSuccess { days = it }.onFailure { error = it }
            loading = false
        }
    }
    LaunchedEffect(userId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { stringResource(R.string.fam_history) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onClick = ::load, enabled = !loading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.fam_refresh)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        // fill in the days with no report at all: those were missed (or the phone was offline)
        val byDay = days.orEmpty().associateBy { it.day }
        val today = LocalDate.now()
        val first = days.orEmpty().minOfOrNull { runCatching { LocalDate.parse(it.day) }.getOrDefault(today) } ?: today
        val span = (0L..29L).map { today.minusDays(it) }.filter { !it.isBefore(first) }
        val complete = span.count { d -> byDay[d.toString()]?.let { it.due > 0 && it.done >= it.due } == true }
        val missed = span.count { d -> byDay[d.toString()]?.let { it.due > 0 && it.done < it.due } ?: (d != today) }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading && days == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item { Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (days != null && days!!.isEmpty()) item {
                Text(stringResource(R.string.fam_history_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            if (!days.isNullOrEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat(stringResource(R.string.fam_days_complete), "$complete / ${span.size}", Modifier.weight(1f))
                        Stat(stringResource(R.string.fam_days_missed), "$missed", Modifier.weight(1f))
                        Stat(stringResource(R.string.fam_streak), "${days!!.first().streak}", Modifier.weight(1f))
                    }
                }
                items(span, key = { it.toString() }) { d -> DayCard(d, byDay[d.toString()], d == today) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val x = LocalExtra.current
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, x.cardBorder)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun DayCard(day: LocalDate, p: Progress?, isToday: Boolean) {
    val x = LocalExtra.current
    var open by remember { mutableStateOf(isToday) }
    val complete = p != null && p.due > 0 && p.done >= p.due
    val missed = if (p == null) !isToday else p.due > 0 && p.done < p.due && !isToday
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (missed) MaterialTheme.colorScheme.error.copy(alpha = 0.35f) else x.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when { complete -> Icons.Rounded.CheckCircle; missed -> Icons.Rounded.Close; else -> Icons.Rounded.RadioButtonUnchecked }, null,
                    tint = when { complete -> MaterialTheme.colorScheme.primary; missed -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline })
                Spacer(Modifier.width(10.dp))
                Text(if (isToday) stringResource(R.string.today) else day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (p == null) stringResource(R.string.fam_no_report) else stringResource(R.string.fam_today, p.done, p.due),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (p != null && p.due > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.due },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), trackColor = MaterialTheme.colorScheme.outlineVariant)
            if (open && p != null) p.werds.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (w.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(16.dp),
                        tint = if (w.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Text(w.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${w.done}/${w.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
