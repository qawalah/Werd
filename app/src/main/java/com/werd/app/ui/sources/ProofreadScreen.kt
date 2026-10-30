package com.werd.app.ui.sources

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ai.AiTasks
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.WerdDao
import com.werd.app.data.flagged
import com.werd.app.data.diffSet
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import com.werd.app.sources.PageSource
import com.werd.app.sources.Sources
import com.werd.app.ui.components.CountStepper
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which pages the proofreader steps through: pages with findings, or every page read so far. */
internal fun proofPages(items: List<SourceItem>, source: Source?, includeEmpty: Boolean): List<Int> {
    val withItems = items.map { it.page }.toSortedSet()
    if (!includeEmpty || source == null) return withItems.toList()
    val last = maxOf(source.pagesDone, withItems.lastOrNull() ?: 0).coerceAtMost(maxOf(source.pageCount, 1))
    return ((1..last) + withItems).toSortedSet().toList()
}

/** Next page (after [current], wrapping) that still has something unchecked; null when all are checked. */
internal fun nextUnchecked(items: List<SourceItem>, current: Int): Int? {
    // pages where the two readings of a two-pass scan disagree come first
    val flagged = items.filter { it.flagged }.map { it.page }.toSortedSet()
    if (flagged.isNotEmpty()) return flagged.firstOrNull { it > current } ?: flagged.firstOrNull()
    val pages = items.filter { !it.checked }.map { it.page }.toSortedSet()
    return pages.firstOrNull { it > current } ?: pages.firstOrNull()
}

