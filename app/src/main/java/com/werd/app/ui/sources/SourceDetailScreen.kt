package com.werd.app.ui.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ai.AiTasks
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.flagged
import com.werd.app.sources.SourceImport
import com.werd.app.sources.SourceWorker
import com.werd.app.sources.Sources
import com.werd.app.ui.components.CountStepper
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.ui.theme.WerdPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.werd.app.sources.BookFile

@Composable
fun occasionNames(): Map<String, String> {
    val names = LocalContext.current.resources.getStringArray(R.array.occasion_names)
    return AiTasks.OCCASIONS.mapIndexed { i, o -> o to names.getOrElse(i) { o } }.toMap()
}

private fun bare(s: String) = s.filter { it !in 'ً'..'ٟ' && it != 'ٰ' }
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').lowercase()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourceDetailScreen(app: WerdApp, sourceId: Long, onBack: () -> Unit, onOpenWerd: (Long) -> Unit,
                       onProofread: (Long, Int) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = app.db.dao()
    val sourceFlow = remember(sourceId) { dao.sourceFlow(sourceId) }
    val itemsFlow = remember(sourceId) { dao.sourceItemsFlow(sourceId) }
    val source by sourceFlow.collectAsStateWithLifecycle(initialValue = null)
    val items by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val names = occasionNames()
    var filter by remember { mutableStateOf<String?>(null) }   // occasion, "new", or null = all
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceItem?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rescanning by remember { mutableStateOf(false) }
    var proofing by remember { mutableStateOf(false) }
    val proofStartedMsg = stringResource(R.string.pw_started)
    val titlesMsg = stringResource(R.string.titles_fixed_n)
    val titlesNoneMsg = stringResource(R.string.titles_fixed_none)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var open by remember { mutableStateOf<Long?>(null) }          // the one supplication shown in full
    var indexOpen by rememberSaveable { mutableStateOf(true) }
    var editingIndex by remember { mutableStateOf(false) }
    var readingIndex by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var indexJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var choosingMode by remember { mutableStateOf(false) }
    val noIndexMsg = stringResource(R.string.index_none)
    val indexFoundMsg = stringResource(R.string.index_found)
    val voicePrefs by app.voice.flow.collectAsStateWithLifecycle()
    val voiceEngineName = com.werd.app.ui.settings.voiceEngineLabel(voicePrefs.engine)
    val prepKey = "source:$sourceId"
    val prepProgress by com.werd.app.voice.AudioPrep.progress.collectAsStateWithLifecycle()
    val prepErrors by com.werd.app.voice.AudioPrep.errors.collectAsStateWithLifecycle()
    var publishing by remember { mutableStateOf(false) }
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exportedMsg = stringResource(R.string.book_exported)
    val publishedMsg = stringResource(R.string.lib_published)
    val rescanStartedMsg = stringResource(R.string.rescan_started)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BookFile.MIME)) { uri ->
        val s = source ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(BookFile.toJson(s, items).toString(1).toByteArray()) }
            }
            snackbar.showSnackbar(exportedMsg)
        }
    }
    val addedOneMsg = stringResource(R.string.src_added_one)
    val addedManyFmt = stringResource(R.string.src_added_many)
    val openLabel = stringResource(R.string.src_open)
    val x = LocalExtra.current

    val q = bare(query.trim())
    val shown = items.filter { it ->
        (filter == null || (filter == "new" && it.werdId == null) || (filter == "flagged" && it.flagged) || it.occasion == filter) &&
            (q.isEmpty() || bare(it.text).contains(q) || bare(it.title).contains(q) || bare(it.occasionText).contains(q))
    }
    val byOccasion = items.groupingBy { it.occasion }.eachCount()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(source?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (items.isNotEmpty()) TextButton(onClick = { onProofread(sourceId, 0) }) {
                        Icon(Icons.Rounded.Spellcheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.proof_title))
                    }
                    val src = source
                    if (src != null && items.isNotEmpty()) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.book_share)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = { menu = false; shareBook(context, src, items) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.book_export)) }, leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                                onClick = { menu = false; exportLauncher.launch(BookFile.fileName(src.title)) })
                            if (Sources.hasPages(src) && src.kind != Sources.KIND_TEXT && src.status == "DONE") DropdownMenuItem(
                                text = { Text(stringResource(if (src.indexJson.isBlank()) R.string.index_read else R.string.index_reread)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.List, null) }, enabled = readingIndex == null,
                                onClick = {
                                    menu = false
                                    indexJob = scope.launch {
                                        readingIndex = 0 to 1
                                        val r = runCatching { com.werd.app.sources.IndexReader.read(context, src) { a, b -> readingIndex = a to b } }
                                        readingIndex = null
                                        if (r.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
                                        val n = r.getOrNull()?.size ?: 0
                                        snackbar.showSnackbar(if (n > 0) indexFoundMsg.format(n) else noIndexMsg + (r.exceptionOrNull()?.message?.let { "\n$it" } ?: ""))
                                    }
                                })
                            if (src.indexJson.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.index_edit)) },
                                leadingIcon = { Icon(Icons.Rounded.EditNote, null) }, onClick = { menu = false; editingIndex = true })
                            if (Sources.hasPages(src) && src.status == "DONE" && src.rescanTo == 0)
                                DropdownMenuItem(text = { Text(stringResource(R.string.rescan_title)) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = { menu = false; rescanning = true })
                            if (src.status == "DONE" && src.proofTotal == 0)
                                DropdownMenuItem(text = { Text(stringResource(R.string.pw_title)) }, leadingIcon = { Icon(Icons.Rounded.FactCheck, null) },
                                    onClick = { menu = false; proofing = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.titles_fix)) }, leadingIcon = { Icon(Icons.Rounded.Title, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val changed = withContext(Dispatchers.IO) {
                                            com.werd.app.sources.Titles.fix(dao.sourceItems(sourceId), src.title).also { if (it.isNotEmpty()) dao.updateSourceItems(it) }
                                        }
                                        snackbar.showSnackbar(if (changed.isEmpty()) titlesNoneMsg else titlesMsg.format(changed.size))
                                    }
                                })
                            if (voicePrefs.isClip && items.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.voice_prepare_book)) },
                                leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null) },
                                onClick = { menu = false; com.werd.app.voice.AudioPrep.start(app, prepKey, items.map { it.text }) })
                            if (cloud.configured) DropdownMenuItem(
                                text = { Text(stringResource(if (src.libraryId.isNotBlank()) R.string.lib_in_library else R.string.lib_publish)) },
                                leadingIcon = { Icon(Icons.Rounded.CloudUpload, null) }, enabled = src.libraryId.isBlank(),
                                onClick = { menu = false; publishing = true })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected.clear() }) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(4.dp)); Text("${selected.size}") }
                    TextButton(onClick = { shown.forEach { if (it.id !in selected) selected += it.id } }) { Text(stringResource(R.string.src_select_all)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { adding = true }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.src_add_to_werds))
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val s = source
        val index = remember(s?.indexJson) { com.werd.app.sources.BookIndex.decode(s?.indexJson.orEmpty()) }


        LazyColumn(Modifier.fillMaxSize().padding(pad), state = listState, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (index.isNotEmpty()) { item(key = "index") {
                IndexCard(index, items, indexOpen, onToggle = { indexOpen = !indexOpen }, onEdit = { editingIndex = true }, onGo = { id ->
                    filter = null; query = ""; open = id
                    val i = items.indexOfFirst { it.id == id }
                    if (i >= 0) scope.launch { listState.animateScrollToItem(headCount(index.isNotEmpty(), s, prepProgress[prepKey] != null || prepErrors[prepKey] != null, readingIndex != null) + i) }
                })
            } }
            readingIndex?.let { (a, b) -> item(key = "readingIndex") {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.index_reading, a, b), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { indexJob?.cancel(); readingIndex = null }) { Text(stringResource(R.string.cancel)) }
                        }
                        LinearProgressIndicator(progress = { a.toFloat() / b.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            } }
            if (s != null) { item(key = "status") { StatusCard(s, items.size, items.count { it.checked }, scope = { block -> scope.launch { block() } }, app = app,
                onMode = { choosingMode = true }) } }
            val prep = prepProgress[prepKey]
            val prepErr = prepErrors[prepKey]
            if (prep != null || prepErr != null) item(key = "prep") {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (prep != null) stringResource(R.string.voice_preparing_n, prep.first + 1, prep.second)
                                else stringResource(R.string.voice_failed) + " " + prepErr.orEmpty().take(160),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (prep != null) TextButton(onClick = { com.werd.app.voice.AudioPrep.cancel(prepKey) }) { Text(stringResource(R.string.cancel)) }
                        }
                        if (prep != null) LinearProgressIndicator(progress = { (prep.first + 1f) / prep.second.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.voice_prepare_book_d, voiceEngineName), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.src_search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = RoundedCornerShape(16.dp))
            }
            item(key = "filters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text(stringResource(R.string.src_all, items.size)) })
                    val flagged = items.count { it.flagged }
                    if (flagged > 0) FilterChip(filter == "flagged", { filter = if (filter == "flagged") null else "flagged" },
                        label = { Text(stringResource(R.string.two_flagged_n, flagged)) }, leadingIcon = { Icon(Icons.Rounded.CompareArrows, null, Modifier.size(16.dp)) })
                    val notAdded = items.count { it.werdId == null }
                    if (notAdded in 1 until items.size) FilterChip(filter == "new", { filter = if (filter == "new") null else "new" },
                        label = { Text(stringResource(R.string.src_not_added, notAdded)) })
                    AiTasks.OCCASIONS.filter { (byOccasion[it] ?: 0) > 0 }.forEach { o ->
                        FilterChip(filter == o, { filter = if (filter == o) null else o }, label = { Text("${names[o]} (${byOccasion[o]})") })
                    }
                }
            }
            if (items.isEmpty() && s?.status != "DONE") item(key = "waiting") {
                Text(stringResource(R.string.src_waiting), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(shown, key = { it.id }) { it ->
                ItemCard(it, names, checked = it.id in selected, expanded = open == it.id,
                    onExpand = { open = if (open == it.id) null else it.id },
                    onToggle = { if (it.id in selected) selected -= it.id else selected += it.id },
                    onEdit = { editing = it }, onPage = { onProofread(sourceId, it.page) },
                    onOpenWerd = { it.werdId?.let(onOpenWerd) })
            }
        }
    }

    if (adding) {
        val picked = items.filter { it.id in selected }
        AddDialog(app, picked, names, settings.autoGroup, defaultTitle = source?.title.orEmpty(),
            onDismiss = { adding = false },
            onDone = { ids, combined ->
                adding = false; selected.clear()
                scope.launch {
                    val msg = if (combined) addedOneMsg else addedManyFmt.format(ids.size)
                    val r = snackbar.showSnackbar(msg, actionLabel = if (ids.size == 1) openLabel else null,
                        duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) onOpenWerd(ids.first())
                }
            })
    }
    val src = source
    if (proofing && src != null) ProofreadWithDialog(app, src, items, onDismiss = { proofing = false },
        onStarted = { proofing = false; scope.launch { snackbar.showSnackbar(proofStartedMsg) } })
    if (rescanning && src != null) RescanDialog(app, src, 1, src.pageCount, onDismiss = { rescanning = false },
        onStarted = { rescanning = false; scope.launch { snackbar.showSnackbar(rescanStartedMsg) } })
    if (publishing && src != null) PublishDialog(app, src, items, onDismiss = { publishing = false },
        onProofread = { onProofread(sourceId, 0) },
        onPublished = { publishing = false; scope.launch { snackbar.showSnackbar(publishedMsg) } })
    if (editingIndex && src != null) IndexDialog(com.werd.app.sources.BookIndex.decode(src.indexJson), items,
        onDismiss = { editingIndex = false },
        onSave = { list -> editingIndex = false; scope.launch { dao.setSourceIndex(sourceId, com.werd.app.sources.BookIndex.encode(list)) } })
    if (choosingMode && src != null) {
        var m by remember { mutableIntStateOf(src.scanMode) }
        var r by remember { mutableStateOf(src.checkReader) }
        AlertDialog(onDismissRequest = { choosingMode = false },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ScanModePicker(m, { m = it }, r, { r = it }) } },
            confirmButton = { TextButton(onClick = { choosingMode = false; scope.launch { dao.setScanMode(sourceId, m); dao.setCheckReader(sourceId, r) } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { choosingMode = false }) { Text(stringResource(R.string.cancel)) } })
    }
    editing?.let { e -> EditDialog(e, names, onDismiss = { editing = null }, onSave = { n ->
        editing = null; scope.launch { dao.updateSourceItem(n) } }, onDelete = { editing = null; scope.launch { dao.deleteSourceItem(e.id) } }) }
}

