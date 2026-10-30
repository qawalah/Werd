package com.werd.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.tts.TtsController
import com.werd.app.voice.ClipVoice
import com.werd.app.voice.CloudVoices
import com.werd.app.voice.PiperVoice
import com.werd.app.voice.VoiceCache
import com.werd.app.voice.VoiceChoice
import com.werd.app.voice.VoicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE_TEXT = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ"

@Composable
fun voiceEngineLabel(engine: String): String = stringResource(when (engine) {
    VoicePrefs.PIPER -> R.string.ve_piper
    VoicePrefs.GEMINI -> R.string.ve_gemini
    VoicePrefs.AZURE -> R.string.ve_azure
    VoicePrefs.ELEVEN -> R.string.ve_eleven
    VoicePrefs.SERVER -> R.string.ve_server
    else -> R.string.ve_system
})

@Composable
private fun engineHint(engine: String): String = stringResource(when (engine) {
    VoicePrefs.PIPER -> R.string.ve_piper_d
    VoicePrefs.GEMINI -> R.string.ve_gemini_d
    VoicePrefs.AZURE -> R.string.ve_azure_d
    VoicePrefs.ELEVEN -> R.string.ve_eleven_d
    VoicePrefs.SERVER -> R.string.ve_server_d
    else -> R.string.ve_system_d
})

/** Read-aloud voice: the phone's own, on-phone natural (Piper), Gemini, Azure, ElevenLabs, or the family server. */
@Composable
fun VoiceSection(app: WerdApp, tts: TtsController, systemContent: @Composable () -> Unit) {
    val v by app.voice.flow.collectAsStateWithLifecycle()
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val st by tts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(block: (VoicePrefs) -> VoicePrefs) = app.voice.update(block)

    Text(stringResource(R.string.ve_title), style = MaterialTheme.typography.labelLarge)
    VoicePrefs.ENGINES.forEach { e ->
        Row(Modifier.fillMaxWidth().clickable { upd { it.copy(engine = e) } }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(v.engine == e, null)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(voiceEngineLabel(e), style = MaterialTheme.typography.bodyLarge)
                Text(engineHint(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))

    when (v.engine) {
        VoicePrefs.SYSTEM -> systemContent()
        VoicePrefs.PIPER -> PiperBox(app, v)
        VoicePrefs.GEMINI -> {
            Secret(v.geminiKey, stringResource(R.string.ve_gemini_key),
                if (s.aiKeyGemini.isNotBlank()) stringResource(R.string.ve_gemini_key_same) else "AIza…") { k -> upd { it.copy(geminiKey = k) } }
            Picker(stringResource(R.string.ve_voice), v.geminiVoice, VoicePrefs.GEMINI_VOICES.map { VoiceChoice(it, it) }) { c -> upd { it.copy(geminiVoice = c.id) } }
            OutlinedTextField(v.geminiStyle, { t -> upd { it.copy(geminiStyle = t.take(80)) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ve_style)) }, placeholder = { Text(stringResource(R.string.ve_style_hint)) })
            OutlinedTextField(v.geminiModel, { t -> upd { it.copy(geminiModel = t.trim()) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ve_model)) })
        }
        VoicePrefs.AZURE -> {
            Secret(v.azureKey, stringResource(R.string.ve_azure_key), "") { k -> upd { it.copy(azureKey = k) } }
            Picker(stringResource(R.string.ve_region), v.azureRegion, VoicePrefs.AZURE_REGIONS.map { VoiceChoice(it, it) }, allowCustom = true) { c ->
                upd { it.copy(azureRegion = c.id) }
            }
            var list by remember(v.azureKey, v.azureRegion) { mutableStateOf(VoicePrefs.AZURE_VOICES.map { VoiceChoice(it, it) }) }
            LaunchedEffect(v.azureKey, v.azureRegion) {
                if (v.azureKey.length > 10) runCatching { withContext(Dispatchers.IO) { CloudVoices.azureVoices(v.azureKey, v.azureRegion) } }
                    .onSuccess { if (it.isNotEmpty()) list = it }
            }
            Picker(stringResource(R.string.ve_voice), v.azureVoice, list) { c -> upd { it.copy(azureVoice = c.id) } }
        }
        VoicePrefs.ELEVEN -> {
            Secret(v.elevenKey, stringResource(R.string.ve_eleven_key), "") { k -> upd { it.copy(elevenKey = k) } }
            var list by remember(v.elevenKey) { mutableStateOf<List<VoiceChoice>>(emptyList()) }
            var err by remember(v.elevenKey) { mutableStateOf("") }
            LaunchedEffect(v.elevenKey) {
                if (v.elevenKey.length > 10) runCatching { withContext(Dispatchers.IO) { CloudVoices.elevenVoices(v.elevenKey) } }
                    .onSuccess { list = it; err = "" }.onFailure { err = it.message.orEmpty() }
            }
            Picker(stringResource(R.string.ve_voice), v.elevenVoiceName.ifBlank { v.elevenVoice }, list) { c ->
                upd { it.copy(elevenVoice = c.id, elevenVoiceName = c.name) }
            }
            if (err.isNotBlank()) Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(v.elevenModel, { t -> upd { it.copy(elevenModel = t.trim()) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ve_model)) }, supportingText = { Text(stringResource(R.string.ve_eleven_model_hint)) })
        }
        VoicePrefs.SERVER -> ServerVoices(app, v)
    }

    // pace and pitch apply to every voice
    Text(stringResource(R.string.s_speed, "%.2f".format(java.util.Locale.US, s.ttsRate)), style = MaterialTheme.typography.labelLarge)
    Slider(s.ttsRate, { r -> app.settings.update { it.copy(ttsRate = r) } }, valueRange = 0.5f..1.6f)
    Text(stringResource(R.string.s_pitch, "%.2f".format(java.util.Locale.US, s.ttsPitch)), style = MaterialTheme.typography.labelLarge)
    Slider(s.ttsPitch, { p -> app.settings.update { it.copy(ttsPitch = p) } }, valueRange = 0.6f..1.5f)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            tts.setVoice(s.ttsRate, s.ttsPitch)
            val c = ClipVoice.current(app)
            if (c != null) tts.previewClip(c, SAMPLE_TEXT) else tts.preview(SAMPLE_TEXT)
        }) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s_test_voice))
        }
        if (st.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    if (st.error.isNotBlank()) Text(stringResource(R.string.voice_failed) + " " + st.error, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)

    val cloudState by app.cloud.store.flow.collectAsStateWithLifecycle()
    if (v.isClip && v.engine != VoicePrefs.SERVER && cloudState.configured) {
        Row(Modifier.fillMaxWidth().clickable { upd { it.copy(shareToServer = !it.shareToServer) } }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ve_share), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ve_share_d, v.shareName()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(v.shareToServer, { on -> upd { it.copy(shareToServer = on) } })
        }
    }

    if (v.isClip) {
        var size by remember { mutableStateOf(VoiceCache(app).size()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ve_cache, size.first, "%.1f".format(java.util.Locale.US, size.second / 1048576.0)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { VoiceCache(app).clear() }; size = VoiceCache(app).size() } }) {
                Text(stringResource(R.string.ve_cache_clear))
            }
        }
        Text(stringResource(R.string.ve_cache_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Secret(value: String, label: String, placeholder: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, { onChange(it.trim()) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) androidx.compose.material.icons.Icons.Rounded.VisibilityOff else androidx.compose.material.icons.Icons.Rounded.Visibility, null)
            }
        })
}

