package com.werd.app.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ocr.AiReader
import com.werd.app.ocr.NanoReader
import com.werd.app.ocr.OcrPrefs
import com.werd.app.ocr.OcrStore
import com.werd.app.ocr.PhoneEngine
import com.werd.app.voice.VoiceChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun readerEngineLabel(engine: String): String = stringResource(when (engine) {
    OcrPrefs.SERVER -> R.string.reader_server
    OcrPrefs.PHONE -> R.string.reader_phone
    OcrPrefs.NANO -> R.string.reader_nano
    else -> R.string.reader_tesseract
})

@Composable
private fun readerEngineHint(engine: String): String = stringResource(when (engine) {
    OcrPrefs.SERVER -> R.string.reader_server_d
    OcrPrefs.PHONE -> R.string.reader_phone_d
    OcrPrefs.NANO -> R.string.reader_nano_d
    else -> R.string.reader_tesseract_d
})

/** The engine chips shown in the editor and when adding a book (offline reading). */
@Composable
fun ReaderEnginePicker(compact: Boolean = false) {
    val context = LocalContext.current
    val store = remember { OcrStore.get(context) }
    val p by store.flow.collectAsStateWithLifecycle()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OcrPrefs.ENGINES.forEach { e ->
            FilterChip(selected = p.engine == e, onClick = { store.update { it.copy(engine = e) } }, label = { Text(readerEngineLabel(e)) })
        }
    }
    if (!compact || p.engine != OcrPrefs.TESSERACT) {
        val detail = when (p.engine) {
            OcrPrefs.PHONE -> AiReader.model(p.phoneModel)?.name?.let { n ->
                if (PhoneEngine.installed(context, p.phoneModel)) n else stringResource(R.string.reader_not_downloaded, n)
            }
            else -> null
        }
        Text(listOfNotNull(readerEngineHint(p.engine), detail).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings → Reading pages: choose the engine, download models, and try a photo with each. */
@Composable
fun ReaderSection(app: WerdApp) {
    val context = LocalContext.current
    val store = remember { OcrStore.get(context) }
    val p by store.flow.collectAsStateWithLifecycle()
    Text(stringResource(R.string.reader_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OcrPrefs.ENGINES.forEach { e ->
        Row(Modifier.fillMaxWidth().clickable { store.update { it.copy(engine = e) } }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(p.engine == e, null)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(readerEngineLabel(e), style = MaterialTheme.typography.bodyLarge)
                Text(readerEngineHint(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    when (p.engine) {
        OcrPrefs.SERVER -> ServerModels(app, p)
        OcrPrefs.PHONE -> PhoneModels(p)
        OcrPrefs.NANO -> NanoBox()
        else -> {}
    }
    TryPage(p)
}

@Composable
private fun ServerModels(app: WerdApp, p: OcrPrefs) {
    val context = LocalContext.current
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<Triple<Boolean, String, List<Pair<String, String>>>?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(cloud.configured) {
        if (!cloud.configured) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { app.cloud.ocrModels() } }.onSuccess { state = it; err = "" }
            .onFailure { err = if ((it as? com.werd.app.cloud.CloudException)?.kind == com.werd.app.cloud.CloudException.Kind.NOT_FOUND)
                context.getString(R.string.reader_server_old) else it.message ?: it.javaClass.simpleName }
    }
    when {
        !cloud.configured -> Text(stringResource(R.string.reader_no_server), style = MaterialTheme.typography.bodySmall)
        err.isNotBlank() -> Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        state == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        !state!!.first || state!!.third.isEmpty() -> Text(stringResource(R.string.reader_server_none), style = MaterialTheme.typography.bodySmall)
        else -> {
            val (_, def, models) = state!!
            val choices = listOf(VoiceChoice("", stringResource(R.string.ve_server_default), models.firstOrNull { it.first == def }?.second.orEmpty())) +
                models.map { (id, name) -> VoiceChoice(id, name) }
            Picker(stringResource(R.string.reader_model), p.serverModel, choices) { c -> OcrStore.get(context).update { it.copy(serverModel = c.id) } }
        }
    }
}

@Composable
private fun PhoneModels(p: OcrPrefs) {
    val context = LocalContext.current
    val store = remember { OcrStore.get(context) }
    val dl by PhoneEngine.download.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(dl) { tick++ }
    if (!PhoneEngine.supported(context)) {
        Text(stringResource(R.string.reader_phone_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val ram = remember { PhoneEngine.ramGb(context) }
    Text(stringResource(R.string.reader_phone_ram, ram), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AiReader.MODELS.forEach { m ->
        val installed = remember(tick) { PhoneEngine.installed(context, m.id) }
        val size = m.files.sumOf { it.size }
        Surface(shape = MaterialTheme.shapes.medium, color = if (p.phoneModel == m.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(p.phoneModel == m.id, { store.update { it.copy(phoneModel = m.id) } })
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${m.detail} · %.1f GB".format(java.util.Locale.US, size / 1e9) +
                            if (ram in 1 until m.ramGb) " · " + stringResource(R.string.reader_needs_ram, m.ramGb) else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val d = dl?.takeIf { it.id == m.id }
                when {
                    d != null && d.error == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("%.2f / %.2f GB".format(java.util.Locale.US, d.done / 1e9, d.total / 1e9), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { PhoneEngine.cancelDownload(context) }) { Text(stringResource(R.string.cancel)) }
                        }
                        LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                    installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.reader_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { PhoneEngine.remove(context, m.id); tick++ }) { Text(stringResource(R.string.delete)) }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d?.error != null) Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                        OutlinedButton(enabled = dl == null || dl?.error != null, onClick = {
                            store.update { it.copy(phoneModel = m.id) }; PhoneEngine.startDownload(context, m.id)
                        }) { Text(stringResource(R.string.reader_download)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Text(stringResource(R.string.reader_phone_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NanoBox() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { status = NanoReader.status() }
    when (status) {
        null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        com.google.mlkit.genai.common.FeatureStatus.AVAILABLE ->
            Text(stringResource(R.string.reader_nano_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE ->
            Text(stringResource(R.string.reader_nano_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> {
            progress?.let { (d, t) ->
                Text("%.0f / %.0f MB".format(java.util.Locale.US, d / 1048576.0, t / 1048576.0), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (t > 0) d.toFloat() / t else 0f }, modifier = Modifier.fillMaxWidth())
            } ?: Button(onClick = {
                err = ""; progress = 0L to 0L
                scope.launch {
                    runCatching { NanoReader.download { d, t -> progress = d to t } }.onFailure { err = it.message ?: it.javaClass.simpleName }
                    progress = null; status = NanoReader.status()
                }
            }) { Text(stringResource(R.string.reader_nano_get)) }
        }
    }
    if (err.isNotBlank()) Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Pick a photo and read it with the chosen engine: shows the text and how long it took, to compare engines and models. */
@Composable
private fun TryPage(p: OcrPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val engineName = readerEngineLabel(p.engine)
    LaunchedEffect(busy) { while (busy) { elapsed = ((System.currentTimeMillis() - started) / 1000).toInt(); kotlinx.coroutines.delay(1000) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; result = null; meta = ""; started = System.currentTimeMillis()
        job = scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("image")
                    try { AiReader.open(context, p).use { it.read(bmp, false) } } finally { bmp.recycle() }
                }
            }
            busy = false
            val secs = (System.currentTimeMillis() - started) / 1000
            r.onSuccess { result = it; meta = "$engineName · ${secs}s" }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) meta = it.message ?: it.javaClass.simpleName }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(stringResource(R.string.reader_try))
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("${elapsed}s", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { job?.cancel(); busy = false }) { Text(stringResource(R.string.cancel)) }
        }
    }
    if (busy && p.engine != OcrPrefs.TESSERACT) Text(stringResource(R.string.reader_working), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall,
        color = if (result == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    result?.let { t ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(t.ifBlank { "—" }, modifier = Modifier.padding(12.dp), fontSize = 19.sp, lineHeight = 34.sp,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl))
            }
        }
    }
}
