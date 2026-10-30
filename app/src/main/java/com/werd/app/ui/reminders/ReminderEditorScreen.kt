package com.werd.app.ui.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.ALL_DAYS
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.excludes
import com.werd.app.data.withExcluded
import com.werd.app.ui.components.CountStepper
import com.werd.app.ui.components.DayChips
import com.werd.app.ui.components.SectionTitle
import com.werd.app.ui.components.currentLocale
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderEditorScreen(app: WerdApp, werdId: Long, reminderId: Long, onBack: () -> Unit, groupId: Long = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val x = LocalExtra.current
    var r by remember {
        val now = LocalDateTime.now().plusHours(1)
        mutableStateOf(Reminder(werdId = werdId, hour = now.hour, minute = 0,
            dateValue = null))
    }
    var werdTitle by remember { mutableStateOf("") }
    var groupWerds by remember { mutableStateOf<List<com.werd.app.data.Werd>>(emptyList()) }
    var showTime by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }

    LaunchedEffect(werdId, reminderId) {
        werdTitle = if (groupId != 0L) app.db.dao().group(groupId)?.name.orEmpty()
        else app.db.dao().werd(werdId)?.title.orEmpty()
        if (groupId != 0L) groupWerds = app.db.dao().werdsInGroup(groupId)
        if (reminderId != 0L) app.db.dao().reminder(reminderId)?.let { r = it }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    val hasFine = remember(permTick) { granted(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    val hasBg = remember(permTick) { Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val hasBt = remember(permTick) { Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT) }

    val errLoc = stringResource(R.string.err_location)
    val errWifi = stringResource(R.string.err_wifi)
    val errBt = stringResource(R.string.err_bt)
    val errDate = stringResource(R.string.err_date)
    val errDays = stringResource(R.string.err_days)

    fun save() {
        when (r.type) {
            ReminderType.LOCATION -> if (r.lat == null) { error = errLoc; return }
            ReminderType.WIFI -> if (r.ssid.isBlank()) { error = errWifi; return }
            ReminderType.BLUETOOTH -> if (r.btAddress.isBlank()) { error = errBt; return }
            ReminderType.ONCE -> if ((r.dateValue ?: 0) < System.currentTimeMillis()) { error = errDate; return }
            ReminderType.DAILY, ReminderType.PRAYER -> if (r.daysMask == 0) { error = errDays; return }
            else -> Unit
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {
            app.repo.saveReminder(r.copy(
                werdId = werdId.takeIf { it != 0L && groupId == 0L },
                groupId = groupId.takeIf { it != 0L },
                lastFired = if (r.id == 0L) 0 else r.lastFired,
            ))
            withContext(Dispatchers.Main.immediate) { onBack() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(if (reminderId == 0L) R.string.add_reminder else R.string.edit_reminder))
                        Text(if (groupId != 0L) stringResource(R.string.group_label, werdTitle) else werdTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (reminderId != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
                    TextButton(onClick = ::save) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle(stringResource(R.string.remind_when))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReminderType.entries.forEach { t ->
                    FilterChip(
                        selected = r.type == t,
                        onClick = {
                            r = r.copy(type = t, dateValue = when (t) {
                                ReminderType.ONCE -> r.dateValue?.takeIf { r.type == ReminderType.ONCE }
                                    ?: LocalDateTime.now().plusHours(1).withMinute(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                ReminderType.INTERVAL -> r.dateValue?.takeIf { r.type == ReminderType.INTERVAL } ?: LocalDate.now().toEpochDay()
                                else -> r.dateValue
                            })
                        },
                        label = { Text(stringResource(typeLabel(t))) },
                        leadingIcon = { Icon(typeIcon(t), null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }

            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, x.cardBorder)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (r.type) {
                        ReminderType.DAILY -> {
                            TimeRow(r.hour, r.minute) { showTime = true }
                            Text(stringResource(R.string.on_days), style = MaterialTheme.typography.labelLarge)
                            DayChips(r.daysMask, { r = r.copy(daysMask = it) })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Preset(stringResource(R.string.every_day), r.daysMask == ALL_DAYS) { r = r.copy(daysMask = ALL_DAYS) }
                                Preset(stringResource(R.string.fridays), r.daysMask == 0b0010000) { r = r.copy(daysMask = 0b0010000) }
                                Preset(stringResource(R.string.mon_thu), r.daysMask == 0b0001001) { r = r.copy(daysMask = 0b0001001) }
                            }
                        }
                        ReminderType.PRAYER -> PrayerSection(app, r) { r = it }
                        ReminderType.ONCE -> {
                            val dt = r.dateValue?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
                                ?: LocalDateTime.now().plusHours(1)
                            DateRow(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(currentLocale()).format(dt)) { showDate = true }
                            TimeRow(dt.hour, dt.minute) { showTime = true }
                        }
                        ReminderType.INTERVAL -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.every_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                CountStepper(r.intervalDays, { r = r.copy(intervalDays = it.coerceIn(1, 365)) })
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.days_unit))
                            }
                            val start = LocalDate.ofEpochDay(r.dateValue ?: LocalDate.now().toEpochDay())
                            DateRow(stringResource(R.string.starting) + " " +
                                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale()).format(start)) { showDate = true }
                            TimeRow(r.hour, r.minute) { showTime = true }
                        }
                        ReminderType.LOCATION -> {
                            if (!hasFine) {
                                PermissionNote(stringResource(R.string.need_location)) {
                                    locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            }
                            LocationPicker(
                                lat = r.lat, lng = r.lng, radius = r.radius, hasPermission = hasFine,
                                onPick = { la, lo -> r = r.copy(lat = la, lng = lo) },
                            )
                            Text(stringResource(R.string.radius_m, r.radius.toInt()), style = MaterialTheme.typography.labelLarge)
                            Slider(r.radius, { r = r.copy(radius = (it / 50f).toInt() * 50f) }, valueRange = 100f..2000f)
                            OutlinedTextField(r.placeName, { r = r.copy(placeName = it) }, label = { Text(stringResource(R.string.place_name)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(!r.onExit, { r = r.copy(onExit = false) }, SegmentedButtonDefaults.itemShape(0, 2)) {
                                    Text(stringResource(R.string.on_arrive))
                                }
                                SegmentedButton(r.onExit, { r = r.copy(onExit = true) }, SegmentedButtonDefaults.itemShape(1, 2)) {
                                    Text(stringResource(R.string.on_leave))
                                }
                            }
                            if (hasFine && !hasBg) PermissionNote(stringResource(R.string.need_bg_location)) {
                                if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                            CooldownRow(r.cooldownHours) { r = r.copy(cooldownHours = it) }
                        }
                        ReminderType.WIFI -> {
                            Text(stringResource(R.string.wifi_explain), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(r.ssid, { r = r.copy(ssid = it) }, label = { Text(stringResource(R.string.wifi_name)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Rounded.Wifi, null) })
                            OutlinedButton(onClick = {
                                if (!hasFine) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                else currentSsid(context)?.let { r = r.copy(ssid = it) }
                            }) { Text(stringResource(R.string.use_current_wifi)) }
                            if (!hasBg) PermissionNote(stringResource(R.string.need_bg_location_wifi)) {
                                if (!hasFine) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                                else if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                            CooldownRow(r.cooldownHours) { r = r.copy(cooldownHours = it) }
                        }
                        ReminderType.BLUETOOTH -> {
                            Text(stringResource(R.string.bt_explain), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!hasBt) {
                                PermissionNote(stringResource(R.string.need_bt)) {
                                    if (Build.VERSION.SDK_INT >= 31) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                            } else {
                                val devices = remember(permTick) { bondedDevices(context) }
                                if (devices.isEmpty()) Text(stringResource(R.string.no_paired), style = MaterialTheme.typography.bodySmall)
                                devices.forEach { (name, addr) ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { r = r.copy(btAddress = addr, btName = name) }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(r.btAddress.equals(addr, true), { r = r.copy(btAddress = addr, btName = name) })
                                        Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(name, style = MaterialTheme.typography.bodyLarge)
                                            Text(addr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            CooldownRow(r.cooldownHours) { r = r.copy(cooldownHours = it) }
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, x.cardBorder)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    SwitchRow(stringResource(R.string.enabled), r.enabled) { r = r.copy(enabled = it) }
                    SwitchRow(stringResource(if (groupId != 0L) R.string.skip_if_group_done else R.string.skip_if_done), r.skipIfDone) { r = r.copy(skipIfDone = it) }
                }
            }
            if (groupId != 0L && groupWerds.isNotEmpty()) {
                SectionTitle(stringResource(R.string.applies_to))
                Text(stringResource(R.string.applies_to_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, x.cardBorder)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        groupWerds.forEach { w ->
                            val on = !r.excludes(w.id)
                            Row(Modifier.fillMaxWidth().clickable { r = r.withExcluded(w.id, on) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(on, { r = r.withExcluded(w.id, !it) })
                                Text(w.title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showTime) {
        val initial = if (r.type == ReminderType.ONCE && r.dateValue != null)
            Instant.ofEpochMilli(r.dateValue!!).atZone(ZoneId.systemDefault()).toLocalDateTime().let { it.hour to it.minute }
        else r.hour to r.minute
        val st = rememberTimePickerState(initial.first, initial.second, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    showTime = false
                    r = if (r.type == ReminderType.ONCE) {
                        val dt = Instant.ofEpochMilli(r.dateValue ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
                            .toLocalDate().atTime(st.hour, st.minute)
                        r.copy(dateValue = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), hour = st.hour, minute = st.minute)
                    } else r.copy(hour = st.hour, minute = st.minute)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = st) },
        )
    }
    if (showDate) {
        val initialMs = when (r.type) {
            ReminderType.ONCE -> Instant.ofEpochMilli(r.dateValue ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
            else -> LocalDate.ofEpochDay(r.dateValue ?: LocalDate.now().toEpochDay())
        }.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ds = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    showDate = false
                    val ms = ds.selectedDateMillis ?: return@TextButton
                    val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    r = if (r.type == ReminderType.ONCE) {
                        val t = Instant.ofEpochMilli(r.dateValue ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalTime()
                        r.copy(dateValue = date.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    } else r.copy(dateValue = date.toEpochDay())
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = ds) }
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_reminder_q)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { app.repo.deleteReminder(reminderId); withContext(Dispatchers.Main.immediate) { onBack() } } }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

fun typeLabel(t: ReminderType): Int = when (t) {
    ReminderType.DAILY -> R.string.type_daily
    ReminderType.PRAYER -> R.string.type_prayer
    ReminderType.ONCE -> R.string.type_once
    ReminderType.INTERVAL -> R.string.type_interval
    ReminderType.LOCATION -> R.string.type_location
    ReminderType.WIFI -> R.string.type_wifi
    ReminderType.BLUETOOTH -> R.string.type_bluetooth
}

@Composable
private fun TimeRow(h: Int, m: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.time), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(timeText(h, m), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DateRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Preset(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun CooldownRow(hours: Int, onChange: (Int) -> Unit) {
    Column {
        Text(stringResource(R.string.once_per_hours, hours), style = MaterialTheme.typography.labelLarge)
        Slider(hours.toFloat(), { onChange(it.toInt().coerceIn(1, 24)) }, valueRange = 1f..24f, steps = 22)
    }
}

@Composable
private fun PermissionNote(text: String, onGrant: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onGrant) { Text(stringResource(R.string.allow)) }
        }
    }
}

@SuppressLint("MissingPermission")
private fun currentSsid(context: Context): String? {
    val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    @Suppress("DEPRECATION")
    val s = wm.connectionInfo?.ssid?.trim('"') ?: return null
    return s.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<Pair<String, String>> = runCatching {
    val bm = context.getSystemService(BluetoothManager::class.java)
    bm.adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
}.getOrDefault(emptyList())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrayerSection(app: WerdApp, r: Reminder, onChange: (Reminder) -> Unit) {
    val context = LocalContext.current
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val names = context.resources.getStringArray(R.array.prayer_names)
    Text(stringResource(R.string.prayer), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEachIndexed { i, n ->
            FilterChip(selected = r.prayer == i, onClick = { onChange(r.copy(prayer = i)) }, label = { Text(n) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
        }
    }
    Text(stringResource(R.string.offset_label), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val mode = when { r.offsetMin < 0 -> 0; r.offsetMin == 0 -> 1; else -> 2 }
        listOf(R.string.offset_before, R.string.offset_at, R.string.offset_after).forEachIndexed { i, label ->
            SegmentedButton(mode == i, {
                val mag = kotlin.math.abs(r.offsetMin).takeIf { it > 0 } ?: 15
                onChange(r.copy(offsetMin = when (i) { 0 -> -mag; 1 -> 0; else -> mag }))
            }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
        }
    }
    if (r.offsetMin != 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.minutes_unit), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            CountStepper(kotlin.math.abs(r.offsetMin), { v ->
                val m = v.coerceIn(1, 180)
                onChange(r.copy(offsetMin = if (r.offsetMin < 0) -m else m))
            })
        }
    }
    Text(stringResource(R.string.on_days), style = MaterialTheme.typography.labelLarge)
    DayChips(r.daysMask, { onChange(r.copy(daysMask = it)) })
    val today = remember(settings, r.prayer, r.offsetMin) {
        val p = com.werd.app.util.PrayerTimes.Prayer.entries[r.prayer.coerceIn(0, 5)]
        settings.prayerTimes(LocalDate.now())[p]
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (today != null) {
                val fire = today.plusMinutes(r.offsetMin.toLong())
                Text(stringResource(R.string.prayer_today_preview, names[r.prayer.coerceIn(0, 5)],
                    timeText(today.hour, today.minute), timeText(fire.hour, fire.minute)),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.prayer_location_note,
                settings.prayerPlace.ifEmpty { stringResource(R.string.makkah) },
                stringResource(methodLabel(settings.method))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun methodLabel(m: com.werd.app.util.PrayerTimes.Method): Int = when (m) {
    com.werd.app.util.PrayerTimes.Method.UMM_AL_QURA -> R.string.m_umm_al_qura
    com.werd.app.util.PrayerTimes.Method.MWL -> R.string.m_mwl
    com.werd.app.util.PrayerTimes.Method.EGYPT -> R.string.m_egypt
    com.werd.app.util.PrayerTimes.Method.KARACHI -> R.string.m_karachi
    com.werd.app.util.PrayerTimes.Method.ISNA -> R.string.m_isna
    com.werd.app.util.PrayerTimes.Method.DUBAI -> R.string.m_dubai
    com.werd.app.util.PrayerTimes.Method.KUWAIT -> R.string.m_kuwait
    com.werd.app.util.PrayerTimes.Method.QATAR -> R.string.m_qatar
    com.werd.app.util.PrayerTimes.Method.SINGAPORE -> R.string.m_singapore
    com.werd.app.util.PrayerTimes.Method.TURKEY -> R.string.m_turkey
}
