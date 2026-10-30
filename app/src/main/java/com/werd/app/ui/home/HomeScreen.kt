package com.werd.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Diversity3
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Dashboard
import com.werd.app.data.GroupMode
import com.werd.app.data.ReminderType
import com.werd.app.data.WerdGroup
import com.werd.app.data.WerdUi
import com.werd.app.ui.components.OrnateHeader
import com.werd.app.ui.components.ProgressRing
import com.werd.app.ui.components.currentLocale
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.ui.theme.WerdPalette
import com.werd.app.util.Days
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime

private data class Section(val key: String, val title: String, val group: WerdGroup?, val werds: List<WerdUi>, val color: Color?)

@Composable
fun HomeScreen(app: WerdApp, onOpen: (Long) -> Unit, onEdit: (Long) -> Unit, onNew: () -> Unit, onGroupReminder: (Long) -> Unit = {}) {
    val dash by app.repo.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showGroups by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<WerdUi?>(null) }
    var deleteTarget by remember { mutableStateOf<WerdUi?>(null) }
    var shareTarget by remember { mutableStateOf<WerdUi?>(null) }
    val sharedMsg = stringResource(R.string.wlib_shared)
    val snack = remember { SnackbarHostState() }
    val d = dash

    val sections = d?.let { buildSections(it, settings.groupMode) } ?: emptyList()
    val copySuffix = stringResource(R.string.copy_suffix)
    val inboxFlow = remember { app.db.dao().inboxFlow() }
    val inbox by inboxFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // awrad sent by family show up (or, from a parent, get added) as soon as the app is opened
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val c = app.cloud.store.value
        if (c.configured && c.signedIn) scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.cloud.syncInbox() } }
    }

    val fabLabel = stringResource(R.string.new_werd)
    // werds that a natural voice has completely on the phone get a small voice mark
    var voiced by remember { mutableStateOf(emptySet<Long>()) }
    val voiceKey = d?.werds?.map { it.werd.id to it.items.size }
    val voicePrefs by app.voice.flow.collectAsStateWithLifecycle()
    LaunchedEffect(voiceKey, voicePrefs) {
        d?.werds?.let { ws -> voiced = com.werd.app.voice.VoiceLibrary.readyWerds(app, ws.map { w -> w.werd.id to w.items.map { it.text } }) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { HomeHeader(d, onOpen) }
            items(inbox, key = { "inbox_" + it.id }) { w ->
                InboxCard(w, onAdd = {
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { app.cloud.accept(w, null) }
                        withContext(Dispatchers.Main.immediate) { onOpen(id) }
                    }
                }, onDismiss = { scope.launch { withContext(Dispatchers.IO) { app.cloud.dismiss(w) } } })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.my_awrad), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    GroupModeButton(settings.groupMode) { m -> app.settings.update { it.copy(groupMode = m) } }
                    IconButton(onClick = { showGroups = true }) { Icon(Icons.Rounded.FolderOpen, stringResource(R.string.manage_groups)) }
                }
            }
            if (d != null && d.werds.isEmpty()) {
                item { EmptyState(onNew) }
            }
            sections.forEach { sec ->
                val collapsed = sec.key in settings.collapsed
                if (settings.groupMode != GroupMode.NONE) {
                    item(key = "h_${sec.key}") {
                        SectionHeader(sec, collapsed) {
                            app.settings.update { s ->
                                s.copy(collapsed = if (collapsed) s.collapsed - sec.key else s.collapsed + sec.key)
                            }
                        }
                    }
                }
                if (!collapsed) {
                    if (sec.werds.isEmpty() && sec.group != null) {
                        item(key = "e_${sec.key}") {
                            Text(stringResource(R.string.group_empty), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
                        }
                    }
                    items(sec.werds, key = { "${sec.key}_${it.werd.id}" }) { w ->
                        WerdCard(
                            w = w, voiced = w.werd.id in voiced,
                            onOpen = { onOpen(w.werd.id) },
                            onEdit = { onEdit(w.werd.id) },
                            onMove = { moveTarget = w },
                            onDuplicate = { scope.launch { app.repo.duplicateWerd(w.werd.id, copySuffix) } },
                            onReset = { scope.launch { app.repo.setAll(w.werd.id, false) } },
                            onComplete = { scope.launch { app.repo.setAll(w.werd.id, true) } },
                            onDelete = { deleteTarget = w },
                            onShare = { shareTarget = w },
                            onUp = { scope.launch { reorder(app, d!!, w, -1) } },
                            onDown = { scope.launch { reorder(app, d!!, w, 1) } },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.new_werd)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.BottomRight).padding(18.dp)
                .semantics { contentDescription = fabLabel },
        )
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    if (showGroups && d != null) {
        GroupsSheet(app, d.groups, onDismiss = { showGroups = false },
            onReminder = { gid -> showGroups = false; onGroupReminder(gid) })
    }
    moveTarget?.let { w ->
        MoveDialog(d?.groups.orEmpty(), w.werd.groupId,
            onPick = { gid -> scope.launch { app.repo.moveToGroup(w.werd.id, gid) }; moveTarget = null },
            onDismiss = { moveTarget = null })
    }
    shareTarget?.let { w ->
        com.werd.app.ui.sources.ShareWerdDialog(app, w.werd.id, onDismiss = { shareTarget = null },
            onShared = { shareTarget = null; scope.launch { snack.showSnackbar(sharedMsg) } })
    }
    deleteTarget?.let { w ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_werd_q)) },
            text = { Text(stringResource(R.string.delete_werd_msg, w.werd.title)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { app.repo.deleteWerd(w.werd.id) }; deleteTarget = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private suspend fun reorder(app: WerdApp, d: Dashboard, w: WerdUi, dir: Int) {
    val list = d.werds.map { it.werd }.toMutableList()
    val i = list.indexOfFirst { it.id == w.werd.id }
    val j = i + dir
    if (i < 0 || j !in list.indices) return
    val t = list[i]; list[i] = list[j]; list[j] = t
    app.repo.reorderWerds(list)
}

@Composable
private fun buildSections(d: Dashboard, mode: GroupMode): List<Section> {
    val other = stringResource(R.string.group_other)
    val morning = stringResource(R.string.time_morning)
    val afternoon = stringResource(R.string.time_afternoon)
    val evening = stringResource(R.string.time_evening)
    val night = stringResource(R.string.time_night)
    val anytime = stringResource(R.string.time_anytime)
    val todo = stringResource(R.string.status_todo)
    val done = stringResource(R.string.status_done)
    val notToday = stringResource(R.string.status_not_today)
    val texts = stringResource(R.string.status_texts)
    return when (mode) {
        GroupMode.NONE -> listOf(Section("all", "", null, d.werds, null))
        GroupMode.GROUP -> {
            val byGroup = d.werds.groupBy { it.werd.groupId }
            d.groups.map { g -> Section("g_${g.id}", g.name, g, byGroup[g.id].orEmpty(), WerdPalette[g.colorIndex % WerdPalette.size]) } +
                listOfNotNull(byGroup[null]?.takeIf { it.isNotEmpty() }?.let { Section("g_none", other, null, it, null) })
        }
        GroupMode.TIME -> {
            fun minutes(w: WerdUi): Int? = w.reminders.filter { it.enabled }.mapNotNull { r ->
                when (r.type) {
                    ReminderType.DAILY, ReminderType.INTERVAL -> r.hour * 60 + r.minute
                    ReminderType.PRAYER -> com.werd.app.reminder.ReminderScheduler.occurrenceOn(r, java.time.LocalDate.now())
                        ?.let { it.hour * 60 + it.minute }
                    else -> null
                }
            }.minOrNull()
            val buckets = listOf(
                "t_m" to morning, "t_a" to afternoon, "t_e" to evening, "t_n" to night, "t_x" to anytime,
            )
            val by = d.werds.groupBy { w ->
                when (val m = minutes(w)) {
                    null -> "t_x"
                    in 240 until 660 -> "t_m"
                    in 660 until 960 -> "t_a"
                    in 960 until 1200 -> "t_e"
                    else -> "t_n"
                }
            }
            buckets.mapNotNull { (k, t) -> by[k]?.let { Section(k, t, null, it.sortedBy { w -> minutes(w) ?: 0 }, null) } }
        }
        GroupMode.STATUS -> listOfNotNull(
            d.werds.filter { it.dueToday && !it.completedToday }.takeIf { it.isNotEmpty() }?.let { Section("s_todo", todo, null, it, null) },
            d.werds.filter { it.completedToday }.takeIf { it.isNotEmpty() }?.let { Section("s_done", done, null, it, null) },
            d.werds.filter { !it.dueToday && !it.completedToday && !it.werd.textOnly }.takeIf { it.isNotEmpty() }?.let { Section("s_off", notToday, null, it, null) },
            d.werds.filter { it.werd.textOnly && !it.completedToday }.takeIf { it.isNotEmpty() }?.let { Section("s_text", texts, null, it, null) },
        )
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun HomeHeader(d: Dashboard?, onOpen: (Long) -> Unit) {
    val locale = currentLocale()
    val x = LocalExtra.current
    val session by com.werd.app.tts.ReadAloud.session.collectAsStateWithLifecycle()
    val ra by com.werd.app.tts.ReadAloud.state.collectAsStateWithLifecycle()
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("ورد", fontFamily = Amiri, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(Days.gregorian(locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Days.hijri(locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        val due = d?.dueToday.orEmpty()
        val doneN = d?.doneToday ?: 0
        val next = d?.next
        val reading = session
        // Today: the day's awrad as a string of beads, and one button that goes on with the next one
        Surface(shape = RoundedCornerShape(28.dp), color = x.hero, contentColor = x.onHero) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.today_awrad), style = MaterialTheme.typography.titleLarge)
                        Text(
                            when {
                                reading != null -> stringResource(R.string.ra_now, reading.title)
                                due.isEmpty() -> stringResource(R.string.nothing_due)
                                doneN >= due.size -> stringResource(R.string.all_done_today)
                                else -> stringResource(R.string.remaining_today, due.size - doneN)
                            },
                            style = MaterialTheme.typography.bodyMedium, color = x.heroMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val target = reading?.werdId ?: next?.werd?.id
                    if (target != null) Surface(
                        onClick = {
                            if (reading != null) { if (ra.speaking) com.werd.app.tts.ReadAloud.pause() else com.werd.app.tts.ReadAloud.resume() }
                            else onOpen(target)
                        },
                        shape = CircleShape, color = x.heroBead, contentColor = x.hero, modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (reading != null && ra.speaking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                stringResource(if (reading != null && ra.speaking) R.string.ra_pause else R.string.listen), Modifier.size(30.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (due.isNotEmpty()) com.werd.app.ui.components.BeadString(
                        total = due.size, done = doneN, bead = 14.dp, gap = 6.dp, max = 12,
                        doneSet = due.withIndex().filter { it.value.completedToday }.map { it.index }.toSet(),
                        current = next?.let { n -> due.indexOfFirst { it.werd.id == n.werd.id } } ?: -1,
                        fill = x.heroBead, empty = x.heroBeadEmpty, ring = x.onHero,
                    )
                    Spacer(Modifier.weight(1f))
                    if ((d?.dayStreak ?: 0) > 0) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = x.heroBead, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.streak_short, d?.dayStreak ?: 0), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (reading == null && next != null) {
                    Spacer(Modifier.height(12.dp))
                    Surface(onClick = { onOpen(next.werd.id) }, shape = RoundedCornerShape(16.dp), color = x.onHero.copy(alpha = 0.08f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(stringResource(R.string.next_up) + " · " + next.werd.title + (next.todayMinutes?.let { " · " + timeLabel(it) } ?: ""),
                                style = MaterialTheme.typography.labelLarge, color = x.heroMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            next.items.getOrNull(next.currentIndex)?.let {
                                Text(it.text, fontFamily = Amiri, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(textDirection = TextDirection.Content))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextCard(n: WerdUi, onClick: () -> Unit) {
    val x = LocalExtra.current
    val item = n.items.getOrNull(n.currentIndex)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, x.gold.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.next_up) + (n.todayMinutes?.let { " · " + timeLabel(it) } ?: ""),
                    color = x.gold, style = MaterialTheme.typography.labelMedium)
                Text(n.werd.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it.text, fontFamily = Amiri, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { n.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

fun timeLabel(minutes: Int): String = String.format(java.util.Locale.US, "%d:%02d", minutes / 60, minutes % 60)

@Composable
private fun SectionHeader(sec: Section, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sec.color != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(sec.color)); Spacer(Modifier.width(10.dp)) }
        Text(sec.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("${sec.werds.count { it.completedToday }}/${sec.werds.size}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WerdCard(
    w: WerdUi, voiced: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onDuplicate: () -> Unit,
    onReset: () -> Unit, onComplete: () -> Unit, onDelete: () -> Unit, onUp: () -> Unit, onDown: () -> Unit,
    onShare: () -> Unit = {},
) {
    val x = LocalExtra.current
    val accent = WerdPalette[w.werd.colorIndex % WerdPalette.size]
    var menu by remember { mutableStateOf(false) }
    val bg by animateFloatAsState(if (w.completedToday) 1f else 0f, label = "bg")
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = if (bg > 0.5f) x.done else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (w.completedToday) accent.copy(alpha = 0.3f) else x.cardBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(w.werd.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (w.completedToday) x.onDone else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                    if (w.completedToday) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.CheckCircle, null, tint = x.onDone, modifier = Modifier.size(18.dp)) }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (w.werd.textOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(14.dp), tint = x.gold)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.text_to_read), style = MaterialTheme.typography.bodySmall, color = x.gold)
                    } else Text(stringResource(R.string.lines_count, w.items.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (w.streak > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(14.dp), tint = x.gold)
                        Text("${w.streak}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    val enabled = w.reminders.count { it.enabled }
                    if (enabled > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.NotificationsNone, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(w.todayMinutes?.let { timeLabel(it) } ?: "$enabled", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!w.dueToday && !w.werd.textOnly) Text(stringResource(R.string.not_today_short), style = MaterialTheme.typography.bodySmall,
                        color = x.gold)
                    if (voiced) Icon(Icons.Rounded.RecordVoiceOver, stringResource(R.string.voice_all_ready), Modifier.size(15.dp),
                        tint = x.bead)
                }
                // the line to go on with, then the werd as a string of beads (one per line)
                if (w.werd.textOnly) w.items.firstOrNull()?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it.text, fontFamily = Amiri, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!w.werd.textOnly && !w.completedToday && w.done > 0) w.items.getOrNull(w.currentIndex)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it.text, fontFamily = Amiri, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurface)
                }
                if (!w.werd.textOnly) Spacer(Modifier.height(10.dp))
                if (!w.werd.textOnly) com.werd.app.ui.components.BeadString(
                    total = w.items.size, done = 0, max = 22,
                    doneSet = w.items.withIndex().filter { (_, it) -> (w.counts[it.id] ?: 0) >= it.target }.map { it.index }.toSet(),
                    current = if (w.completedToday || w.done == 0) -1 else w.currentIndex,
                    fill = if (w.completedToday) x.onDone else x.bead,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.edit, Icons.Rounded.Edit) { menu = false; onEdit() }
                    MenuItem(R.string.move_to_group, Icons.AutoMirrored.Rounded.DriveFileMove) { menu = false; onMove() }
                    MenuItem(R.string.move_up, Icons.Rounded.ArrowUpward) { menu = false; onUp() }
                    MenuItem(R.string.move_down, Icons.Rounded.ArrowDownward) { menu = false; onDown() }
                    MenuItem(R.string.duplicate, Icons.Rounded.ContentCopy) { menu = false; onDuplicate() }
                    MenuItem(R.string.wlib_share, Icons.Rounded.Share) { menu = false; onShare() }
                    if (!w.completedToday) MenuItem(if (w.werd.textOnly) R.string.mark_read else R.string.mark_complete, Icons.Rounded.DoneAll) { menu = false; onComplete() }
                    MenuItem(R.string.reset_today, Icons.Rounded.Refresh) { menu = false; onReset() }
                    MenuItem(R.string.delete, Icons.Rounded.Delete) { menu = false; onDelete() }
                }
            }
        }
    }
}

@Composable
fun MenuItem(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun GroupModeButton(mode: GroupMode, onChange: (GroupMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        GroupMode.GROUP to R.string.group_by_group, GroupMode.TIME to R.string.group_by_time,
        GroupMode.STATUS to R.string.group_by_status, GroupMode.NONE to R.string.group_by_none,
    )
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.ViewAgenda, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(labels.getValue(mode)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(stringResource(R.string.group_by), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            GroupMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(labels.getValue(m)), fontWeight = if (m == mode) FontWeight.Bold else null) },
                    onClick = { open = false; onChange(m) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        com.werd.app.ui.components.BeadString(total = 3, done = 0, current = 0, bead = 16.dp, gap = 8.dp)
        Spacer(Modifier.height(14.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onNew) { Text(stringResource(R.string.new_werd)) }
    }
}

@Composable
private fun MoveDialog(groups: List<WerdGroup>, current: Long?, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_group)) },
        text = {
            Column {
                (listOf<WerdGroup?>(null) + groups).forEach { g ->
                    val sel = g?.id == current
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(g?.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(g?.let { WerdPalette[it.colorIndex % WerdPalette.size] } ?: MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.width(12.dp))
                        Text(g?.name ?: stringResource(R.string.group_other), fontWeight = if (sel) FontWeight.Bold else null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun GroupsSheet(app: WerdApp, groups: List<WerdGroup>, onDismiss: () -> Unit, onReminder: (Long) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<WerdGroup?>(null) }
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_groups)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (groups.isEmpty()) Text(stringResource(R.string.no_groups), color = MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(WerdPalette[g.colorIndex % WerdPalette.size]))
                        Spacer(Modifier.width(10.dp))
                        Text(g.name, modifier = Modifier.weight(1f).clickable { edit = g }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = {
                            if (i > 0) scope.launch { app.repo.saveGroups(groups.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }
                        }) { Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(18.dp)) }
                        IconButton(onClick = { onReminder(g.id) }) {
                            Icon(Icons.Rounded.NotificationAdd, stringResource(R.string.group_reminder), Modifier.size(18.dp))
                        }
                        IconButton(onClick = { edit = g }) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) }
                        IconButton(onClick = { scope.launch { app.repo.deleteGroup(g.id) } }) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_group))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
    if (adding || edit != null) {
        GroupEditDialog(
            initial = edit,
            onDismiss = { adding = false; edit = null },
            onSave = { name, color ->
                scope.launch {
                    val e = edit
                    if (e == null) app.repo.addGroup(name, color)
                    else app.repo.saveGroups(groups.map { if (it.id == e.id) it.copy(name = name, colorIndex = color) else it })
                }
                adding = false; edit = null
            },
        )
    }
}

@Composable
private fun GroupEditDialog(initial: WerdGroup?, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.colorIndex ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_group else R.string.edit_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                com.werd.app.ui.components.ColorDots(color, WerdPalette) { color = it }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), color) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Suppress("unused")
@Composable
private fun Unused() = AnimatedVisibility(true) {}


/** A werd a family member sent: add it to my awrad, or dismiss it. */
@Composable
private fun InboxCard(w: com.werd.app.data.InboxWerd, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, x.gold.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Diversity3, null, tint = x.gold); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.inbox_from, w.fromName), style = MaterialTheme.typography.labelLarge, color = x.gold)
            }
            Text(w.title, style = MaterialTheme.typography.titleMedium)
            if (w.note.isNotBlank()) Text(w.note, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.inbox_add)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.inbox_dismiss)) }
            }
        }
    }
}