/**
 * Proofread mode: the original page beside the text that was read from it.
 * Edits save as you type; ticking items (or "Page is correct") records what has been checked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProofreadScreen(app: WerdApp, sourceId: Long, startPage: Int, onBack: () -> Unit, onOpenWerd: (Long) -> Unit) {
    val dao = app.db.dao()
    val scope = rememberCoroutineScope()
    val sourceFlow = remember(sourceId) { dao.sourceFlow(sourceId) }
    val itemsFlow = remember(sourceId) { dao.sourceItemsFlow(sourceId) }
    val source by sourceFlow.collectAsStateWithLifecycle(initialValue = null)
    val itemsOrNull by itemsFlow.collectAsStateWithLifecycle(initialValue = null)
    val items = itemsOrNull.orEmpty()
    val names = occasionNames()
    val snackbar = remember { SnackbarHostState() }
    var includeEmpty by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(startPage) }
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    var split by remember { mutableFloatStateOf(settings.proofSplit) }
    // the item being edited: the original page follows it
    var focusId by rememberSaveable { mutableLongStateOf(-1L) }
    var refit by remember { mutableIntStateOf(0) }
    var rescanning by remember { mutableStateOf(false) }
    val rescanStartedMsg = stringResource(R.string.rescan_started)
    val deletedMsg = stringResource(R.string.proof_deleted)
    val undoLabel = stringResource(R.string.undo)
    val allCheckedMsg = stringResource(R.string.proof_all_checked)

    // First visit: open on the first page that still needs checking.
    LaunchedEffect(itemsOrNull != null) {
        if (itemsOrNull != null && page <= 0) page = nextUnchecked(items, 0) ?: items.firstOrNull()?.page ?: 1
    }
    val pages = proofPages(items, source, includeEmpty)
    val idx = pages.indexOf(page)
    val onPage = items.filter { it.page == page }.sortedBy { it.position }
    val focus = onPage.firstOrNull { it.id == focusId } ?: onPage.firstOrNull { it.flagged } ?: onPage.firstOrNull()
    val checkedCount = items.count { it.checked }

    fun go(p: Int?) { if (p != null) page = p }
    fun prev() = go(if (idx > 0) pages[idx - 1] else pages.lastOrNull { it < page })
    fun next() = go(if (idx >= 0 && idx < pages.lastIndex) pages[idx + 1] else pages.firstOrNull { it > page })

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.proof_title), maxLines = 1)
                        Text(source?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    val src = source
                    if (src != null && Sources.hasPages(src) && src.status == "DONE" && src.rescanTo == 0)
                        IconButton(onClick = { rescanning = true }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.rescan_page)) }
                    if (src?.kind != Sources.KIND_TEXT && src?.kind != Sources.KIND_IMPORTED) IconToggleButton(includeEmpty, { includeEmpty = it }) {
                        Icon(if (includeEmpty) Icons.Rounded.AutoStories else Icons.Rounded.FilterList,
                            stringResource(R.string.proof_show_empty))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            PageBar(page = page, total = source?.pageCount ?: 0, checked = checkedCount, all = items.size,
                hasPrev = pages.any { it < page }, hasNext = pages.any { it > page },
                onPrev = ::prev, onNext = ::next,
                onNextUnchecked = {
                    val n = nextUnchecked(items, page)
                    if (n == null) scope.launch { snackbar.showSnackbar(allCheckedMsg) } else page = n
                })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val s = source
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val wide = maxWidth > maxHeight
            val total = if (wide) maxWidth else maxHeight
            val original: @Composable (Modifier) -> Unit = { m ->
                Pane(m, stringResource(R.string.proof_original)) {
                    if (s != null) OriginalPage(s, page, focus?.region?.let(::band), refit)
                    if (focus?.region?.let(::band) != null) SmallFloatingActionButton(onClick = { refit++ },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Icon(Icons.Rounded.CenterFocusStrong, stringResource(R.string.proof_fit))
                    }
                }
            }
            val recognised: @Composable (Modifier) -> Unit = { m ->
                Pane(m, stringResource(R.string.proof_recognised)) {
                    RecognisedList(dao, onPage, names, onFocus = { focusId = it.id; refit++ },
                        onCheck = { it, c -> scope.launch { if (c && it.flagged) dao.resolveSourceItem(it.id) else dao.setSourceItemChecked(it.id, c) } },
                        onDelete = { it ->
                            scope.launch {
                                dao.deleteSourceItem(it.id)
                                if (snackbar.showSnackbar(deletedMsg, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                                    dao.insertSourceItemAt(it.copy(id = 0))
                            }
                        },
                        onAddMissed = {
                            scope.launch {
                                val pos = (onPage.maxOfOrNull { it.position } ?: items.lastOrNull { it.page < page }?.position ?: -1) + 1
                                dao.insertSourceItemAt(SourceItem(sourceId = sourceId, position = pos, page = page, title = "", text = "",
                                    occasion = onPage.firstOrNull()?.occasion ?: "general", checked = true))
                            }
                        },
                        onPageOk = {
                            scope.launch {
                                onPage.filter { !it.checked || it.flagged }.forEach { dao.resolveSourceItem(it.id) }
                                next()
                            }
                        },
                        onOpenWerd = onOpenWerd)
                }
            }
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            val handle = Modifier.pointerInput(wide, total, rtl) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val d = (if (wide) drag.x * (if (rtl) -1 else 1) else drag.y)
                    split = (split + d / total.toPx()).coerceIn(0.2f, 0.8f)
                }
            }
            LaunchedEffect(split) { delay(400); if (split != settings.proofSplit) app.settings.update { it.copy(proofSplit = split) } }
            // a shared book carries no pages: only the text to read through
            if (s != null && rescanning) RescanDialog(app, s, page, page, onDismiss = { rescanning = false },
                onStarted = { rescanning = false; scope.launch { snackbar.showSnackbar(rescanStartedMsg) } })
            if (s != null && !Sources.hasPages(s)) Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.proof_no_pages), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                recognised(Modifier.weight(1f).fillMaxWidth())
            } else if (wide) Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                original(Modifier.weight(split).fillMaxHeight())
                Box(handle.fillMaxHeight().width(16.dp), contentAlignment = Alignment.Center) { Grip(vertical = true) }
                recognised(Modifier.weight(1 - split).fillMaxHeight())
            } else Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                original(Modifier.weight(split).fillMaxWidth())
                Box(handle.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) { Grip(vertical = false) }
                recognised(Modifier.weight(1 - split).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Grip(vertical: Boolean) {
    Box(Modifier.then(if (vertical) Modifier.size(5.dp, 40.dp) else Modifier.size(40.dp, 5.dp))
        .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun Pane(modifier: Modifier, label: String, content: @Composable BoxScope.() -> Unit) {
    val x = LocalExtra.current
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder)) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            content()
            Text(label, style = MaterialTheme.typography.labelSmall, color = x.gold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun PageBar(page: Int, total: Int, checked: Int, all: Int, hasPrev: Boolean, hasNext: Boolean,
                    onPrev: () -> Unit, onNext: () -> Unit, onNextUnchecked: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (all > 0) LinearProgressIndicator(progress = { checked.toFloat() / all },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(4.dp).clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = hasPrev) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, stringResource(R.string.proof_prev)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.proof_page_of, page, total), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.proof_checked_n, checked, all), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onNext, enabled = hasNext) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, stringResource(R.string.proof_next)) }
                TextButton(onClick = onNextUnchecked) { Text(stringResource(R.string.proof_next_unchecked)) }
            }
        }
    }
}

/** The page as printed (pinch / double-tap to zoom, drag to pan), or the text chunk for text sources. */
/** "0.31-0.52" → the item's band on its page. */
internal fun band(region: String): ClosedFloatingPointRange<Float>? {
    val p = region.split('-').mapNotNull { it.toFloatOrNull() }
    return if (p.size == 2 && p[1] > p[0]) p[0]..p[1] else null
}

