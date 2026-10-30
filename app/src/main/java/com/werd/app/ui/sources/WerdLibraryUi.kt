package com.werd.app.ui.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.cloud.CloudRepo
import com.werd.app.cloud.SharedWerd
import com.werd.app.data.ReminderType
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Search state of the shared awrad (the library's second shelf). */
class WerdLibraryState {
    var query by mutableStateOf("")
    /** "" everything I can see, "public", "family", "mine". */
    var scope by mutableStateOf("")
    var werds by mutableStateOf<List<SharedWerd>>(emptyList())
    var total by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var loaded by mutableStateOf(false)
    var open by mutableStateOf<SharedWerd?>(null)
    var reload by mutableIntStateOf(0)
}

@Composable
fun WerdLibrarySearchEffect(app: WerdApp, st: WerdLibraryState, active: Boolean) {
    LaunchedEffect(st.query, st.scope, active, st.reload) {
        if (!active || !app.cloud.store.value.configured) return@LaunchedEffect
        if (st.loaded) delay(400)
        st.loading = true; st.error = null
        runCatching { withContext(Dispatchers.IO) { app.cloud.searchWerds(st.query.trim(), st.scope) } }
            .onSuccess { st.werds = it.werds; st.total = it.total }
            .onFailure { st.error = it }
        st.loading = false; st.loaded = true
    }
}

fun LazyListScope.werdLibraryItems(st: WerdLibraryState, inFamily: Boolean) {
    item {
        OutlinedTextField(st.query, { st.query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.wlib_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(16.dp))
    }
    item {
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val scopes = listOfNotNull("" to R.string.wlib_all, "public" to R.string.wlib_public,
                ("family" to R.string.wlib_family).takeIf { inFamily }, "mine" to R.string.wlib_mine)
            items(scopes) { (k, label) -> FilterChip(st.scope == k, { st.scope = k }, label = { Text(stringResource(label)) }) }
        }
    }
    item {
        Text(stringResource(R.string.wlib_intro), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    }
    if (st.loading && st.werds.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
    st.error?.let { e -> item { Text(cloudError(e), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(20.dp)) } }
    if (st.loaded && !st.loading && st.error == null && st.werds.isEmpty()) item {
        Text(stringResource(if (st.query.isBlank()) R.string.wlib_empty else R.string.lib_no_results), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    }
    items(st.werds, key = { "w_" + it.id }) { w -> SharedWerdCard(w) { st.open = w } }
}

@Composable
private fun SharedWerdCard(w: SharedWerd, onOpen: () -> Unit) {
    val x = LocalExtra.current
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (w.textOnly) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Repeat, null, Modifier.size(18.dp), tint = x.gold)
                Spacer(Modifier.width(8.dp))
                Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (w.visibility == "family") Icon(Icons.Rounded.FamilyRestroom, stringResource(R.string.wlib_family), Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            w.lines.firstOrNull()?.let { (t, _) ->
                Text(t, fontFamily = Amiri, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(textDirection = TextDirection.Content), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (w.textOnly) stringResource(R.string.text_to_read) else stringResource(R.string.lines_count, w.lineCount),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (w.hasSettings) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsNone, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.wlib_with_settings), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (w.mine) stringResource(R.string.wlib_by_me) else stringResource(R.string.wlib_by, w.publisher),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** A shared werd: its lines, what comes with it (each part can be left out), and Add — or, for the sharer, move / withdraw. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedWerdDialog(app: WerdApp, summary: SharedWerd, inFamily: Boolean, onDismiss: () -> Unit, onAdded: (Long) -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var w by remember { mutableStateOf<SharedWerd?>(null) }
    var err by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(summary.id) {
        runCatching { withContext(Dispatchers.IO) { app.cloud.sharedWerd(summary.id) } }
            .onSuccess { w = it }.onFailure { err = it }
    }
    val full = w
    val set = remember(full) { runCatching { JSONObject(full?.settings.orEmpty()) }.getOrNull() }
    val reminders = remember(full) { app.cloud.sharedReminders(full?.settings.orEmpty()) }
    var keepGroup by remember { mutableStateOf(true) }
    var keepDays by remember { mutableStateOf(true) }
    // time reminders are kept by default; place, Wi-Fi and Bluetooth ones only if picked (they were someone else's places)
    val keepRem = remember(reminders) { mutableStateListOf<Int>().apply {
        addAll(reminders.indices.filter { runCatching { ReminderType.valueOf(reminders[it].optString("type")).isTime }.getOrDefault(false) }) } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(summary.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOf(if (summary.mine) stringResource(R.string.wlib_by_me) else stringResource(R.string.wlib_by, summary.publisher),
                    stringResource(if (summary.visibility == "family") R.string.wlib_family else R.string.wlib_public)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                err?.let { Text(cloudError(it), color = MaterialTheme.colorScheme.error) }
                if (full == null && err == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                full?.let { f ->
                    if (f.note.isNotBlank()) Text(f.note, style = MaterialTheme.typography.bodySmall)
                    f.lines.take(8).forEach { (t, n) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(t, fontFamily = Amiri, fontSize = 17.sp, lineHeight = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
                            if (!f.textOnly && n > 1) Text("×$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (f.lines.size > 8) Text(stringResource(R.string.wlib_more_lines, f.lines.size - 8), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!summary.mine && f.hasSettings && set != null) {
                        HorizontalDivider()
                        Text(stringResource(R.string.wlib_comes_with), style = MaterialTheme.typography.labelLarge)
                        val group = set.optString("group")
                        if (group.isNotBlank()) KeepRow(stringResource(R.string.wlib_keep_group, group), keepGroup) { keepGroup = it }
                        if (!f.textOnly && set.optInt("goalDaysMask", 127) != 127) KeepRow(stringResource(R.string.wlib_keep_days), keepDays) { keepDays = it }
                        reminders.forEachIndexed { i, r ->
                            KeepRow(reminderLabel(r), i in keepRem, warn = runCatching { !ReminderType.valueOf(r.optString("type")).isTime }.getOrDefault(false)) { on ->
                                if (on) keepRem += i else keepRem -= i
                            }
                        }
                    }
                    if (summary.mine) {
                        HorizontalDivider()
                        Text(stringResource(R.string.wlib_yours), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val target = if (f.visibility == "family") "public" else "family"
                            if (target == "public" || inFamily) OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { app.cloud.moveSharedWerd(f.id, target) } }
                                        .onSuccess { onChanged() }.onFailure { err = it }
                                    busy = false
                                }
                            }) { Text(stringResource(if (target == "public") R.string.wlib_move_public else R.string.wlib_move_family)) }
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { app.cloud.withdrawSharedWerd(f.id) } }
                                        .onSuccess { onChanged() }.onFailure { err = it }
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.wlib_withdraw), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = full != null && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.cloud.addShared(summary.id, CloudRepo.Keep(keepGroup, keepDays, keepRem.toSet())) } }
                        .onSuccess { onAdded(it) }.onFailure { err = it; busy = false }
                }
            }) { Text(stringResource(R.string.wlib_add)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun KeepRow(label: String, on: Boolean, warn: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, onChange)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (warn) Text(stringResource(R.string.wlib_place_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun reminderLabel(r: JSONObject): String {
    val type = runCatching { ReminderType.valueOf(r.optString("type")) }.getOrNull()
    val time = "%02d:%02d".format(java.util.Locale.ROOT, r.optInt("hour"), r.optInt("minute"))
    return when (type) {
        ReminderType.PRAYER -> stringResource(R.string.wlib_rem_prayer,
            androidx.compose.ui.platform.LocalContext.current.resources.getStringArray(R.array.prayer_names).getOrElse(r.optInt("prayer", 4)) { "" })
        ReminderType.LOCATION -> stringResource(R.string.wlib_rem_place, r.optString("placeName").ifBlank { "📍" })
        ReminderType.WIFI -> stringResource(R.string.wlib_rem_wifi, r.optString("ssid"))
        ReminderType.BLUETOOTH -> stringResource(R.string.wlib_rem_bt, r.optString("btName").ifBlank { r.optString("btAddress") })
        ReminderType.ONCE -> stringResource(R.string.wlib_rem_once, time)
        else -> stringResource(R.string.wlib_rem_time, time)
    }
}

/** Share a werd to the library: public or family, with or without its reminders and group. */
@Composable
fun ShareWerdDialog(app: WerdApp, werdId: Long, onDismiss: () -> Unit, onShared: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    var vis by remember { mutableStateOf(if (cloud.inFamily) "family" else "public") }
    var keep by remember { mutableStateOf(true) }
    var places by remember { mutableStateOf(false) }
    var hasPlaces by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(werdId) { app.db.dao().werd(werdId)?.let { hasPlaces = app.cloud.hasPlaceReminders(it) } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.Share, null) },
        title = { Text(stringResource(R.string.wlib_share)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!cloud.configured) Text(stringResource(R.string.wlib_need_server), color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().clickable { vis = "public" }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(vis == "public", { vis = "public" })
                    Column { Text(stringResource(R.string.wlib_public)); Text(stringResource(R.string.wlib_public_d), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = cloud.inFamily) { vis = "family" }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(vis == "family", { vis = "family" }, enabled = cloud.inFamily)
                    Column { Text(stringResource(R.string.wlib_family)); Text(stringResource(if (cloud.inFamily) R.string.wlib_family_d else R.string.wlib_no_family),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.wlib_keep_settings))
                        Text(stringResource(R.string.wlib_keep_settings_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(keep, { keep = it })
                }
                if (keep && hasPlaces) Row(Modifier.fillMaxWidth().clickable { places = !places }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(places, { places = it })
                    Column {
                        Text(stringResource(R.string.wlib_include_places))
                        Text(stringResource(R.string.wlib_include_places_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.wlib_never_progress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                err?.let { Text(cloudError(it), color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = cloud.configured && !busy, onClick = {
                busy = true; err = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        app.cloud.shareWerd(werdId, vis, keep, keep && places)
                    } }.onSuccess { onShared() }.onFailure { err = it; busy = false }
                }
            }) { Text(stringResource(R.string.wlib_share_go)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
