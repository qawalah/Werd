package com.werd.app.ui.reader

import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.WerdItem
import com.werd.app.data.WerdUi
import com.werd.app.ui.home.MenuItem as MenuItemCompat
import com.werd.app.tts.TtsController
import com.werd.app.ui.KeyBus
import com.werd.app.ui.components.OrnamentDivider
import com.werd.app.ui.components.ProgressRing
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.ui.theme.WerdPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.6f, 0.75f, 0.9f, 1.1f, 1.3f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(app: WerdApp, werdId: Long, onBack: () -> Unit, onEdit: () -> Unit, onReminders: () -> Unit) {
    val dash by app.repo.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val w = dash?.werds?.firstOrNull { it.werd.id == werdId }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var celebrate by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var focusIndex by remember { mutableIntStateOf(-1) }
    var resumeFrom by remember { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(false) }

    val latest by rememberUpdatedState(w)
    val latestSettings by rememberUpdatedState(settings)

    // ---------------------------------------------------------------- counting
    fun haptic(strong: Boolean) {
        if (!latestSettings.haptics) return
        val c = if (strong) (if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        else HapticFeedbackConstants.KEYBOARD_TAP
        view.performHapticFeedback(c)
    }

    fun count(item: WerdItem, delta: Int, fromTts: Boolean = false) {
        scope.launch {
            val r = app.repo.increment(werdId, item.id, delta)
            if (!r.changed) return@launch
            haptic(r.itemDone && delta > 0)
            if (r.justCompleted) celebrate = true
            val cur = latest ?: return@launch
            if (delta > 0 && r.itemDone && latestSettings.autoAdvance && !fromTts) {
                val idx = cur.items.indexOfFirst { it.id == item.id }
                val next = cur.items.withIndex().firstOrNull { (i, it) ->
                    i > idx && (cur.counts[it.id] ?: 0) < it.target
                }?.index
                if (next != null) {
                    if (latestSettings.focusMode) { delay(350); focusIndex = next }
                    else listState.animateScrollToItem(next + 1)
                }
            }
        }
    }

    // ---------------------------------------------------------------- text to speech
    // reading aloud lives outside this screen (it goes on with the screen off); this screen shows and steers it
    val tts = remember(settings.ttsEngine) { com.werd.app.tts.ReadAloud.controller(app) }
    val session by com.werd.app.tts.ReadAloud.session.collectAsState()
    val mine = session?.werdId == werdId
    LaunchedEffect(tts, settings.ttsVoice) { tts.preferredVoice = settings.ttsVoice }
    val voicePrefs by app.voice.flow.collectAsStateWithLifecycle()
    LaunchedEffect(tts, voicePrefs) { tts.clips = com.werd.app.voice.ClipVoice.current(app) }
    val rawState by tts.state.collectAsState()
    // another werd's reading is not shown here
    val ttsState = if (mine) rawState else com.werd.app.tts.TtsController.State(ready = rawState.ready, arabicAvailable = rawState.arabicAvailable)
    DisposableEffect(werdId) {
        com.werd.app.tts.ReadAloud.onCompleted = { id -> if (id == werdId) celebrate = true }
        onDispose { if (com.werd.app.tts.ReadAloud.onCompleted != null) com.werd.app.tts.ReadAloud.onCompleted = null }
    }
    val voiceFailed = stringResource(R.string.voice_failed)
    LaunchedEffect(ttsState.error) {
        if (ttsState.error.isNotEmpty()) {
            snackbar.showSnackbar(voiceFailed + " " + ttsState.error.take(160), withDismissAction = true)
            tts.clearError()
        }
    }
    // "prepare audio": make every line's clip now (e.g. before going offline)
    var preparing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val preparedMsg = stringResource(R.string.voice_prepared)
    LaunchedEffect(tts, settings.ttsRate, settings.ttsPitch) { tts.setVoice(settings.ttsRate, settings.ttsPitch) }
    LaunchedEffect(ttsState.index, mine) {
        val i = ttsState.index
        if (mine && i >= 0) {
            resumeFrom = i
            if (settings.focusMode) focusIndex = i else listState.animateScrollToItem(i + 1)
        }
    }
    LaunchedEffect(mine) { if (mine) resumeFrom = com.werd.app.tts.ReadAloud.resumeFrom }
    // which natural voices already have this werd's lines on the phone
    var ready by remember { mutableStateOf<List<com.werd.app.voice.VoiceLibrary.Ready>>(emptyList()) }
    var chooseVoice by remember { mutableStateOf(false) }
    val itemTexts = w?.items?.map { it.text }
    LaunchedEffect(itemTexts, voicePrefs, preparing, ttsState.index) {
        if (itemTexts != null) ready = com.werd.app.voice.VoiceLibrary.ready(app, itemTexts)
    }
    val noVoice = stringResource(R.string.tts_no_arabic)
    val install = stringResource(R.string.install)
    fun toggleTts() {
        if (ttsState.speaking) { com.werd.app.tts.ReadAloud.pause(); return }
        if (tts.clips == null && ttsState.ready && !ttsState.arabicAvailable) {
            scope.launch {
                val res = snackbar.showSnackbar(noVoice, actionLabel = install, withDismissAction = true)
                if (res == SnackbarResult.ActionPerformed) runCatching {
                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        val cur = latest ?: return
        val start = when {
            settings.focusMode && focusIndex >= 0 -> focusIndex
            resumeFrom in cur.items.indices -> resumeFrom
            else -> cur.currentIndex
        }
        com.werd.app.tts.ReadAloud.start(app, werdId, start)
    }

    // ---------------------------------------------------------------- screen on & volume keys
    DisposableEffect(settings.keepScreenOn) {
        val win = (context as? Activity)?.window
        if (settings.keepScreenOn) win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(Unit) {
        KeyBus.readerActive = true
        onDispose { KeyBus.readerActive = false }
    }
    LaunchedEffect(Unit) {
        KeyBus.events.collect { d ->
            val cur = latest ?: return@collect
            if (cur.werd.textOnly) return@collect
            val idx = if (latestSettings.focusMode && focusIndex >= 0) focusIndex else cur.currentIndex
            cur.items.getOrNull(idx)?.let { count(it, d) }
        }
    }
    LaunchedEffect(w?.werd?.id) {
        val cur = w ?: return@LaunchedEffect
        if (focusIndex < 0) focusIndex = cur.currentIndex
        if (cur.currentIndex > 0 && !cur.completedToday) listState.scrollToItem(cur.currentIndex + 1)
    }

    val x = LocalExtra.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(w?.werd?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (w?.werd?.textOnly != true) IconButton(onClick = { app.settings.update { it.copy(focusMode = !it.focusMode) } }) {
                        Icon(if (settings.focusMode) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.CenterFocusStrong,
                            stringResource(if (settings.focusMode) R.string.list_mode else R.string.focus_mode))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItemCompat(R.string.text_bigger, Icons.Rounded.TextIncrease) {
                                app.settings.update { it.copy(fontScale = (it.fontScale + 0.1f).coerceAtMost(2.2f)) }
                            }
                            MenuItemCompat(R.string.text_smaller, Icons.Rounded.TextDecrease) {
                                app.settings.update { it.copy(fontScale = (it.fontScale - 0.1f).coerceAtLeast(0.7f)) }
                            }
                            if (voicePrefs.isClip) MenuItemCompat(R.string.voice_prepare, Icons.Rounded.Download) {
                                menu = false
                                val c = com.werd.app.voice.ClipVoice.current(app)
                                val lines = w?.items?.map { it.text }?.filter { it.isNotBlank() }?.distinct().orEmpty()
                                if (c != null && preparing == null) scope.launch {
                                    var failed: Throwable? = null
                                    for ((i, t) in lines.withIndex()) {
                                        preparing = i to lines.size
                                        runCatching { c.file(t) }.onFailure { failed = it }
                                        if (failed != null) break
                                    }
                                    preparing = null
                                    snackbar.showSnackbar(failed?.let { voiceFailed + " " + (it.message ?: "").take(160) } ?: preparedMsg)
                                }
                            }
                            MenuItemCompat(R.string.reminders, Icons.Rounded.NotificationAdd) { menu = false; onReminders() }
                            MenuItemCompat(R.string.wlib_share, Icons.Rounded.Share) { menu = false; sharing = true }
                            MenuItemCompat(R.string.edit, Icons.Rounded.Edit) { menu = false; onEdit() }
                            MenuItemCompat(if (w?.werd?.textOnly == true) R.string.mark_read else R.string.mark_complete, Icons.Rounded.DoneAll) {
                                menu = false; scope.launch { app.repo.setAll(werdId, true); celebrate = true }
                            }
                            MenuItemCompat(R.string.reset_today, Icons.Rounded.Refresh) {
                                menu = false; if (mine) com.werd.app.tts.ReadAloud.finish(); resumeFrom = 0
                                scope.launch { app.repo.setAll(werdId, false); focusIndex = 0; listState.scrollToItem(0) }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (w != null) TtsBar(
                speaking = ttsState.speaking,
                lineLabel = when {
                    preparing != null -> stringResource(R.string.voice_preparing_n, preparing!!.first + 1, preparing!!.second)
                    ttsState.preparing -> stringResource(R.string.voice_preparing)
                    ttsState.speaking && ttsState.index >= 0 -> {
                        // the repetition being said, in the werd's own count when counting along
                        val item = w.items.getOrNull(ttsState.index)
                        if (item != null && settings.ttsAutoCount)
                            stringResource(R.string.rep_now, ((w.counts[item.id] ?: 0) + 1).coerceAtMost(item.target))
                        else stringResource(R.string.rep_of, ttsState.rep, ttsState.reps)
                    }
                    mine && resumeFrom > 0 -> stringResource(R.string.ra_resume_at, resumeFrom + 1)
                    else -> stringResource(R.string.listen)
                },
                hint = if (ttsState.speaking && ttsState.index >= 0) stringResource(R.string.reading_line, ttsState.index + 1, w.items.size) + " · " +
                    stringResource(if (settings.ttsAutoCount) R.string.count_follows else R.string.listen_only)
                    else stringResource(R.string.tts_hint),
                ready = ready,
                onVoices = { chooseVoice = true },
                autoCount = settings.ttsAutoCount, showAutoCount = !w.werd.textOnly,
                speed = settings.ttsRate,
                onToggle = ::toggleTts,
                onAutoCount = {
                    val v = !settings.ttsAutoCount
                    app.settings.update { it.copy(ttsAutoCount = v) }
                    com.werd.app.tts.ReadAloud.setAutoCount(v)
                },
                onSpeed = {
                    val i = SPEEDS.indexOfFirst { it >= settings.ttsRate - 0.01f }.let { if (it < 0) 0 else it }
                    val n = SPEEDS[(i + 1) % SPEEDS.size]
                    app.settings.update { it.copy(ttsRate = n) }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (w == null) {
            Box(Modifier.fillMaxSize().padding(pad))
            return@Scaffold
        }
        if (w.werd.textOnly) {
            // a text to read: the words, read aloud with the moving marker, and "mark as read" — no counter
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { TextHeader(w) }
                itemsIndexed(w.items, key = { _, it -> it.id }) { i, item ->
                    val speaking = ttsState.speaking && ttsState.index == i
                    Surface(shape = RoundedCornerShape(22.dp),
                        color = if (speaking) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(if (speaking) 2.dp else 1.dp, if (speaking) MaterialTheme.colorScheme.primary else x.cardBorder)) {
                        Text(
                            highlighted(item.text, if (speaking) ttsState.wordStart else -1, if (speaking) ttsState.wordEnd else -1, x.bead.copy(alpha = 0.30f)),
                            style = TextStyle(fontFamily = Amiri, fontSize = (21 * settings.fontScale).sp, lineHeight = (38 * settings.fontScale).sp,
                                textDirection = TextDirection.Content, textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    if (w.completedToday) OutlinedButton(onClick = { scope.launch { app.repo.setAll(werdId, false) } }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = x.onDone); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.read_today))
                    } else Button(onClick = { scope.launch { app.repo.setAll(werdId, true) } }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Rounded.DoneAll, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mark_read))
                    }
                    Spacer(Modifier.height(8.dp))
                    OrnamentDivider(Modifier.padding(horizontal = 40.dp))
                }
            }
        } else if (settings.focusMode) {
            val idx = focusIndex.coerceIn(0, (w.items.size - 1).coerceAtLeast(0))
            FocusView(
                w = w, index = idx, fontScale = settings.fontScale,
                speaking = ttsState.speaking && ttsState.index == idx,
                wordStart = ttsState.wordStart, wordEnd = ttsState.wordEnd,
                modifier = Modifier.padding(pad),
                onIndex = { focusIndex = it.coerceIn(0, w.items.size - 1) },
                onCount = { item, d -> count(item, d) },
                onFont = { d -> app.settings.update { it.copy(fontScale = (it.fontScale + d).coerceIn(0.7f, 2.2f)) } },
                counterStyle = settings.counterStyle,
                onStyle = { app.settings.update { it.copy(counterStyle = if (it.counterStyle == "pull") "ring" else "pull") } },
                counterSize = settings.counterSize, beads = settings.counterBeads,
                onSize = { app.settings.update { it.copy(counterSize = (it.counterSize + 1) % 4) } },
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { ProgressHeader(w) }
                itemsIndexed(w.items, key = { _, it -> it.id }) { i, item ->
                    val c = w.counts[item.id] ?: 0
                    val speaking = ttsState.speaking && ttsState.index == i
                    LineCard(
                        index = i, item = item, count = c, fontScale = settings.fontScale,
                        speaking = speaking,
                        wordStart = if (speaking) ttsState.wordStart else -1,
                        wordEnd = if (speaking) ttsState.wordEnd else -1,
                        tapToCount = settings.tapCardToCount,
                        accent = WerdPalette[w.werd.colorIndex % WerdPalette.size],
                        onInc = { count(item, 1) },
                        onDec = { count(item, -1) },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    OrnamentDivider(Modifier.padding(horizontal = 40.dp))
                }
            }
        }
    }

    if (chooseVoice) VoiceChooser(
        ready = ready, usingPhone = !voicePrefs.isClip,
        onPick = { r ->
            chooseVoice = false
            if (r == null) app.voice.update { it.copy(engine = com.werd.app.voice.VoicePrefs.SYSTEM) } else com.werd.app.voice.VoiceLibrary.use(app, r)
            if (ttsState.speaking) scope.launch { com.werd.app.tts.ReadAloud.pause(); kotlinx.coroutines.delay(150); toggleTts() }
        },
        onDismiss = { chooseVoice = false },
    )

    if (sharing) {
        val sharedMsg = stringResource(R.string.wlib_shared)
        com.werd.app.ui.sources.ShareWerdDialog(app, werdId, onDismiss = { sharing = false },
            onShared = { sharing = false; scope.launch { snackbar.showSnackbar(sharedMsg) } })
    }
    if (celebrate && w != null && w.werd.textOnly) {
        celebrate = false   // a text read to the end is just marked as read
        LaunchedEffect(Unit) { snackbar.showSnackbar(context.getString(R.string.read_today)) }
    }
    if (celebrate && w != null) {
        AlertDialog(
            onDismissRequest = { celebrate = false },
            icon = { com.werd.app.ui.components.BeadString(total = 7, done = 7, bead = 12.dp, gap = 6.dp) },
            title = { Text(stringResource(R.string.werd_complete), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.taqabbal), fontFamily = Amiri, fontSize = 22.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    val streak = (w.streak).coerceAtLeast(1)
                    Text(stringResource(R.string.streak_now, streak), textAlign = TextAlign.Center)
                    Text(stringResource(R.string.recitations_today, w.total), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            },
            confirmButton = { Button(onClick = { celebrate = false; if (mine) com.werd.app.tts.ReadAloud.finish(); onBack() }) { Text(stringResource(R.string.done)) } },
            dismissButton = { TextButton(onClick = { celebrate = false }) { Text(stringResource(R.string.stay)) } },
        )
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun TextHeader(w: WerdUi) {
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, x.cardBorder)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = x.gold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.text_to_read), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.text_to_read_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (w.completedToday) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.read_today), tint = x.onDone)
        }
    }
}

@Composable
private fun ProgressHeader(w: WerdUi) {
    val x = LocalExtra.current
    val linesDone = w.items.count { (w.counts[it.id] ?: 0) >= it.target }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lines_done, linesDone, w.items.size), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.recited_of, w.done, w.total), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                // one bead per line: the werd as a string
                com.werd.app.ui.components.BeadString(total = w.items.size, done = linesDone, max = 26,
                    doneSet = w.items.withIndex().filter { (_, it) -> (w.counts[it.id] ?: 0) >= it.target }.map { it.index }.toSet(),
                    current = w.items.indexOfFirst { (w.counts[it.id] ?: 0) < it.target })
            }
            if (w.streak > 0) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = x.gold, modifier = Modifier.size(20.dp))
                    Text("${w.streak}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun highlighted(text: String, start: Int, end: Int, color: Color): AnnotatedString = buildAnnotatedString {
    if (start < 0 || end <= start || end > text.length) { append(text); return@buildAnnotatedString }
    append(text.substring(0, start))
    withStyle(SpanStyle(color = color.copy(alpha = 1f), fontWeight = FontWeight.Bold,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append(text.substring(start, end)) }
    append(text.substring(end))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineCard(
    index: Int, item: WerdItem, count: Int, fontScale: Float, speaking: Boolean,
    wordStart: Int, wordEnd: Int, tapToCount: Boolean, accent: Color,
    onInc: () -> Unit, onDec: () -> Unit,
) {
    val x = LocalExtra.current
    val done = count >= item.target
    val bg by animateColorAsState(
        when {
            done -> x.done
            speaking -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceContainerLowest
        }, label = "bg",
    )
    var pulse by remember { mutableIntStateOf(0) }
    val s by animateFloatAsState(if (pulse % 2 == 1) 1.12f else 1f, spring(dampingRatio = 0.4f, stiffness = 900f), label = "pulse",
        finishedListener = { if (pulse % 2 == 1) pulse++ })
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = bg,
        border = BorderStroke(if (speaking) 2.dp else 1.dp, if (speaking) MaterialTheme.colorScheme.primary else if (done) accent.copy(alpha = 0.25f) else x.cardBorder),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).combinedClickable(
            enabled = true,
            onClick = { if (tapToCount && !done) { pulse++; onInc() } },
            onLongClick = { onDec() },
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (done) Icon(Icons.Rounded.CheckCircle, null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                highlighted(item.text, wordStart, wordEnd, x.bead.copy(alpha = 0.30f)),
                style = TextStyle(
                    fontFamily = Amiri, fontSize = (21 * fontScale).sp, lineHeight = (38 * fontScale).sp,
                    textDirection = TextDirection.Content, textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (done) 0.62f else 1f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (item.target in 2..33) com.werd.app.ui.components.BeadString(total = item.target, done = count.coerceAtMost(item.target),
                        current = if (speaking && !done) count else -1, bead = 8.dp, gap = 3.dp, max = 33)
                    else Text(stringResource(R.string.required_count, item.target), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$count", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            color = if (done) accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.scale(s))
                        Text(" / ${item.target}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                if (count > 0) {
                    OutlinedIconButton(onClick = onDec, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Remove, stringResource(R.string.undo), Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Surface(
                    onClick = { pulse++; onInc() },
                    enabled = !done,
                    shape = CircleShape,
                    color = if (done) accent.copy(alpha = 0.2f) else accent,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Add, stringResource(R.string.count_plus),
                            tint = if (done) accent else Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusView(
    w: WerdUi, index: Int, fontScale: Float, speaking: Boolean, wordStart: Int, wordEnd: Int,
    modifier: Modifier, onIndex: (Int) -> Unit, onCount: (WerdItem, Int) -> Unit, onFont: (Float) -> Unit,
    counterStyle: String = "ring", onStyle: () -> Unit = {},
    /** 0 large, 1 medium, 2 slim bar, 3 hidden (tap the text to count). */
    counterSize: Int = 1, beads: Boolean = true, onSize: () -> Unit = {},
) {
    val x = LocalExtra.current
    val item = w.items.getOrNull(index) ?: return
    val c = w.counts[item.id] ?: 0
    val done = c >= item.target
    val accent = WerdPalette[w.werd.colorIndex % WerdPalette.size]
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        // the counter takes at most ~30% of the height, so the text always keeps most of the screen
        val fullH = maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onIndex(index - 1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) }
                Text(stringResource(R.string.line_of, index + 1, w.items.size), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { onIndex(index + 1) }, enabled = index < w.items.size - 1) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
                Spacer(Modifier.weight(1f))
                // text size, right here in the tally view
                if (counterSize <= 1) IconButton(onClick = onStyle) {
                    Icon(if (counterStyle == "pull") Icons.Rounded.Autorenew else Icons.Rounded.SwipeDown, stringResource(R.string.counter_style))
                }
                // the counter's size: large, medium, a slim bar, or hidden (tap the text)
                IconButton(onClick = onSize) {
                    Icon(when (counterSize) { 0 -> Icons.Rounded.UnfoldLess; 1 -> Icons.Rounded.ViewAgenda; 2 -> Icons.Rounded.TouchApp; else -> Icons.Rounded.UnfoldMore },
                        stringResource(R.string.counter_size_now, stringResource(when (counterSize) {
                            0 -> R.string.counter_large; 1 -> R.string.counter_medium; 2 -> R.string.counter_slim; else -> R.string.counter_hidden })))
                }
                IconButton(onClick = { onFont(-0.1f) }, enabled = fontScale > 0.71f) { Icon(Icons.Rounded.TextDecrease, stringResource(R.string.text_smaller)) }
                IconButton(onClick = { onFont(0.1f) }, enabled = fontScale < 2.19f) { Icon(Icons.Rounded.TextIncrease, stringResource(R.string.text_bigger)) }
            }
            if (beads) com.werd.app.ui.components.BeadString(total = w.items.size, done = 0, current = index, max = 30, bead = 8.dp, gap = 4.dp,
                doneSet = w.items.withIndex().filter { (_, it) -> (w.counts[it.id] ?: 0) >= it.target }.map { it.index }.toSet())
            // The text gets all the room left above the counter and scrolls; when more text is hidden below,
            // a fade and a "more" hint say so — nothing can sit unseen under the button.
            val scroll = remember(index) { androidx.compose.foundation.ScrollState(0) }
            val tapText = counterSize == 3
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)
                .then(if (tapText) Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClickLabel = stringResource(R.string.tap_to_count)) { if (!done) onCount(item, 1) } else Modifier)) {
                val viewport = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                  // short text stays centred; long text starts at the top and scrolls
                  Box(Modifier.fillMaxWidth().heightIn(min = viewport), contentAlignment = Alignment.Center) {
                    Text(
                        highlighted(item.text, if (speaking) wordStart else -1, wordEnd, x.bead.copy(alpha = 0.30f)),
                        style = TextStyle(fontFamily = Amiri, fontSize = (26 * fontScale).sp, lineHeight = (46 * fontScale).sp,
                            textAlign = TextAlign.Center, textDirection = TextDirection.Content, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
                    )
                  }
                }
                if (scroll.canScrollForward) {
                    val bg = MaterialTheme.colorScheme.background
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
                    val scope = rememberCoroutineScope()
                    Surface(onClick = { scope.launch { scroll.animateScrollTo(scroll.value + (scroll.viewportSize * 0.8f).toInt()) } },
                        shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.BottomCenter)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.more_text), style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            // while a long text is being scrolled the counter steps aside, and comes back when the scrolling stops
            val scrolling = scroll.isScrollInProgress && (scroll.canScrollForward || scroll.canScrollBackward)
            if (counterSize >= 2) {
                androidx.compose.animation.AnimatedVisibility(!scrolling || counterSize == 3) {
                    SlimCounter(c, item.target, done, accent, hidden = counterSize == 3, onInc = { if (!done) onCount(item, 1) }, onDec = { onCount(item, -1) })
                }
            } else {
            // the misbaha: a loop that turns one bead per tap, or a hanging string you pull beads down
            val pull = counterStyle == "pull"
            val big = counterSize == 0
            val area = when {
                pull && big -> (fullH * 0.5f).coerceIn(250.dp, 430.dp)
                big -> (fullH * 0.48f).coerceIn(240.dp, 400.dp)
                pull -> (fullH * 0.36f).coerceIn(200.dp, 320.dp)
                else -> (fullH * 0.32f).coerceIn(180.dp, 290.dp)
            }
            androidx.compose.animation.AnimatedVisibility(!scrolling) {
            val tray = if (MaterialTheme.colorScheme.background.luminance() < 0.2f) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLowest
            val numbers: @Composable () -> Unit = {
                Column(horizontalAlignment = if (pull) Alignment.Start else Alignment.CenterHorizontally) {
                    val halo = androidx.compose.ui.graphics.Shadow(tray, blurRadius = 14f)
                    Text("$c", style = TextStyle(fontSize = (area.value * (if (pull) 0.17f else 0.15f)).sp, fontWeight = FontWeight.Bold, shadow = halo),
                        color = if (done) x.onDone else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.of_n, item.target), style = MaterialTheme.typography.titleSmall.copy(shadow = halo),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.target > 33) Text(stringResource(R.string.round_n, ((c - 1).coerceAtLeast(0)) / 33 + 1, (item.target + 32) / 33),
                        style = MaterialTheme.typography.labelSmall.copy(shadow = halo), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (done) Icon(Icons.Rounded.CheckCircle, null, tint = x.bead, modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(area).padding(bottom = 6.dp)) {
                androidx.compose.runtime.key(item.id, counterStyle) {
                    if (pull) com.werd.app.ui.components.MisbahaPull(count = c, target = item.target, live = speaking, done = done,
                        onCount = { if (!done) onCount(item, 1) }, modifier = Modifier.fillMaxSize(),
                        description = stringResource(R.string.pull_hint)) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.TopStart) { numbers() }
                    } else com.werd.app.ui.components.MisbahaRing(count = c, target = item.target, live = speaking, done = done,
                        onTap = { if (!done) onCount(item, 1) }, modifier = Modifier.fillMaxSize(),
                        description = stringResource(R.string.tap_to_count)) { numbers() }
                }
                // undo sits in the corner, out of the thumb's way
                OutlinedIconButton(onClick = { onCount(item, -1) }, enabled = c > 0,
                    modifier = Modifier.align(Alignment.BottomStart).size(44.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), Modifier.size(20.dp))
                }
            }
            }
            }
        }
    }
}

/** The slim counter: the count, undo and a big "+" in one bar; or, hidden, just the count (the text is tapped to count). */
@Composable
private fun SlimCounter(count: Int, target: Int, done: Boolean, accent: Color, hidden: Boolean, onInc: () -> Unit, onDec: () -> Unit) {
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(if (hidden) 16.dp else 22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (hidden) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = onDec, enabled = count > 0, modifier = Modifier.size(if (hidden) 34.dp else 42.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text("$count", fontSize = if (hidden) 20.sp else 28.sp, fontWeight = FontWeight.Bold, color = if (done) x.onDone else MaterialTheme.colorScheme.onSurface)
                Text(" / $target", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp))
                if (hidden) Text("  " + stringResource(R.string.counter_tap_text), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (!hidden) Surface(onClick = onInc, enabled = !done, shape = CircleShape, color = if (done) accent.copy(alpha = 0.2f) else accent,
                modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Add, stringResource(R.string.count_plus),
                        tint = if (done) accent else Color.White, modifier = Modifier.size(28.dp))
                }
            } else if (done) Icon(Icons.Rounded.CheckCircle, null, tint = x.onDone)
        }
    }
}

@Composable
private fun TtsBar(
    speaking: Boolean, lineLabel: String, hint: String, ready: List<com.werd.app.voice.VoiceLibrary.Ready>, onVoices: () -> Unit,
    autoCount: Boolean, speed: Float, showAutoCount: Boolean = true,
    onToggle: () -> Unit, onAutoCount: () -> Unit, onSpeed: () -> Unit,
) {
    val x = LocalExtra.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = onToggle, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(54.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (speaking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (speaking) R.string.ra_pause else R.string.listen),
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedContent(lineLabel, label = "rep") { l ->
                        Text(l, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onSpeed, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}×")
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showAutoCount) FilterChip(
                    selected = autoCount, onClick = onAutoCount,
                    label = { Text(stringResource(R.string.auto_count)) },
                    leadingIcon = if (autoCount) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null,
                )
                // the voice: ready on the phone or not, and a choice when several are
                val cur = ready.firstOrNull { it.current }
                val complete = ready.count { it.complete }
                if (ready.isNotEmpty()) AssistChip(
                    onClick = onVoices,
                    label = {
                        Text(when {
                            cur != null && cur.complete && complete > 1 -> stringResource(R.string.voice_ready_more, cur.name, complete - 1)
                            cur != null && cur.complete -> stringResource(R.string.voice_ready_named, cur.name)
                            cur == null && complete > 1 -> stringResource(R.string.voices_ready_n, complete)
                            cur != null -> stringResource(R.string.voice_ready_part, cur.name, cur.have, cur.total)
                            else -> stringResource(R.string.voices_ready_n, ready.size)
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = {
                        Icon(if (cur?.complete == true || complete > 0) Icons.Rounded.CheckCircle else Icons.Rounded.GraphicEq, null,
                            Modifier.size(16.dp), tint = if (cur?.complete == true || complete > 0) x.bead else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = if (ready.size > 1) { { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) } } else null,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceChooser(ready: List<com.werd.app.voice.VoiceLibrary.Ready>, usingPhone: Boolean,
                         onPick: (com.werd.app.voice.VoiceLibrary.Ready?) -> Unit, onDismiss: () -> Unit) {
    val x = LocalExtra.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.voice_choose), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.voice_choose_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ready.forEach { v ->
                Surface(onClick = { onPick(v) }, shape = RoundedCornerShape(18.dp),
                    color = if (v.current && !usingPhone) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = v.current && !usingPhone, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (v.complete) stringResource(R.string.voice_all_ready) else stringResource(R.string.voice_lines_ready, v.have, v.total),
                                style = MaterialTheme.typography.bodySmall, color = if (v.complete) x.bead else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        com.werd.app.ui.components.BeadString(total = v.total.coerceAtMost(12), done = (v.have * 12 / v.total.coerceAtLeast(1)).coerceAtMost(12)
                            .let { if (v.total <= 12) v.have else it }, bead = 7.dp, gap = 3.dp, max = 12)
                    }
                }
            }
            Surface(onClick = { onPick(null) }, shape = RoundedCornerShape(18.dp),
                color = if (usingPhone) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = usingPhone, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.ve_system), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.voice_phone_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
