package com.werd.app.ui.settings

import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.BuildConfig
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Backup
import com.werd.app.data.ThemeMode
import com.werd.app.reminder.Notifier
import com.werd.app.reminder.ReminderScheduler
import com.werd.app.tts.TtsController
import com.werd.app.ui.MainActivity
import com.werd.app.ui.components.OrnateHeader
import com.werd.app.ui.components.SectionTitle
import com.werd.app.ui.theme.Amiri
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.widget.NextWerdWidgetReceiver
import com.werd.app.widget.StreakWidgetReceiver
import com.werd.app.widget.TodayWidgetReceiver
import com.werd.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(app: WerdApp, activity: MainActivity) {
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmImport by remember { mutableStateOf<String?>(null) }
    val tts = remember(s.ttsEngine) { TtsController(context, s.ttsEngine) }
    DisposableEffect(tts) { onDispose { tts.shutdown() } }
    androidx.compose.runtime.LaunchedEffect(tts, s.ttsVoice) { tts.preferredVoice = s.ttsVoice }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tts.refreshVoices() }

    val exportOk = stringResource(R.string.export_ok)
    val importOk = stringResource(R.string.import_ok)
    val importFail = stringResource(R.string.import_fail)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val json = Backup.export(app.db.dao())
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            Toast.makeText(context, exportOk, Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        confirmImport = activity.readUri(uri)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OrnateHeader {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(20.dp)) {
                Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group(stringResource(R.string.s_appearance)) {
                Label(stringResource(R.string.s_language))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("" to stringResource(R.string.s_system), "ar" to "العربية", "en" to "English").forEachIndexed { i, (tag, label) ->
                        SegmentedButton(s.language == tag, {
                            app.settings.update { it.copy(language = tag) }
                            activity.setLanguage(tag)
                            Notifier.createChannels(context)
                        }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.s_theme))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(ThemeMode.LIGHT to R.string.s_light, ThemeMode.DARK to R.string.s_dark, ThemeMode.SYSTEM to R.string.s_system)
                        .forEachIndexed { i, (m, label) ->
                            SegmentedButton(s.theme == m, { app.settings.update { it.copy(theme = m) } },
                                SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
                        }
                }
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.s_colors))
                PalettePicker(s.palette) { p -> app.settings.update { it.copy(palette = p) } }
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.counter_style))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("ring" to R.string.counter_ring, "pull" to R.string.counter_pull).forEachIndexed { i, (v, label) ->
                        SegmentedButton(s.counterStyle == v, { app.settings.update { it.copy(counterStyle = v) } },
                            SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.counter_size))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(R.string.counter_large, R.string.counter_medium, R.string.counter_slim, R.string.counter_hidden).forEachIndexed { i, label ->
                        SegmentedButton(s.counterSize == i, { app.settings.update { it.copy(counterSize = i) } },
                            SegmentedButtonDefaults.itemShape(i, 4)) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                Text(stringResource(R.string.counter_size_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle(stringResource(R.string.counter_beads), null, s.counterBeads) { v -> app.settings.update { it.copy(counterBeads = v) } }
            }

            Group(stringResource(R.string.s_reading)) {
                Label(stringResource(R.string.s_text_size))
                Text("سُبْحَانَ اللهِ وَبِحَمْدِهِ", style = TextStyle(fontFamily = Amiri, fontSize = (21 * s.fontScale).sp,
                    textDirection = TextDirection.Rtl), modifier = Modifier.fillMaxWidth())
                Slider(s.fontScale, { v -> app.settings.update { it.copy(fontScale = v) } }, valueRange = 0.7f..2.2f)
                Toggle(stringResource(R.string.s_tap_card), stringResource(R.string.s_tap_card_d), s.tapCardToCount) { v -> app.settings.update { it.copy(tapCardToCount = v) } }
                Toggle(stringResource(R.string.s_auto_advance), null, s.autoAdvance) { v -> app.settings.update { it.copy(autoAdvance = v) } }
                Toggle(stringResource(R.string.s_haptics), null, s.haptics) { v -> app.settings.update { it.copy(haptics = v) } }
                Toggle(stringResource(R.string.s_volume), stringResource(R.string.s_volume_d), s.volumeKeys) { v -> app.settings.update { it.copy(volumeKeys = v) } }
                Toggle(stringResource(R.string.s_screen_on), null, s.keepScreenOn) { v -> app.settings.update { it.copy(keepScreenOn = v) } }
                Label(stringResource(R.string.s_day_start, s.dayStartHour))
                Text(stringResource(R.string.s_day_start_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(s.dayStartHour.toFloat(), { v ->
                    app.settings.update { it.copy(dayStartHour = v.toInt()) }
                }, valueRange = 0f..6f, steps = 5, onValueChangeFinished = {
                    ReminderScheduler.scheduleRollover(context); WidgetUpdater.requestUpdate(context)
                })
            }

            val rp by com.werd.app.ocr.OcrStore.get(context).flow.collectAsStateWithLifecycle()
            Group(stringResource(R.string.reader_title), collapsible = true, summary = readerEngineLabel(rp.engine)) { ReaderSection(app) }

            val vp by app.voice.flow.collectAsStateWithLifecycle()
            Group(stringResource(R.string.s_voice), collapsible = true,
                summary = voiceEngineLabel(vp.engine) + " · " + stringResource(R.string.s_voice_summary, "%.2f".format(java.util.Locale.US, s.ttsRate))) {
                VoiceSection(app, tts) {
                    VoicePicker(app, tts)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.s_install_voice)) }
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.s_tts_settings)) }
                    }
                }
            }

            AiGroup(app)

            PrayerGroup(app)

            Group(stringResource(R.string.s_widgets)) {
                Text(stringResource(R.string.s_widgets_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val awm = AppWidgetManager.getInstance(context)
                if (awm.isRequestPinAppWidgetSupported) {
                    listOf(
                        NextWerdWidgetReceiver::class.java to R.string.widget_next_name,
                        TodayWidgetReceiver::class.java to R.string.widget_today_name,
                        StreakWidgetReceiver::class.java to R.string.widget_streak_name,
                    ).forEach { (cls, label) ->
                        OutlinedButton(onClick = { awm.requestPinAppWidget(ComponentName(context, cls), null, null) },
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.s_add_widget, stringResource(label))) }
                    }
                }
            }

            ServerGroup(app)

            Group(stringResource(R.string.s_backup)) {
                Text(stringResource(R.string.s_backup_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("werd-backup-${java.time.LocalDate.now()}.json") }) {
                        Text(stringResource(R.string.s_export))
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                        Text(stringResource(R.string.s_import))
                    }
                }
            }

            Group(stringResource(R.string.s_about)) {
                Text("ورد · Werd ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.s_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirmImport?.let { json ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text(stringResource(R.string.s_import_q)) },
            text = { Text(stringResource(R.string.s_import_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    scope.launch {
                        val ok = runCatching { Backup.import(app.db.dao(), json) }.isSuccess
                        if (ok) { ReminderScheduler.syncAll(context); WidgetUpdater.updateNow(context) }
                        Toast.makeText(context, if (ok) importOk else importFail, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.s_import)) }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Group(title: String, collapsible: Boolean = false, summary: String? = null, content: @Composable () -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(!collapsible) }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, LocalExtra.current.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (collapsible) {
                Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(title)
                        if (!open && summary != null) Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Icon(
                        if (open) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore,
                        stringResource(if (open) R.string.collapse else R.string.expand))
                }
                androidx.compose.animation.AnimatedVisibility(open) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Spacer(Modifier.height(4.dp)); content() }
                }
            } else {
                SectionTitle(title)
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onChange)
    }
}