@Composable
private fun BoxScope.OriginalPage(source: Source, page: Int, focus: ClosedFloatingPointRange<Float>? = null, refit: Int = 0) {
    var bmp by remember(source.id, page) { mutableStateOf<Bitmap?>(null) }
    var text by remember(source.id, page) { mutableStateOf<String?>(null) }
    var failed by remember(source.id, page) { mutableStateOf(false) }
    LaunchedEffect(source.id, page) {
        withContext(Dispatchers.IO) {
            runCatching {
                PageSource(source).use { ps ->
                    if (source.kind == Sources.KIND_TEXT) text = ps.textChunk(page - 1) else bmp = ps.bitmap(page - 1, 2200)
                }
            }.onFailure { failed = true }
        }
    }
    when {
        text != null -> SelectionContainer {
            Text(text!!, style = TextStyle(fontFamily = Amiri, fontSize = 18.sp, lineHeight = 31.sp, textDirection = TextDirection.Content,
                color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp))
        }
        bmp != null -> ZoomableImage(bmp!!, key = page, focus = focus, refit = refit)
        failed -> Icon(Icons.Rounded.BrokenImage, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.outline)
        else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ZoomableImage(bmp: Bitmap, key: Any, focus: ClosedFloatingPointRange<Float>? = null, refit: Int = 0) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val image = remember(bmp) { bmp.asImageBitmap() }
    fun clamp(o: Offset, s: Float): Offset {
        val w = box.width * s
        val h = box.width * (bmp.height.toFloat() / bmp.width) * s
        return Offset(o.x.coerceIn(minOf(0f, box.width - w), 0f), o.y.coerceIn(minOf(0f, box.height - h), 0f))
    }
    // follow the item being edited: fitted to the width, its lines at the top of the pane
    val anim = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(focus, refit, box) {
        if (focus == null || box.width == 0) return@LaunchedEffect
        val imgH = box.width * (bmp.height.toFloat() / bmp.width)
        // fitted to the width: the whole line is seen, right to left (only pinching zooms further)
        val s = 1f
        val target = clamp(Offset(0f, -(focus.start * imgH * s) + box.height * 0.06f), s)
        val from = offset; val fromS = scale
        anim.snapTo(0f)
        anim.animateTo(1f, androidx.compose.animation.core.tween(350)) {
            scale = fromS + (s - fromS) * value
            offset = Offset(from.x + (target.x - from.x) * value, from.y + (target.y - from.y) * value)
        }
    }
    val bandColor = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }
        .pointerInput(key) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val s = (scale * zoom).coerceIn(1f, 6f)
                val k = s / scale
                offset = clamp(centroid - (centroid - offset) * k + pan, s)
                scale = s
            }
        }
        .pointerInput(key) {
            detectTapGestures(onDoubleTap = { p ->
                if (scale > 1.05f) { scale = 1f; offset = clamp(Offset(0f, offset.y), 1f) }
                else { val s = 2.5f; offset = clamp(p - (p - offset) * s, s); scale = s }
            })
        }) {
        Image(image, null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
                .drawWithContent {
                    drawContent()
                    // the item's lines, marked on the page
                    if (focus != null) {
                        val y0 = focus.start * size.height; val y1 = focus.endInclusive * size.height
                        drawRect(bandColor.copy(alpha = 0.06f), topLeft = Offset(0f, y0), size = androidx.compose.ui.geometry.Size(size.width, y1 - y0))
                        drawRect(bandColor.copy(alpha = 0.85f), topLeft = Offset(size.width - 4.dp.toPx(), y0), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), y1 - y0))
                    }
                })
    }
}