@Composable
private fun StatusCard(s: Source, found: Int, checked: Int, scope: (suspend () -> Unit) -> Unit, app: WerdApp, onMode: () -> Unit = {}) {
    val context = LocalContext.current
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, x.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(s.kind), null, tint = x.gold)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.src_found, found), style = MaterialTheme.typography.titleSmall)
                    Text(statusLabel(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ModeLabel(s)
                }
                when (s.status) {
                    "RUNNING", "QUEUED" -> TextButton(onClick = { scope { SourceWorker.pause(context, s.id) } }) { Text(stringResource(R.string.src_pause)) }
                    "PAUSED", "ERROR" -> TextButton(onClick = { scope { SourceWorker.resume(context, s.id) } }) { Text(stringResource(R.string.src_resume)) }
                }
            }
            if (s.status != "DONE" && s.pageCount > 0) LinearProgressIndicator(progress = { s.pagesDone.toFloat() / s.pageCount },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), trackColor = MaterialTheme.colorScheme.outlineVariant)
            if (s.rescanTo > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp), tint = x.gold); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.rescan_progress, s.rescanFrom, s.rescanTo), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope { SourceWorker.cancelRescan(context, s.id) } }) { Text(stringResource(R.string.cancel)) }
                }
                LinearProgressIndicator(progress = { s.rescanDone.toFloat() / (s.rescanTo - s.rescanFrom + 1) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
            if (s.libraryId.isNotBlank() && s.kind != Sources.KIND_IMPORTED) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lib_in_library), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (checked > 0) Text(stringResource(R.string.proof_checked_n, checked, found), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            if (s.kind != Sources.KIND_TEXT && Sources.hasPages(s)) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CompareArrows, null, Modifier.size(16.dp), tint = x.gold); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.scan_check) + ": " + stringResource(when (s.scanMode) {
                    com.werd.app.sources.TwoPass.TWO -> R.string.scan_two; com.werd.app.sources.TwoPass.DOUBTFUL -> R.string.scan_doubtful
                    else -> R.string.scan_single }) + (if (s.scanMode != com.werd.app.sources.TwoPass.SINGLE)
                        " · " + com.werd.app.sources.Readers.label(context, s.checkReader) else ""),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onMode) { Text(stringResource(R.string.change)) }
            }
            if (s.proofTotal > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FactCheck, null, Modifier.size(16.dp), tint = x.gold); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pw_running), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope { SourceWorker.cancelProof(context, s.id) } }) { Text(stringResource(R.string.cancel)) }
                }
                LinearProgressIndicator(progress = { s.proofDone.toFloat() / s.proofTotal },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
            if (s.proofNote.isNotBlank()) Text(stringResource(R.string.pw_stopped, s.proofNote.take(200)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            if (s.error.isNotBlank()) Text(s.error, style = MaterialTheme.typography.bodySmall,
                color = if (s.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemCard(it: SourceItem, names: Map<String, String>, checked: Boolean, expanded: Boolean, onExpand: () -> Unit,
                     onToggle: () -> Unit, onEdit: () -> Unit, onPage: () -> Unit, onOpenWerd: () -> Unit) {
    val x = LocalExtra.current
    // shown folded (title, count, page); a tap opens it in full — one at a time
    Surface(onClick = onExpand, shape = RoundedCornerShape(18.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (checked || expanded) 2.dp else 1.dp,
            if (checked) MaterialTheme.colorScheme.primary else if (expanded) x.gold.copy(alpha = 0.6f) else x.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = if (expanded) 10.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.titleSmall, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(names[it.occasion] ?: it.occasion, style = MaterialTheme.typography.labelSmall, color = x.gold)
                        if (it.textOnly) Text(stringResource(R.string.text_label), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("×${it.count}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.src_page, it.page), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onPage))
                        if (it.checked) Icon(Icons.Rounded.Spellcheck, stringResource(R.string.proof_mark), Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        if (it.flagged) Icon(Icons.Rounded.CompareArrows, stringResource(R.string.two_differ), Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (it.werdId != null) AssistChip(onClick = onOpenWerd, label = { Text(stringResource(R.string.src_added)) },
                    leadingIcon = { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp)) })
                if (expanded) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit), Modifier.size(18.dp)) }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    stringResource(if (expanded) R.string.collapse else R.string.expand), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.animation.AnimatedVisibility(expanded) {
                Column {
                    Text(it.text, style = TextStyle(fontFamily = Amiri, fontSize = 18.sp, lineHeight = 31.sp, textDirection = TextDirection.Content,
                        color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.padding(start = 12.dp))
                    if (it.occasionText.isNotBlank() || it.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(listOf(it.occasionText, it.note).filter { s -> s.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                    if (it.section.isNotBlank()) Text(stringResource(R.string.proof_section) + ": " + it.section, style = MaterialTheme.typography.labelSmall,
                        color = x.gold, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                    // footnotes stay folded (and are never read aloud)
                    if (it.footnote.isNotBlank()) {
                        var notes by remember(it.id) { mutableStateOf(false) }
                        Text((if (notes) "▾ " else "▸ ") + stringResource(R.string.proof_has_footnote), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, top = 4.dp).clickable { notes = !notes })
                        if (notes) Text(it.footnote, style = TextStyle(fontFamily = Amiri, fontSize = 15.sp, lineHeight = 26.sp, textDirection = TextDirection.Content,
                            color = MaterialTheme.colorScheme.onSurfaceVariant), modifier = Modifier.padding(start = 12.dp))
                    }
                    if (it.flagged) Text(stringResource(R.string.two_differ_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp, top = 4.dp).clickable(onClick = onPage))
                }
            }
        }
    }
}

/** The book's فهرس: each entry opens the supplication it names. */
@Composable
private fun IndexCard(index: List<com.werd.app.sources.BookIndex.Entry>, items: List<SourceItem>, open: Boolean,
                      onToggle: () -> Unit, onEdit: () -> Unit, onGo: (Long) -> Unit) {
    val x = LocalExtra.current
    val ids = remember(items) { items.map { it.id }.toSet() }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, x.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.List, null, tint = x.gold)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.index_title, index.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (open) TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, stringResource(if (open) R.string.collapse else R.string.expand))
            }
            androidx.compose.animation.AnimatedVisibility(open) {
                Column {
                    index.forEach { e ->
                        val linked = e.item != null && e.item in ids
                        Row(Modifier.fillMaxWidth().clickable(enabled = linked) { e.item?.let(onGo) }.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(e.title, fontFamily = Amiri, fontSize = 17.sp, modifier = Modifier.weight(1f),
                                style = TextStyle(textDirection = TextDirection.Content,
                                    textDecoration = if (linked) androidx.compose.ui.text.style.TextDecoration.Underline else null),
                                color = if (linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (e.page > 0) Text("${e.page}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Fix the index: rename an entry, change its page, link it to a supplication by hand, or remove it. */
@Composable
private fun IndexDialog(entries: List<com.werd.app.sources.BookIndex.Entry>, items: List<SourceItem>,
                        onDismiss: () -> Unit, onSave: (List<com.werd.app.sources.BookIndex.Entry>) -> Unit) {
    val list = remember { entries.toMutableStateList() }
    var picking by remember { mutableIntStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.index_edit)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(list) { i, e ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(e.title, { t -> list[i] = e.copy(title = t) }, singleLine = true, modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(textDirection = TextDirection.Content))
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(if (e.page > 0) "${e.page}" else "", { t -> list[i] = e.copy(page = t.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
                                    singleLine = true, modifier = Modifier.width(72.dp),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                IconButton(onClick = { list.removeAt(i) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                            }
                            TextButton(onClick = { picking = i }) {
                                Icon(Icons.Rounded.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                                Text(items.firstOrNull { it.id == e.item }?.title ?: stringResource(R.string.index_not_linked), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list.filter { it.title.isNotBlank() }) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (picking >= 0) AlertDialog(
        onDismissRequest = { picking = -1 },
        title = { Text(stringResource(R.string.index_link_to)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(items, key = { it.id }) { it ->
                    Row(Modifier.fillMaxWidth().clickable { list[picking] = list[picking].copy(item = it.id); picking = -1 }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.src_page, it.page), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { list[picking] = list[picking].copy(item = null); picking = -1 }) { Text(stringResource(R.string.index_unlink)) } },
    )
}

private fun headCount(index: Boolean, s: Source?, prep: Boolean, reading: Boolean) =
    (if (index) 1 else 0) + (if (reading) 1 else 0) + (if (s != null) 1 else 0) + (if (prep) 1 else 0) + 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDialog(app: WerdApp, picked: List<SourceItem>, names: Map<String, String>, autoGroupDefault: Boolean,
                      defaultTitle: String, onDismiss: () -> Unit, onDone: (List<Long>, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val groupsFlow = remember { app.db.dao().groupsFlow() }
    val groups by groupsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var combine by remember { mutableStateOf(picked.size > 1) }
    val dominant = SourceImport.dominantOccasion(picked)
    var title by remember { mutableStateOf(if (dominant != "general") names[dominant].orEmpty() else defaultTitle) }
    var auto by remember { mutableStateOf(autoGroupDefault) }
    var groupId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    // preview of where things will go
    val preview = remember(picked, combine, auto, groups) {
        val occ = if (combine) listOf(dominant) else picked.map { it.occasion }.distinct()
        occ.joinToString("، ") { o ->
            val g = if (o == "general") null else SourceImport.matchGroup(o, groups)
            g?.name ?: names[o]?.takeIf { o != "general" }?.let { "$it ✚" } ?: "—"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.src_add_n, picked.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picked.size > 1) {
                    Row(Modifier.fillMaxWidth().clickable { combine = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(combine, { combine = true }); Text(stringResource(R.string.src_combine))
                    }
                    Row(Modifier.fillMaxWidth().clickable { combine = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!combine, { combine = false }); Text(stringResource(R.string.src_separate, picked.size))
                    }
                }
                if (combine || picked.size == 1) OutlinedTextField(title, { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.werd_title)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.src_auto_group), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.src_auto_group_d), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(auto, { auto = it; app.settings.update { s -> s.copy(autoGroup = it) } })
                }
                if (auto) Text(stringResource(R.string.src_goes_to, preview), style = MaterialTheme.typography.bodySmall,
                    color = LocalExtra.current.gold)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(groupId == null, { groupId = null }, label = { Text(stringResource(R.string.group_other)) })
                    groups.forEach { g ->
                        FilterChip(groupId == g.id, { groupId = g.id }, label = { Text(g.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(WerdPalette[g.colorIndex % WerdPalette.size])) })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val ids = SourceImport.add(app.repo, app.db.dao(), picked,
                        SourceImport.Options(combine = combine || picked.size == 1, title = title, autoGroup = auto, groupId = groupId, occasionNames = names))
                    onDone(ids, combine || picked.size == 1)
                }
            }) { Text(stringResource(R.string.src_add_to_werds)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(item: SourceItem, names: Map<String, String>, onDismiss: () -> Unit, onSave: (SourceItem) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var text by remember { mutableStateOf(item.text) }
    var count by remember { mutableIntStateOf(item.count) }
    var occasion by remember { mutableStateOf(item.occasion) }
    var textOnly by remember { mutableStateOf(item.textOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.werd_title)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    textStyle = TextStyle(fontFamily = Amiri, fontSize = 17.sp, lineHeight = 29.sp, textDirection = TextDirection.Content))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_only), modifier = Modifier.weight(1f)); Switch(textOnly, { textOnly = it })
                }
                if (!textOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.repeat_times), modifier = Modifier.weight(1f)); CountStepper(count, { count = it })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiTasks.OCCASIONS.forEach { o -> FilterChip(occasion == o, { occasion = o }, label = { Text(names[o] ?: o) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(item.copy(title = title.trim(), text = text.trim(), count = count, occasion = occasion, textOnly = textOnly)) }) {
            Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
    )
}