/** A read-only field that opens a list of choices (optionally also accepts typing). */
@Composable
internal fun Picker(label: String, current: String, choices: List<VoiceChoice>, allowCustom: Boolean = false, onPick: (VoiceChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = choices.firstOrNull { it.id == current }?.let { if (it.id.isBlank()) it.name else if (it.name != it.id && it.name.isNotBlank()) "${it.name} · ${it.id}" else it.id } ?: current
    Box {
        OutlinedTextField(shown, { if (allowCustom) onPick(VoiceChoice(it.trim(), it.trim())) }, readOnly = !allowCustom, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(label) },
            trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Rounded.ArrowDropDown, null) } })
        if (!allowCustom) Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { c ->
                DropdownMenuItem(text = {
                    Column {
                        Text(if (c.name.isNotBlank()) c.name else c.id)
                        val sub = listOf(c.id.takeIf { it != c.name }, c.detail.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, onClick = { open = false; onPick(c) })
            }
        }
    }
}

@Composable
private fun PiperBox(app: WerdApp, v: VoicePrefs) {
    val piper = remember { PiperVoice(app) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var err by remember { mutableStateOf("") }
    val installed = remember(tick, v.piperModel) { piper.installed(v.piperModel) }
    Text(stringResource(R.string.ve_piper_quality), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(PiperVoice.MEDIUM to R.string.ve_piper_medium, PiperVoice.LOW to R.string.ve_piper_low).forEachIndexed { i, (m, l) ->
            SegmentedButton(v.piperModel == m, { app.voice.update { it.copy(piperModel = m) } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(l)) }
        }
    }
    when {
        progress != null -> {
            val p = progress!!
            Text(if (p < 0) stringResource(R.string.ve_piper_unpacking) else stringResource(R.string.ve_piper_downloading, (p * 100).toInt()),
                style = MaterialTheme.typography.bodySmall)
            if (p < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
        installed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ve_piper_ready, "%.0f".format(java.util.Locale.US, piper.sizeOnDisk() / 1048576.0)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { piper.remove(v.piperModel); tick++ }) { Text(stringResource(R.string.delete)) }
        }
        else -> Button(onClick = {
            err = ""; progress = 0f
            scope.launch {
                runCatching { piper.download(v.piperModel) { p -> scope.launch(Dispatchers.Main) { progress = p } } }
                    .onFailure { err = it.message ?: it.javaClass.simpleName }
                progress = null; tick++
            }
        }) { Text(stringResource(R.string.ve_piper_download)) }
    }
    if (err.isNotBlank()) Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ServerVoices(app: WerdApp, v: VoicePrefs) {
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<Triple<Boolean, String, List<Triple<String, String, String>>>?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(cloud.configured) {
        if (!cloud.configured) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { app.cloud.voices() } }.onSuccess { state = it; err = "" }
            .onFailure { err = it.message ?: it.javaClass.simpleName }
    }
    val sharedLabel = stringResource(R.string.ve_shared_voice)
    when {
        !cloud.configured -> Text(stringResource(R.string.ve_server_none), style = MaterialTheme.typography.bodySmall)
        err.isNotBlank() -> Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        state == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        !state!!.first -> Text(stringResource(R.string.ve_server_off), style = MaterialTheme.typography.bodySmall)
        else -> {
            val (_, def, voices) = state!!
            val choices = listOf(VoiceChoice("", stringResource(R.string.ve_server_default), def)) +
                voices.map { (id, name, detail) ->
                    if (detail.startsWith("shared:")) VoiceChoice(id, name, sharedLabel.format(detail.removePrefix("shared:")))
                    else VoiceChoice(id, name, detail)
                }
            Picker(stringResource(R.string.ve_voice), v.serverVoice, choices) { c -> app.voice.update { it.copy(serverVoice = c.id) } }
            Text(stringResource(R.string.ve_server_lib), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