@Composable
private fun RecognisedList(dao: WerdDao, onPage: List<SourceItem>, names: Map<String, String>, onFocus: (SourceItem) -> Unit,
                           onCheck: (SourceItem, Boolean) -> Unit, onDelete: (SourceItem) -> Unit,
                           onAddMissed: () -> Unit, onPageOk: () -> Unit, onOpenWerd: (Long) -> Unit) {
    val list = rememberLazyListState()
    val page = onPage.firstOrNull()?.page
    LaunchedEffect(page) { list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(10.dp, 30.dp, 10.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onPage.isEmpty()) item {
            Text(stringResource(R.string.proof_none_on_page), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(onPage, key = { it.id }) { item ->
            ProofItem(dao, item, names, onFocus = { onFocus(item) }, onCheck = { onCheck(item, it) }, onDelete = { onDelete(item) },
                onOpenWerd = { item.werdId?.let(onOpenWerd) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAddMissed, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.proof_add_missed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onPage.isNotEmpty()) Button(onClick = onPageOk, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.DoneAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.proof_page_ok), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * One extracted supplication, edited in place (changes save after a short pause, and when leaving the page).
 * Words other readings or the helpers question are marked in the text; tapping one (or the arrows) opens a small list
 * of what the others read there, each with who read it. The full other readings are in a sheet, opened on demand.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProofItem(dao: WerdDao, item: SourceItem, names: Map<String, String>, onFocus: () -> Unit,
                     onCheck: (Boolean) -> Unit, onDelete: () -> Unit, onOpenWerd: () -> Unit) {
    val x = LocalExtra.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember(item.id) { mutableStateOf(item.title) }
    var field by remember(item.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(item.text)) }
    val text = field.text
    var count by remember(item.id) { mutableIntStateOf(item.count) }
    var occasion by remember(item.id) { mutableStateOf(item.occasion) }
    var textOnly by remember(item.id) { mutableStateOf(item.textOnly) }
    var section by remember(item.id) { mutableStateOf(item.section) }
    var footnote by remember(item.id) { mutableStateOf(item.footnote) }
    var more by remember(item.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var occMenu by remember { mutableStateOf(false) }
    var readingsSheet by remember { mutableStateOf(false) }
    // words dealt with here ("keep mine"), and the word whose alternatives are open
    var handled by remember(item.id) { mutableStateOf(emptySet<Int>()) }
    var open by remember(item.id) { mutableStateOf<Int?>(null) }
    val latest by rememberUpdatedState(item)
    val scope = rememberCoroutineScope()
    fun edited() = latest.copy(title = title.trim(), text = text.trim(), count = count, occasion = occasion, textOnly = textOnly,
        section = section.trim(), footnote = footnote.trim())
    fun dirty() = edited().let { it.title != latest.title || it.text != latest.text || it.count != latest.count || it.occasion != latest.occasion ||
        it.textOnly != latest.textOnly || it.section != latest.section || it.footnote != latest.footnote }
    LaunchedEffect(title, text, count, occasion, textOnly, section, footnote) {
        delay(600)
        if (dirty()) dao.updateSourceItem(edited())
    }
    DisposableEffect(item.id) {
        onDispose { if (dirty()) { val e = edited(); CoroutineScope(Dispatchers.IO + NonCancellable).launch { dao.updateSourceItem(e) } } }
    }
    // the questioned words, as the text now reads (a word changed here is compared again)
    val questioned = remember(text, item.reads, item.hints, item.alt, item.diffWords) {
        if (!item.flagged) emptyList()
        else com.werd.app.sources.Proof.settle(item.copy(text = text, checked = false)).diffSet.sorted()
    }
    val todo = questioned.filter { it !in handled }
    LaunchedEffect(todo.isEmpty(), item.flagged) {
        // every questioned word dealt with: the item is proofread
        if (item.flagged && todo.isEmpty() && questioned.isNotEmpty()) { dao.updateSourceItem(edited()); dao.resolveSourceItem(item.id) }
    }
    val err = MaterialTheme.colorScheme.error
    val gold = x.gold
    // marks the questioned words inside the editing box
    val marks = remember(questioned, handled, err) {
        androidx.compose.ui.text.input.VisualTransformation { t ->
            val b = androidx.compose.ui.text.AnnotatedString.Builder(t.text)
            var k = -1
            Regex("\\S+").findAll(t.text).forEach { m ->
                if (m.value.any { it.isLetter() }) {
                    k++
                    if (k in questioned) b.addStyle(androidx.compose.ui.text.SpanStyle(
                        color = if (k in handled) gold else err, fontWeight = FontWeight.Bold,
                        background = (if (k in handled) gold else err).copy(alpha = 0.10f),
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), m.range.first, m.range.last + 1)
                }
            }
            androidx.compose.ui.text.input.TransformedText(b.toAnnotatedString(), androidx.compose.ui.text.input.OffsetMapping.Identity)
        }
    }
    fun wordAt(offset: Int): Int? {
        var k = -1
        Regex("\\S+").findAll(text).forEach { m -> if (m.value.any { it.isLetter() }) { k++; if (offset in m.range.first..m.range.last + 1) return k } }
        return null
    }
    fun select(word: Int) {
        var k = -1
        Regex("\\S+").findAll(text).forEach { m -> if (m.value.any { it.isLetter() }) { k++; if (k == word) field = field.copy(selection = androidx.compose.ui.text.TextRange(m.range.first, m.range.last + 1)) } }
        open = word
    }

    Surface(shape = RoundedCornerShape(14.dp),
        color = if (item.checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, when { item.checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f); item.flagged -> err.copy(alpha = 0.45f); else -> x.cardBorder })) {
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTitle(title, { title = it }, Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocus() })
                IconToggleButton(item.checked, onCheck) {
                    Icon(if (item.checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        stringResource(if (item.checked) R.string.proof_unmark else R.string.proof_mark),
                        tint = if (item.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (item.werdId != null) DropdownMenuItem(text = { Text(stringResource(R.string.src_open)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = { menu = false; onOpenWerd() })
                        if (com.werd.app.sources.Proof.reads(item).any { !it.agrees }) DropdownMenuItem(text = { Text(stringResource(R.string.proof_readings)) },
                            leadingIcon = { Icon(Icons.Rounded.CompareArrows, null) }, onClick = { menu = false; readingsSheet = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            // a slim strip: how many words to check, and the arrows between them
            if (questioned.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) {
                Icon(Icons.Rounded.CompareArrows, null, Modifier.size(16.dp), tint = if (todo.isEmpty()) gold else err)
                Spacer(Modifier.width(6.dp))
                Text(if (todo.isEmpty()) stringResource(R.string.proof_words_done) else stringResource(R.string.proof_words_n, todo.size),
                    style = MaterialTheme.typography.labelLarge, color = if (todo.isEmpty()) gold else err, modifier = Modifier.weight(1f))
                IconButton(onClick = { val cur = open ?: -1; (todo.lastOrNull { it < cur } ?: todo.lastOrNull())?.let(::select); onFocus() }, enabled = todo.isNotEmpty(),
                    modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, stringResource(R.string.proof_prev_word)) }
                IconButton(onClick = { val cur = open ?: -1; (todo.firstOrNull { it > cur } ?: todo.firstOrNull())?.let(::select); onFocus() }, enabled = todo.isNotEmpty(),
                    modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, stringResource(R.string.proof_next_word)) }
            }
            Box {
                OutlinedTextField(field, { v ->
                    val tapped = v.text == field.text && v.selection.collapsed && v.selection != field.selection
                    field = v
                    if (tapped) wordAt(v.selection.start)?.takeIf { it in questioned }?.let { open = it }
                }, modifier = Modifier.fillMaxWidth().padding(end = 6.dp).onFocusChanged { if (it.isFocused) onFocus() }, minLines = 2,
                    visualTransformation = marks,
                    textStyle = TextStyle(fontFamily = Amiri, fontSize = 19.sp, lineHeight = 33.sp, textDirection = TextDirection.Content,
                        color = MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp))
                // what the other readers and the helpers have for the word
                val w = open
                if (w != null) {
                    val offers = remember(w, text, item.reads, item.hints) { com.werd.app.sources.Proof.offers(edited(), w) }
                    DropdownMenu(true, { open = null }) {
                        Text(stringResource(R.string.proof_word_q, com.werd.app.sources.TwoPass.words(text).getOrElse(w) { "" }),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        offers.forEach { (word, by) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(word, style = TextStyle(fontFamily = Amiri, fontSize = 20.sp, textDirection = TextDirection.Content))
                                        Text(byLabel(context, by), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    val nt = com.werd.app.sources.Proof.replaceWord(text, w, word)
                                    field = androidx.compose.ui.text.input.TextFieldValue(nt)
                                    handled = handled + w; open = null
                                })
                        }
                        if (offers.isEmpty()) Text(stringResource(R.string.proof_no_offers), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.proof_keep_mine)) }, leadingIcon = { Icon(Icons.Rounded.Check, null) },
                            onClick = { handled = handled + w; open = null; todo.firstOrNull { it > w && it !in handled }?.let(::select) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, end = 6.dp)) {
                FilterChip(textOnly, { textOnly = !textOnly }, label = { Text(stringResource(R.string.text_label)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(16.dp)) })
                Spacer(Modifier.width(6.dp))
                if (!textOnly) CountStepper(count, { count = it })
                Spacer(Modifier.weight(1f))
                Box {
                    AssistChip(onClick = { occMenu = true }, label = { Text(names[occasion] ?: occasion) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) })
                    DropdownMenu(occMenu, { occMenu = false }) {
                        AiTasks.OCCASIONS.forEach { o ->
                            DropdownMenuItem(text = { Text(names[o] ?: o, fontWeight = if (o == occasion) FontWeight.Bold else null) },
                                onClick = { occasion = o; occMenu = false })
                        }
                    }
                }
            }
            // the chapter and footnotes, folded
            TextButton(onClick = { more = !more }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(listOfNotNull(stringResource(R.string.proof_section_footnote), section.takeIf { it.isNotBlank() }?.let { "· $it" },
                    if (footnote.isNotBlank()) "· " + stringResource(R.string.proof_has_footnote) else null).joinToString(" "),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (more) Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(section, { section = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.proof_section)) })
                OutlinedTextField(footnote, { footnote = it.take(1000) }, modifier = Modifier.fillMaxWidth(), minLines = 1,
                    label = { Text(stringResource(R.string.proof_footnote)) },
                    textStyle = TextStyle(fontFamily = Amiri, fontSize = 16.sp, textDirection = TextDirection.Content))
            }
            if (item.werdId != null) Text(stringResource(R.string.proof_added_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, end = 6.dp))
        }
    }
    if (readingsSheet) ModalBottomSheet(onDismissRequest = { readingsSheet = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.proof_readings), style = MaterialTheme.typography.titleMedium)
            com.werd.app.sources.Proof.reads(item).forEach { r ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(byLabel(context, r.by), style = MaterialTheme.typography.labelLarge, color = gold)
                        if (r.agrees) Text(stringResource(R.string.proof_reading_agrees), style = MaterialTheme.typography.bodySmall)
                        else {
                            Text(r.text, style = TextStyle(fontFamily = Amiri, fontSize = 18.sp, lineHeight = 31.sp, textDirection = TextDirection.Content,
                                color = MaterialTheme.colorScheme.onSurface))
                            OutlinedButton(onClick = {
                                field = androidx.compose.ui.text.input.TextFieldValue(r.text); readingsSheet = false
                                scope.launch { dao.updateSourceItem(edited().copy(text = r.text)); dao.resolveSourceItem(item.id) }
                            }) { Text(stringResource(R.string.two_use_other)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Who a reading or suggestion came from, in words. */
internal fun byLabel(context: android.content.Context, by: String): String = when {
    by.startsWith("quran:") -> by.split(':').let { context.getString(R.string.hint_quran, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
    by == "spelling" -> context.getString(R.string.hint_spelling)
    by.startsWith("model:") -> context.getString(R.string.hint_model, by.removePrefix("model:"))
    by.endsWith("@hd") -> com.werd.app.sources.Readers.label(context, by.removeSuffix("@hd")) + " · " + context.getString(R.string.hint_hd)
    else -> com.werd.app.sources.Readers.label(context, by)
}

@Composable
private fun BasicTitle(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    TextField(value, onChange, modifier = modifier, singleLine = true,
        placeholder = { Text(stringResource(R.string.werd_title)) },
        textStyle = MaterialTheme.typography.titleSmall,
        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
}
