package com.werd.app.ui.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
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
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.cloud.LibraryBook
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Search state of the shared library (kept by the Sources screen across tab switches). */
class LibraryState {
    var query by mutableStateOf("")
    /** "" = every madhhab. */
    var madhhab by mutableStateOf("")
    var books by mutableStateOf<List<LibraryBook>>(emptyList())
    var total by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var loaded by mutableStateOf(false)
    var open by mutableStateOf<LibraryBook?>(null)
}

/** Runs the search when the query changes (after a short pause while typing). */
@Composable
fun LibrarySearchEffect(app: WerdApp, st: LibraryState, active: Boolean) {
    LaunchedEffect(st.query, st.madhhab, active) {
        if (!active || !app.cloud.store.value.configured) return@LaunchedEffect
        if (st.loaded) delay(450)
        st.loading = true; st.error = null
        runCatching { withContext(Dispatchers.IO) { app.cloud.search(st.query.trim(), madhhab = st.madhhab) } }
            .onSuccess { st.books = it.books; st.total = it.total }
            .onFailure { st.error = it }
        st.loading = false; st.loaded = true
    }
}

/** The library list, as items of the Sources screen's list. */
fun LazyListScope.libraryItems(st: LibraryState, configured: Boolean, have: Set<String>) {
    if (!configured) {
        item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, Modifier.size(48.dp), tint = LocalExtra.current.gold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.lib_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.lib_soon_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    item {
        OutlinedTextField(st.query, { st.query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.lib_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(16.dp))
    }
    item {
        // filter by madhhab
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(st.madhhab == "", { st.madhhab = "" }, label = { Text(stringResource(R.string.madhhab_all)) }) }
            items(com.werd.app.cloud.MADHHABS) { m ->
                FilterChip(st.madhhab == m, { st.madhhab = if (st.madhhab == m) "" else m }, label = { Text(madhhabLabel(m)) })
            }
        }
    }
    item {
        Text(stringResource(R.string.lib_intro), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    }
    if (st.loading && st.books.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
    st.error?.let { e -> item { ErrorRow(cloudError(e)) } }
    if (st.loaded && !st.loading && st.error == null && st.books.isEmpty()) item {
        Text(stringResource(if (st.query.isBlank()) R.string.lib_empty else R.string.lib_no_results), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    }
    items(st.books, key = { "lib_" + it.id }) { b -> LibraryCard(b, downloaded = b.id in have, onOpen = { st.open = b }) }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun madhhabLabel(m: String): String = stringResource(when (m) {
    "general" -> R.string.madhhab_general
    "hanafi" -> R.string.madhhab_hanafi
    "maliki" -> R.string.madhhab_maliki
    "shafii" -> R.string.madhhab_shafii
    "hanbali" -> R.string.madhhab_hanbali
    "jafari" -> R.string.madhhab_jafari
    "other" -> R.string.madhhab_other
    else -> R.string.madhhab_none
})

@Composable
private fun ErrorRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LibraryCard(b: LibraryBook, downloaded: Boolean, onOpen: () -> Unit) {
    val x = LocalExtra.current
    Surface(onClick = onOpen, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = x.gold.copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, tint = x.gold) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (b.author.isNotBlank()) Text(b.author, style = MaterialTheme.typography.bodySmall, color = x.gold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.lib_meta, b.items, b.downloads), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (b.visibility == "family" || b.madhhab.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    if (b.visibility == "family") Tag(stringResource(R.string.lib_family_only), MaterialTheme.colorScheme.secondaryContainer)
                    if (b.madhhab.isNotBlank()) Tag(madhhabLabel(b.madhhab), x.gold.copy(alpha = 0.16f))
                }
            }
            if (downloaded) Icon(Icons.Rounded.DownloadDone, stringResource(R.string.lib_have), tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One library book: a look at its first supplications, then download (or open the copy already here), or report. */
@Composable
fun LibraryBookDialog(app: WerdApp, book: LibraryBook, onDismiss: () -> Unit, onOpenSource: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var reporting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<Long?>(null) }
    val errText = cloudError(error)
    LaunchedEffect(book.id) {
        local = withContext(Dispatchers.IO) { app.db.dao().sourceByLibraryId(book.id)?.id }
        preview = runCatching {
            withContext(Dispatchers.IO) {
                val o: JSONObject = app.cloud.api().previewBook(book.id)
                val a = o.optJSONArray("items")
                (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it).let { i -> i.optString("title") to i.optString("text") } }
            }
        }.getOrDefault(emptyList())
    }
    if (reporting) {
        ReportDialog(onDismiss = { reporting = false }, onSend = { reason ->
            reporting = false
            scope.launch { runCatching { withContext(Dispatchers.IO) { app.cloud.report(book.id, reason) } }.onSuccess { reported = true }.onFailure { error = it } }
        })
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(book.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (book.author.isNotBlank()) Text(book.author, color = LocalExtra.current.gold, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.lib_meta, book.items, book.downloads), style = MaterialTheme.typography.bodySmall)
                if (book.publisher.isNotBlank()) Text(stringResource(R.string.lib_by, book.publisher), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Spellcheck, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.lib_proofread_all), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                when (val p = preview) {
                    null -> Text("…", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> p.forEach { (t, txt) ->
                        Column {
                            Text(t, style = MaterialTheme.typography.labelLarge)
                            Text(txt, style = TextStyle(fontFamily = Amiri, fontSize = 17.sp, lineHeight = 29.sp, textDirection = TextDirection.Content),
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (error != null) Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (reported) Text(stringResource(R.string.lib_reported), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { reporting = true }, enabled = !reported) {
                    Icon(Icons.Rounded.Flag, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lib_report))
                }
            }
        },
        confirmButton = {
            val have = local
            if (have != null) Button(onClick = { onDismiss(); onOpenSource(have) }) { Text(stringResource(R.string.src_open)) }
            else Button(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { app.cloud.download(book.id) } }
                    busy = false
                    withContext(Dispatchers.Main.immediate) { r.onSuccess { onDismiss(); onOpenSource(it) }.onFailure { error = it } }
                }
            }) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lib_download)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_report)) },
        text = {
            OutlinedTextField(reason, { reason = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.lib_report_hint)) })
        },
        confirmButton = { Button(enabled = reason.isNotBlank(), onClick = { onSend(reason) }) { Text(stringResource(R.string.lib_report_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