@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun PrayerGroup(app: WerdApp) {
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickMethod by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val names = context.resources.getStringArray(R.array.prayer_names)
    val failMsg = stringResource(R.string.location_failed)

    fun resync() = scope.launch { ReminderScheduler.syncAll(context); WidgetUpdater.requestUpdate(context) }

    fun locate() {
        locating = true
        val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                com.google.android.gms.tasks.CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc == null) { locating = false; Toast.makeText(context, failMsg, Toast.LENGTH_SHORT).show(); return@addOnSuccessListener }
                    scope.launch {
                        val place = withContext(Dispatchers.IO) {
                            runCatching {
                                @Suppress("DEPRECATION")
                                android.location.Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)
                                    ?.firstOrNull()?.let { a -> a.locality ?: a.subAdminArea ?: a.adminArea }
                            }.getOrNull()
                        } ?: String.format(java.util.Locale.US, "%.3f, %.3f", loc.latitude, loc.longitude)
                        app.settings.update { it.copy(prayerLat = loc.latitude, prayerLng = loc.longitude, prayerPlace = place) }
                        locating = false
                        resync()
                    }
                }
                .addOnFailureListener { locating = false; Toast.makeText(context, failMsg, Toast.LENGTH_SHORT).show() }
        }.onFailure { locating = false }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locate()
    }

    Group(stringResource(R.string.s_prayer)) {
        Text(stringResource(R.string.s_prayer_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.s_location))
                Text(s.prayerPlace.ifEmpty { stringResource(R.string.makkah) }, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(enabled = !locating, onClick = {
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (fine) locate() else permLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION))
            }) { Text(stringResource(if (locating) R.string.locating else R.string.use_my_location)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.s_method))
                Text(stringResource(com.werd.app.ui.reminders.methodLabel(s.method)), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { pickMethod = true }) { Text(stringResource(R.string.change)) }
        }
        Toggle(stringResource(R.string.s_asr_hanafi), null, s.asrHanafi) { v -> app.settings.update { it.copy(asrHanafi = v) }; resync() }
        val times = remember(s.prayerLat, s.prayerLng, s.prayerMethod, s.asrHanafi) { s.prayerTimes(java.time.LocalDate.now()) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            com.werd.app.util.PrayerTimes.Prayer.entries.forEach { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(names[p.ordinal], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(times[p]?.let { com.werd.app.ui.reminders.timeText(it.hour, it.minute) } ?: "–",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (pickMethod) {
        AlertDialog(
            onDismissRequest = { pickMethod = false },
            title = { Text(stringResource(R.string.s_method)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    com.werd.app.util.PrayerTimes.Method.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pickMethod = false
                                app.settings.update { it.copy(prayerMethod = m.name) }; resync()
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(s.method == m, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(com.werd.app.ui.reminders.methodLabel(m)))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickMethod = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val SAMPLE = "سُبْحَانَ اللهِ وَبِحَمْدِهِ، سُبْحَانَ اللهِ الْعَظِيمِ"

@Composable
private fun VoicePicker(app: WerdApp, tts: TtsController) {
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val st by tts.state.collectAsStateWithLifecycle()

    if (st.engines.size > 1) {
        Label(stringResource(R.string.s_engine))
        st.engines.forEach { (pkg, label) ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (pkg != st.engine) app.settings.update { it.copy(ttsEngine = pkg, ttsVoice = "") }
                }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(st.engine == pkg, null)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    Label(stringResource(R.string.s_voice_pick))
    if (st.ready && st.voices.isEmpty()) {
        Text(stringResource(R.string.no_arabic_voices), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val options = listOf<TtsController.VoiceOption?>(null) + st.voices
    options.forEachIndexed { i, v ->
        val selected = (v?.name ?: "") == s.ttsVoice || (v == null && st.voices.none { it.name == s.ttsVoice })
        Row(
            Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(ttsVoice = v?.name ?: "") } }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected, null)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(if (v == null) stringResource(R.string.voice_default) else stringResource(R.string.voice_n, i),
                    style = MaterialTheme.typography.bodyLarge)
                if (v != null) {
                    val tags = listOfNotNull(
                        when (v.gender) { "m" -> stringResource(R.string.voice_male); "f" -> stringResource(R.string.voice_female); else -> null },
                        v.country.ifEmpty { null },
                        stringResource(when { v.needsDownload -> R.string.voice_download; v.offline -> R.string.voice_offline; else -> R.string.voice_online }),
                    )
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            androidx.compose.material3.IconButton(onClick = {
                tts.setVoice(s.ttsRate, s.ttsPitch); tts.preview(SAMPLE, v?.name ?: "")
            }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                    stringResource(R.string.voice_preview), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Library & family server: the address (normally built in) and the name others see. */
@Composable
private fun ServerGroup(app: WerdApp) {
    val c by app.cloud.store.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var address by remember(c.serverOverride) { mutableStateOf(c.serverOverride) }
    var remote by remember(c.altServers) { mutableStateOf(c.alternates.joinToString(", ")) }
    var name by remember(c.displayName) { mutableStateOf(c.displayName) }
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<com.werd.app.cloud.CloudRepo.Probe>>(emptyList()) }
    val reasons = mapOf(
        "timeout" to stringResource(R.string.cloud_r_timeout),
        "refused" to stringResource(R.string.cloud_r_refused),
        "unknown_host" to stringResource(R.string.cloud_r_unknown_host),
        "tls" to stringResource(R.string.cloud_r_tls),
        "cleartext_blocked" to stringResource(R.string.cloud_r_cleartext),
        "not_werd" to stringResource(R.string.cloud_r_not_werd),
    )
    val otherReason = stringResource(R.string.cloud_r_other)
    Group(stringResource(R.string.cloud_server)) {
        Text(stringResource(R.string.cloud_server_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(address, { address = it.trim(); results = emptyList() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.cloud_address)) },
            placeholder = { Text(com.werd.app.BuildConfig.SERVER_URL.ifBlank { "192.168.1.20:8080" }) })
        OutlinedTextField(remote, { remote = it; results = emptyList() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.cloud_address_remote)) },
            placeholder = { Text("100.101.102.103:8080") })
        Text(stringResource(R.string.cloud_address_remote_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.cloud_your_name)) })
        AccountCard(app)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !checking, onClick = {
                val typedAlt = remote.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                app.cloud.store.update {
                    it.copy(serverOverride = address, altServers = typedAlt.joinToString(","),
                        activeServer = if (address != it.serverOverride) "" else it.activeServer)
                }
                checking = true; results = emptyList()
                scope.launch {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (name != c.displayName) runCatching { app.cloud.setDisplayName(name) }
                        val list = (listOf(address.ifBlank { com.werd.app.BuildConfig.SERVER_URL }) + typedAlt).filter { it.isNotBlank() }.distinct()
                        val probes = list.map { async { app.cloud.probe(it) } }.awaitAll()
                        // keep the form that answered (e.g. http:// when https:// was typed for a plain-http server)
                        app.cloud.store.update { st ->
                            fun fix(t: String) = probes.firstOrNull { it.typed == t }?.url ?: t
                            st.copy(serverOverride = if (address.isBlank()) "" else fix(address),
                                altServers = typedAlt.map { fix(it) }.joinToString(","),
                                activeServer = probes.firstOrNull { it.url != null }?.url ?: st.activeServer)
                        }
                        // the server may know more addresses of itself (its Tailscale one)
                        if (probes.any { it.url != null }) {
                            runCatching { app.cloud.learnAddresses() }
                            // the phone gets its account now, so it shows on the server's admin page straight away
                            runCatching { app.cloud.ensureAccount(name.ifBlank { c.displayName }) }
                        }
                        results = probes
                    }
                    checking = false
                }
            }) { Text(stringResource(R.string.cloud_save_check)) }
            if (checking) Text(stringResource(R.string.cloud_checking), style = MaterialTheme.typography.bodySmall)
        }
        results.forEach { r ->
            val ok = r.url != null
            Text((if (ok) "✓ " else "✗ ") + (r.url ?: normalizeShown(r.typed)) + " — " +
                (if (ok) stringResource(R.string.ai_ok) + " · v" + r.version
                 else reasons[r.reason] ?: (otherReason + " " + r.reason.removePrefix("other:"))),
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        if (c.configured && c.currentUrl.isNotBlank() && c.candidates.size > 1)
            Text(stringResource(R.string.cloud_using, c.currentUrl), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.configured && c.insecure) Text(stringResource(R.string.cloud_insecure), style = MaterialTheme.typography.bodySmall,
            color = LocalExtra.current.gold)
        // the server's own pages, in the browser
        if (c.configured && c.currentUrl.isNotBlank()) {
            val ctx = LocalContext.current
            androidx.compose.runtime.LaunchedEffect(c.currentUrl) { com.werd.app.cloud.ServerPages.updateShortcut(ctx, c.currentUrl) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = { com.werd.app.cloud.ServerPages.open(ctx, c.currentUrl, "/admin") }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Dashboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.server_dashboard))
                }
                OutlinedButton(onClick = { com.werd.app.cloud.ServerPages.open(ctx, c.currentUrl, "/") }) { Text(stringResource(R.string.server_home)) }
            }
        }
    }
}

private fun normalizeShown(s: String) = com.werd.app.cloud.normalizeUrl(s)

@Composable
private fun AiGroup(app: WerdApp) {
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val claude = s.aiProvider == com.werd.app.ai.AiClient.CLAUDE
    val okMsg = stringResource(R.string.ai_ok)
    val noModelsMsg = stringResource(R.string.ai_no_models)
    val defaultModel = if (claude) com.werd.app.ai.AiClient.DEFAULT_CLAUDE_MODEL else com.werd.app.ai.AiClient.DEFAULT_GEMINI_MODEL

    fun client() = com.werd.app.ai.AiClient.create(s.aiProvider, s.aiKey, if (claude) s.aiModelClaude else s.aiModelGemini)

    Group(stringResource(R.string.ai_title)) {
        Text(stringResource(R.string.ai_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Label(stringResource(R.string.ai_provider))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(com.werd.app.ai.AiClient.GEMINI to "Gemini", com.werd.app.ai.AiClient.CLAUDE to "Claude").forEachIndexed { i, (p, label) ->
                SegmentedButton(s.aiProvider == p, { app.settings.update { it.copy(aiProvider = p) }; status = null; models = null },
                    SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        Text(stringResource(if (claude) R.string.ai_claude_note else R.string.ai_gemini_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.OutlinedTextField(
            value = s.aiKey,
            onValueChange = { v -> app.settings.update { if (claude) it.copy(aiKeyClaude = v.trim()) else it.copy(aiKeyGemini = v.trim()) }; status = null },
            label = { Text(stringResource(R.string.ai_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showKey = !showKey }) {
                    androidx.compose.material3.Icon(if (showKey) androidx.compose.material.icons.Icons.Rounded.VisibilityOff
                        else androidx.compose.material.icons.Icons.Rounded.Visibility, null)
                }
            },
        )
        TextButton(onClick = {
            val url = if (claude) "https://platform.claude.com/" else "https://aistudio.google.com/apikey"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text(stringResource(if (claude) R.string.ai_get_key_claude else R.string.ai_get_key_gemini)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.ai_model))
                Text((if (claude) s.aiModelClaude else s.aiModelGemini).ifBlank { defaultModel }, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(enabled = s.aiReady && !busy, onClick = {
                busy = true; status = null
                scope.launch {
                    runCatching { client().listModels() }
                        .onSuccess { models = it; if (it.isEmpty()) status = noModelsMsg }
                        .onFailure { status = it.message }
                    busy = false
                }
            }) { Text(stringResource(R.string.change)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = s.aiReady && !busy, onClick = {
                busy = true; status = null
                scope.launch {
                    status = runCatching { client().generate("Reply with the single word OK.", maxTokens = 64).text.trim() }
                        .fold({ "$okMsg ($it)" }, { it.message ?: it.javaClass.simpleName })
                    busy = false
                }
            }) { Text(stringResource(R.string.ai_test)) }
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (it.startsWith(okMsg)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Toggle(stringResource(R.string.ai_scan), stringResource(R.string.ai_scan_d), s.aiScan) { v -> app.settings.update { it.copy(aiScan = v) } }
        Toggle(stringResource(R.string.src_auto_group), stringResource(R.string.src_auto_group_d), s.autoGroup) { v -> app.settings.update { it.copy(autoGroup = v) } }
        Text(stringResource(R.string.ai_privacy, if (claude) "Anthropic" else "Google"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    models?.let { list ->
        AlertDialog(
            onDismissRequest = { models = null },
            title = { Text(stringResource(R.string.ai_model)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable {
                            app.settings.update { if (claude) it.copy(aiModelClaude = m) else it.copy(aiModelGemini = m) }; models = null
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(m == (if (claude) s.aiModelClaude else s.aiModelGemini).ifBlank { defaultModel }, null)
                            Spacer(Modifier.width(8.dp)); Text(m)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { models = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}


/** The colour themes, each shown as its ground with a short string of its beads. */
@Composable
private fun PalettePicker(current: String, onPick: (String) -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val names = mapOf("STANDARD" to R.string.pal_standard, "MISBAHA" to R.string.pal_misbaha, "DAWN" to R.string.pal_dawn, "CLASSIC" to R.string.pal_classic)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.werd.app.ui.theme.Palette.entries.forEach { p ->
            val ex = com.werd.app.ui.theme.extraFor(p, false)
            val on = current == p.name
            val ground = com.werd.app.ui.theme.groundFor(p)
            Surface(onClick = { onPick(p.name) }, shape = RoundedCornerShape(18.dp), color = ground,
                border = androidx.compose.foundation.BorderStroke(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).height(96.dp)) {
                Column {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().weight(1f).padding(10.dp)) {
                        com.werd.app.ui.components.BeadString(total = 5, done = 3, current = 3, bead = 8.dp, gap = 3.dp,
                            fill = ex.bead, empty = ex.beadEmpty, ring = ex.beadCurrent)
                    }
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().background(ex.hero).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(stringResource(names.getValue(p.name)), color = ex.onHero, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}
