package com.werd.app.ui.sources

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Source
import com.werd.app.sources.SourceWorker
import com.werd.app.sources.Sources
import com.werd.app.ui.components.OrnateHeader
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun statusLabel(s: Source): String = if (s.kind == Sources.KIND_IMPORTED)
    stringResource(if (s.libraryId.isNotBlank()) R.string.src_from_library else R.string.src_from_file) else when (s.status) {
    "RUNNING", "QUEUED" -> stringResource(R.string.src_reading, s.pagesDone, s.pageCount)
    "PAUSED" -> stringResource(R.string.src_paused, s.pagesDone, s.pageCount)
    "ERROR" -> stringResource(R.string.src_error)
    else -> stringResource(R.string.src_done, s.pageCount)
}

fun kindIcon(kind: String): ImageVector = when (kind) {
    Sources.KIND_PDF -> Icons.Rounded.PictureAsPdf
    Sources.KIND_IMPORTED -> Icons.AutoMirrored.Rounded.LibraryBooks
    Sources.KIND_IMAGES -> Icons.Rounded.PhotoLibrary
    else -> Icons.Rounded.Description
}

/** The library of books/documents the user added. */
@Composable
fun SourcesScreen(app: WerdApp, onOpen: (Long) -> Unit, onSettings: () -> Unit, onOpenWerd: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    // flows are created once; a new flow per recomposition would restart collection in a loop
    val sourcesFlow = remember { app.db.dao().sourcesFlow() }
    val countsFlow = remember { app.db.dao().sourceItemCounts() }
    val sources by sourcesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val counts by countsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val countBy = counts.associate { it.id to it.n }
    var pick by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<List<Uri>, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val lib = remember { LibraryState() }
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    // the library has two shelves: books, and single awrad people shared
    var shelf by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val wlib = remember { WerdLibraryState() }
    LibrarySearchEffect(app, lib, active = tab == 1 && shelf == 0)
    WerdLibrarySearchEffect(app, wlib, active = tab == 1 && shelf == 1)
    val badBookMsg = stringResource(R.string.book_bad_file)
    val snackbar = remember { SnackbarHostState() }
    val bookLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val id = runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    com.werd.app.sources.BookFile.import(app.db.dao(), com.werd.app.sources.BookFile.parse(text))
                }
            }.getOrNull()
            withContext(Dispatchers.Main.immediate) { if (id != null) onOpen(id) }
            if (id == null) snackbar.showSnackbar(badBookMsg)
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { pending = listOf(it) to Sources.KIND_PDF }
    }
    val imagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(200)) { us ->
        if (us.isNotEmpty()) pending = us to Sources.KIND_IMAGES
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { pending = listOf(it) to Sources.KIND_TEXT }
    }
    val fabLabel = stringResource(R.string.src_add)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item {
                OrnateHeader {
                    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(20.dp)) {
                        Text(stringResource(R.string.tab_sources), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(stringResource(R.string.src_sub), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
            item {
                SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.lib_mine)) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.lib_title)) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null) })
                }
            }
            if (tab == 1 && cloud.configured) item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    SegmentedButton(shelf == 0, { shelf = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.wlib_books)) }
                    SegmentedButton(shelf == 1, { shelf = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.wlib_awrad)) }
                }
            }
            if (tab == 1 && (shelf == 0 || !cloud.configured)) libraryItems(lib, cloud.configured, sources.mapNotNull { it.libraryId.takeIf { id -> id.isNotBlank() } }.toSet())
            if (tab == 1 && shelf == 1 && cloud.configured) werdLibraryItems(wlib, cloud.inFamily)
            if (tab == 0 && !settings.aiReady) item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = LocalExtra.current.gold)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.src_need_ai_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.src_need_ai_body), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onSettings) { Text(stringResource(R.string.ai_setup)) }
                    }
                }
            }
            if (tab == 0 && sources.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(48.dp), tint = LocalExtra.current.gold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.src_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.src_empty_body), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tab == 0) items(sources, key = { it.id }) { s ->
                SourceCard(s, countBy[s.id] ?: 0, onOpen = { onOpen(s.id) },
                    onPause = { scope.launch { SourceWorker.pause(context, s.id) } },
                    onResume = { scope.launch { SourceWorker.resume(context, s.id) } },
                    onDelete = { confirmDelete = s })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        if (tab == 0) ExtendedFloatingActionButton(
            onClick = { pick = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(fabLabel) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.BottomRight).padding(18.dp).semantics { contentDescription = fabLabel },
        )
    }

    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text(stringResource(R.string.src_add)) },
            text = {
                Column {
                    PickRow(Icons.Rounded.PictureAsPdf, R.string.src_pick_pdf, R.string.src_pick_pdf_d) { pick = false; pdfLauncher.launch(arrayOf("application/pdf")) }
                    PickRow(Icons.Rounded.PhotoLibrary, R.string.src_pick_photos, R.string.src_pick_photos_d) {
                        pick = false; imagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    PickRow(Icons.Rounded.Description, R.string.src_pick_text, R.string.src_pick_text_d) {
                        pick = false; textLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                    }
                    PickRow(Icons.AutoMirrored.Rounded.LibraryBooks, R.string.src_pick_book, R.string.src_pick_book_d) {
                        pick = false; bookLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pending?.let { (uris, kind) ->
        var title by remember(uris) { mutableStateOf(Sources.displayName(context, uris.first())) }
        var useAi by remember(uris) { mutableStateOf(settings.aiReady) }
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(stringResource(R.string.src_name_q)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.werd_title)) },
                        modifier = Modifier.fillMaxWidth())
                    if (kind == Sources.KIND_IMAGES) Text(stringResource(R.string.src_photos_count, uris.size), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.mode_q), style = MaterialTheme.typography.labelLarge)
                    ReadModePicker(useAi, settings.aiReady, onChange = { useAi = it },
                        onSetupAi = { pending = null; onSettings() }, compact = true)
                    val ai = useAi && settings.aiReady
                    if (!ai && kind != Sources.KIND_TEXT) com.werd.app.ui.settings.ReaderEnginePicker(compact = true)
                    if (kind != Sources.KIND_TEXT) ScanModePicker(settings.scanMode, { m -> app.settings.update { it.copy(scanMode = m) } },
                        settings.checkReader, { r -> app.settings.update { it.copy(checkReader = r) } })
                    Text(if (ai) stringResource(R.string.src_privacy, if (settings.aiProvider == "claude") "Anthropic" else "Google")
                        else stringResource(R.string.mode_offline_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val id = runCatching { withContext(Dispatchers.IO) { Sources.add(context, uris, kind, title, useAi = useAi && settings.aiReady, scanMode = settings.scanMode, checkReader = settings.checkReader) } }.getOrNull()
                        busy = false; pending = null
                        if (id != null) withContext(Dispatchers.Main.immediate) { onOpen(id) }
                    }
                }) { Text(stringResource(R.string.src_start)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    lib.open?.let { b -> LibraryBookDialog(app, b, onDismiss = { lib.open = null }, onOpenSource = onOpen) }
    wlib.open?.let { w -> SharedWerdDialog(app, w, cloud.inFamily, onDismiss = { wlib.open = null },
        onAdded = { id -> wlib.open = null; onOpenWerd(id) }, onChanged = { wlib.open = null; wlib.reload++ }) }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.src_delete_q)) },
            text = { Text(stringResource(R.string.src_delete_msg, s.title)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; scope.launch { Sources.delete(context, s) } }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "AI · model" or "On this phone" under a source's name. */
@Composable
fun ModeLabel(s: Source) {
    if (s.kind == Sources.KIND_IMPORTED && s.author.isNotBlank()) {
        Text(s.author, style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    if (s.kind == Sources.KIND_IMPORTED) return
    val offline = s.provider == Sources.OFFLINE
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(if (offline) Icons.Rounded.PhoneAndroid else Icons.Rounded.AutoAwesome, null, Modifier.size(13.dp), tint = LocalExtra.current.gold)
        Spacer(Modifier.width(4.dp))
        Text(if (offline) stringResource(R.string.mode_offline) else listOf(stringResource(R.string.mode_ai), s.model).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickRow(icon: ImageVector, title: Int, desc: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceCard(s: Source, found: Int, onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    val x = LocalExtra.current
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).then(Modifier), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = x.gold.copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(kindIcon(s.kind), null, tint = x.gold) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.src_found, found) + " · " + statusLabel(s), style = MaterialTheme.typography.bodySmall,
                        color = if (s.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    ModeLabel(s)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s.status == "RUNNING" || s.status == "QUEUED")
                            DropdownMenuItem(text = { Text(stringResource(R.string.src_pause)) }, leadingIcon = { Icon(Icons.Rounded.Pause, null) },
                                onClick = { menu = false; onPause() })
                        if (s.status == "PAUSED" || s.status == "ERROR")
                            DropdownMenuItem(text = { Text(stringResource(R.string.src_resume)) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                                onClick = { menu = false; onResume() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (s.status != "DONE" && s.pageCount > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { s.pagesDone.toFloat() / s.pageCount },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).height(5.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
