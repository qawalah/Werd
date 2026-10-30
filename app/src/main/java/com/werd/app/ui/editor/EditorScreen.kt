package com.werd.app.ui.editor

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Tune
import com.werd.app.data.excludes
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.ALL_DAYS
import com.werd.app.data.Werd
import com.werd.app.data.WerdItem
import com.werd.app.parse.WerdParser
import com.werd.app.ui.MainActivity
import com.werd.app.ui.PendingImport
import com.werd.app.ui.components.ColorDots
import com.werd.app.ui.components.CountStepper
import com.werd.app.ui.components.DayChips
import com.werd.app.ui.components.SectionTitle
import com.werd.app.ui.home.MenuItem
import com.werd.app.ui.reminders.ReminderSummary
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.ui.theme.WerdPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EditLine(val key: Long, val id: Long, val text: String, val target: Int, val auto: Boolean)

private var keySeq = 1L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    app: WerdApp, activity: MainActivity, werdId: Long,
    onBack: () -> Unit, onSaved: (Long) -> Unit, onReminder: (Long, Long) -> Unit,
    onGroupReminder: (Long, Long) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dash by app.repo.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val groups = dash?.groups.orEmpty()
    val reminders = dash?.reminders.orEmpty().filter { it.werdId == werdId }
    val savedGroupId = dash?.werds?.firstOrNull { it.werd.id == werdId }?.werd?.groupId
    val groupReminders = dash?.reminders.orEmpty().filter { it.groupId != null && it.groupId == savedGroupId }
    val groupName = dash?.groups?.firstOrNull { it.id == savedGroupId }?.name.orEmpty()

    var loaded by remember { mutableStateOf(werdId == 0L) }
    var original by remember { mutableStateOf<Werd?>(null) }
    var title by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf<Long?>(null) }
    var color by remember { mutableIntStateOf(0) }
    var goalMask by remember { mutableIntStateOf(ALL_DAYS) }
    var textOnly by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<EditLine>() }
    var paste by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(werdId == 0L) }
    var split by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var detectedInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyParsed(r: WerdParser.Result) {
        if (r.items.isEmpty()) return
        if (title.isBlank()) r.title?.let { title = it }
        lines.clear()
        lines.addAll(r.items.map { EditLine(keySeq++, 0, it.text, it.count, it.detected) })
        detectedInfo = r.items.size to r.items.count { it.detected }
        showPaste = false
        dirty = true
    }

    fun convertOffline(text: String) = applyParsed(WerdParser.parse(text, split))

    LaunchedEffect(werdId) {
        if (werdId != 0L) {
            val w = app.db.dao().werd(werdId)
            if (w != null) {
                original = w; title = w.title; groupId = w.groupId; color = w.colorIndex; goalMask = w.goalDaysMask; textOnly = w.textOnly
                lines.clear()
                lines.addAll(app.db.dao().items(werdId).map { EditLine(keySeq++, it.id, it.text, it.target, false) })
                showPaste = lines.isEmpty()
            }
            loaded = true
        }
    }

    // ---------------------------------------------------------------- text recognition (photo / image / PDF)
    val settingsState by app.settings.flow.collectAsStateWithLifecycle()
    var ocrStatus by remember { mutableStateOf<String?>(null) }
    var ocrJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    val msgReading = stringResource(R.string.ocr_reading)
    val msgAiReading = stringResource(R.string.ocr_ai_reading)
    val msgAiFailed = stringResource(R.string.ocr_ai_failed)
    val msgPage = stringResource(R.string.ocr_page)
    val msgDone = stringResource(R.string.ocr_done)
    val msgFailed = stringResource(R.string.ocr_failed)
    val msgMorePages = stringResource(R.string.ocr_more_pages, com.werd.app.ocr.Ocr.MAX_PDF_PAGES)
    val msgLocalAi = stringResource(R.string.reader_working)
    val ocrPrefs by com.werd.app.ocr.OcrStore.get(context).flow.collectAsStateWithLifecycle()

    val msgAiConverting = stringResource(R.string.convert_ai_working)
    val msgAiConvertFailed = stringResource(R.string.convert_ai_failed)

    /** "Convert": with AI when chosen (better at run-on paragraphs), otherwise the on-phone parser. */
    fun convert(text: String) {
        val s = app.settings.value
        val ai = if (s.aiReady && s.aiScan) runCatching { com.werd.app.sources.Sources.aiClient(context) }.getOrNull() else null
        if (ai == null) { convertOffline(text); return }
        ocrJob?.cancel(); ocrNote = null; ocrStatus = msgAiConverting
        ocrJob = scope.launch {
            val r = runCatching { com.werd.app.ai.AiTasks.splitWerd(ai, text) }
            ocrStatus = null
            val lines = r.getOrNull()?.lines.orEmpty()
            if (lines.isEmpty()) {
                ocrNote = msgAiConvertFailed + (r.exceptionOrNull()?.message?.let { "\n" + it } ?: "")
                convertOffline(text)
            } else applyParsed(WerdParser.Result(r.getOrNull()?.title, lines.map { (t, n) -> WerdParser.Item(t, n, n > 1) }))
        }
    }

    fun runOcr(uri: android.net.Uri, mime: String) {
        ocrJob?.cancel()
        showPaste = true
        ocrNote = null
        ocrStatus = msgReading
        ocrJob = scope.launch {
            val letters = app.settings.value.ocrLettersOnly
            val ai = if (app.settings.value.aiScan) runCatching { com.werd.app.sources.Sources.aiClient(context) }.getOrNull() else null
            if (ai != null) ocrStatus = msgAiReading
            else if (ocrPrefs.engine != com.werd.app.ocr.OcrPrefs.TESSERACT) ocrStatus = msgLocalAi
            val result = runCatching {
                when {
                    ai != null && mime == "application/pdf" -> com.werd.app.ocr.Ocr.aiFromPdf(context, uri, ai) { p, n -> ocrStatus = msgPage.format(p, n) }
                    ai != null -> com.werd.app.ocr.Ocr.aiFromImage(context, uri, ai)
                    mime == "application/pdf" -> com.werd.app.ocr.Ocr.fromPdf(context, uri, letters) { p, n -> ocrStatus = msgPage.format(p, n) }
                    else -> com.werd.app.ocr.Ocr.fromImage(context, uri, letters)
                }
            }
            result.exceptionOrNull()?.takeIf { it is com.werd.app.ai.AiException }?.let { e ->
                ocrStatus = null; ocrNote = msgAiFailed + "\n" + e.message; return@launch
            }
            result.exceptionOrNull()?.takeIf { it !is kotlinx.coroutines.CancellationException && ai == null &&
                ocrPrefs.engine != com.werd.app.ocr.OcrPrefs.TESSERACT }?.let { e ->
                ocrStatus = null; ocrNote = msgAiFailed + "\n" + (e.message ?: e.javaClass.simpleName); return@launch
            }
            ocrStatus = null
            val text = result.getOrNull().orEmpty()
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            if (text.isBlank()) { ocrNote = msgFailed; return@launch }
            paste = if (paste.isBlank()) text else paste.trimEnd() + "\n\n" + text
            val pages = if (mime == "application/pdf") runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { android.graphics.pdf.PdfRenderer(it).use { r -> r.pageCount } } ?: 0
            }.getOrDefault(0) else 0
            ocrNote = msgDone + if (pages > com.werd.app.ocr.Ocr.MAX_PDF_PAGES) "\n" + msgMorePages else ""
        }
    }

    fun openAny(uri: android.net.Uri) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        when {
            mime == "application/pdf" || uri.toString().endsWith(".pdf", true) -> runOcr(uri, "application/pdf")
            mime.startsWith("image/") -> runOcr(uri, mime)
            else -> activity.readUri(uri)?.let { paste = it; convert(it) }
        }
    }

    LaunchedEffect(Unit) {
        if (werdId == 0L) {
            PendingImport.text?.let { paste = it; PendingImport.text = null; convert(it) }
            PendingImport.media?.let { (u, m) -> PendingImport.media = null; runOcr(u, m) }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        openAny(uri)
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOcr(uri, "application/pdf")
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOcr(uri, "image/*")
    }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) runOcr(u, "image/jpeg")
    }
    fun takePhoto() {
        val dir = java.io.File(context.cacheDir, "ocr").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = java.io.File(dir, "page_${System.currentTimeMillis()}.jpg")
        val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        cameraUri = u
        runCatching { cameraLauncher.launch(u) }
    }

    val needTitle = stringResource(R.string.err_need_lines)
    fun save() {
        val clean = lines.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) { error = needTitle; return }
        val t = title.trim().ifEmpty { clean.first().text.take(40) }
        val base = original ?: Werd(title = t)
        scope.launch {
            val id = app.repo.saveWerd(
                base.copy(title = t, groupId = groupId, colorIndex = color, goalDaysMask = goalMask, textOnly = textOnly),
                clean.map { WerdItem(id = it.id, werdId = base.id, position = 0, text = it.text.trim(), target = if (textOnly) 1 else it.target) },
            )
            withContext(Dispatchers.Main.immediate) { onSaved(id) }
        }
    }

    BackHandler(enabled = dirty) { confirmExit = true }

    val x = LocalExtra.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (werdId == 0L) R.string.new_werd else R.string.edit_werd)) },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = loaded) {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = title, onValueChange = { title = it; dirty = true },
                    label = { Text(stringResource(R.string.werd_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.titleMedium,
                )
            }
            if (showPaste) {
                item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, x.cardBorder)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(stringResource(R.string.paste_title))
                            Text(stringResource(R.string.paste_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = paste, onValueChange = { paste = it },
                                placeholder = { Text(stringResource(R.string.paste_placeholder), fontFamily = Amiri) },
                                minLines = 8, maxLines = 16,
                                textStyle = TextStyle(fontFamily = Amiri, fontSize = 18.sp, lineHeight = 30.sp,
                                    textDirection = TextDirection.Content, color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { paste = it }
                                }) { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.paste)) }
                                OutlinedButton(onClick = { fileLauncher.launch(arrayOf("text/*", "application/pdf", "image/*", "application/octet-stream")) }) {
                                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.import_file))
                                }
                            }
                            // ---- how to read: AI or on this phone (applies to scanning and to Convert)
                            Text(stringResource(R.string.mode_q), style = MaterialTheme.typography.labelLarge)
                            com.werd.app.ui.sources.ReadModePicker(useAi = settingsState.aiScan, aiReady = settingsState.aiReady,
                                onChange = { v -> app.settings.update { it.copy(aiScan = v) } }, compact = true)
                            if (!(settingsState.aiReady && settingsState.aiScan)) com.werd.app.ui.settings.ReaderEnginePicker(compact = true)
                            // ---- scan: camera / photo / PDF
                            Text(stringResource(R.string.ocr_title), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.material3.FilledTonalButton(onClick = ::takePhoto, enabled = ocrStatus == null) {
                                    Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ocr_camera))
                                }
                                androidx.compose.material3.FilledTonalButton(enabled = ocrStatus == null, onClick = {
                                    imageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) {
                                    Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ocr_image))
                                }
                                androidx.compose.material3.FilledTonalButton(enabled = ocrStatus == null, onClick = { pdfLauncher.launch(arrayOf("application/pdf")) }) {
                                    Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ocr_pdf))
                                }
                            }
                            if (!(settingsState.aiReady && settingsState.aiScan)) Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.ocr_letters_only), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.ocr_letters_only_d), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(settingsState.ocrLettersOnly, { v -> app.settings.update { it.copy(ocrLettersOnly = v) } })
                            }
                            ocrStatus?.let { st ->
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(st, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { ocrJob?.cancel(); ocrStatus = null }) { Text(stringResource(R.string.cancel)) }
                                        }
                                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            ocrNote?.let { n ->
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DocumentScanner, null, tint = x.gold)
                                        Spacer(Modifier.width(10.dp))
                                        Text(n, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.split_sentences), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(split, { split = it })
                            }
                            Button(onClick = { convert(paste) }, enabled = paste.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.convert))
                            }
                            if (lines.isNotEmpty()) TextButton(onClick = { showPaste = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.group), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = groupId == null, onClick = { groupId = null; dirty = true },
                        label = { Text(stringResource(R.string.group_other)) })
                    groups.forEach { g ->
                        FilterChip(selected = groupId == g.id, onClick = { groupId = g.id; dirty = true },
                            label = { Text(g.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(WerdPalette[g.colorIndex % WerdPalette.size])) })
                    }
                }
            }
            item {
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                ColorDots(color, WerdPalette) { color = it; dirty = true }
            }
            item {
                // a text to read (a poem, a sermon, a supplication said once) has no counter, goal or streak
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.text_only), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.text_only_d), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(textOnly, { textOnly = it; dirty = true })
                }
            }
            if (!textOnly) item {
                Text(stringResource(R.string.goal_days), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.goal_days_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                DayChips(goalMask, { goalMask = it; dirty = true })
            }

            if (!showPaste) {
                item {
                    SectionTitle(stringResource(R.string.lines_title, lines.size)) {
                        TextButton(onClick = { showPaste = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.replace_text))
                        }
                    }
                    detectedInfo?.let { (n, d) ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AutoAwesome, null, tint = x.gold)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.detected_info, n, d), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                itemsIndexed(lines, key = { _, l -> l.key }) { i, l ->
                    LineEditor(
                        index = i, line = l, last = i == lines.lastIndex, showCount = !textOnly,
                        onText = { lines[i] = l.copy(text = it); dirty = true },
                        onCount = { lines[i] = l.copy(target = it, auto = false); dirty = true },
                        onUp = { if (i > 0) { lines.add(i - 1, lines.removeAt(i)); dirty = true } },
                        onDown = { if (i < lines.lastIndex) { lines.add(i + 1, lines.removeAt(i)); dirty = true } },
                        onDuplicate = { lines.add(i + 1, l.copy(key = keySeq++, id = 0)); dirty = true },
                        onRedetect = {
                            val d = WerdParser.detect(l.text)
                            if (d.count != null) { lines[i] = l.copy(text = d.rest, target = d.count, auto = true); dirty = true }
                        },
                        onDelete = { lines.removeAt(i); dirty = true },
                        onMergeUp = {
                            if (i > 0) {
                                val prev = lines[i - 1]
                                lines[i - 1] = prev.copy(text = prev.text.trimEnd() + "، " + l.text.trim(), target = maxOf(prev.target, l.target))
                                lines.removeAt(i); dirty = true
                            }
                        },
                        onSplit = {
                            val parts = WerdParser.splitClauses(l.text)
                            if (parts.size > 1) {
                                lines.removeAt(i)
                                lines.addAll(i, parts.mapIndexed { k, t ->
                                    EditLine(keySeq++, if (k == 0) l.id else 0, t.trim().trimEnd('،', ','), if (k == parts.lastIndex) l.target else 1, false)
                                })
                                dirty = true
                            }
                        },
                    )
                }
                item {
                    OutlinedButton(onClick = { lines.add(EditLine(keySeq++, 0, "", 1, false)); dirty = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_line))
                    }
                }
            }

            if (werdId != 0L) {
                item {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle(stringResource(R.string.reminders)) {
                        TextButton(onClick = { onReminder(werdId, 0) }) {
                            Icon(Icons.Rounded.NotificationAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.add_reminder))
                        }
                    }
                }
                // reminders inherited from the werd's group: switch per werd, or adjust into its own copy
                itemsIndexed(groupReminders, key = { _, r -> "gr${r.id}" }) { _, r ->
                    val applies = !r.excludes(werdId)
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, x.cardBorder)) {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                            Text(stringResource(R.string.from_group, groupName), style = MaterialTheme.typography.labelSmall, color = x.gold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.NotificationsActive, null,
                                    tint = if (applies && r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(10.dp))
                                ReminderSummary(r, Modifier.weight(1f))
                                Switch(applies, { on -> scope.launch { app.repo.setGroupReminderApplies(r.id, werdId, on) } })
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val id = app.repo.customiseGroupReminder(r.id, werdId)
                                    withContext(Dispatchers.Main.immediate) { onReminder(werdId, id) }
                                }
                            }) { Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.adjust_for_werd)) }
                        }
                    }
                }
                if (reminders.isEmpty() && groupReminders.isEmpty()) item {
                    Text(stringResource(R.string.no_reminders_werd), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                }
                itemsIndexed(reminders, key = { _, r -> "r${r.id}" }) { _, r ->
                    Surface(onClick = { onReminder(werdId, r.id) }, shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, x.cardBorder)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsActive, null,
                                tint = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(10.dp))
                            ReminderSummary(r, Modifier.weight(1f))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.discard_q)) },
            confirmButton = { TextButton(onClick = { confirmExit = false; save() }) { Text(stringResource(R.string.save)) } },
            dismissButton = {
                TextButton(onClick = { confirmExit = false; dirty = false; onBack() }) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
}

@Composable
private fun LineEditor(
    index: Int, line: EditLine, last: Boolean, showCount: Boolean,
    onText: (String) -> Unit, onCount: (Int) -> Unit, onUp: () -> Unit, onDown: () -> Unit,
    onDuplicate: () -> Unit, onRedetect: () -> Unit, onDelete: () -> Unit,
    onMergeUp: () -> Unit, onSplit: () -> Unit,
) {
    val x = LocalExtra.current
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (line.auto) x.gold.copy(alpha = 0.55f) else x.cardBorder)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(x.gold.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = x.gold)
                }
                if (line.auto) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = x.gold.copy(alpha = 0.15f)) {
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = x.gold)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.auto_detected), fontSize = 11.sp, color = x.gold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(18.dp)) }
                IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(18.dp)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options), Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(R.string.detect_count, Icons.Rounded.AutoAwesome) { menu = false; onRedetect() }
                        if (index > 0) MenuItem(R.string.merge_up, Icons.Rounded.VerticalAlignTop) { menu = false; onMergeUp() }
                        if (WerdParser.splitClauses(line.text).size > 1)
                            MenuItem(R.string.split_phrases, Icons.AutoMirrored.Rounded.CallSplit) { menu = false; onSplit() }
                        MenuItem(R.string.duplicate, Icons.Rounded.ContentCopy) { menu = false; onDuplicate() }
                        MenuItem(R.string.delete, Icons.Rounded.Delete) { menu = false; onDelete() }
                    }
                }
            }
            OutlinedTextField(
                value = line.text, onValueChange = onText,
                textStyle = TextStyle(fontFamily = Amiri, fontSize = 18.sp, lineHeight = 30.sp,
                    textDirection = TextDirection.Content, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
            if (showCount) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Text(stringResource(R.string.repeat_times), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    CountStepper(line.target, onCount, highlight = line.auto)
                }
            }
        }
    }
}
