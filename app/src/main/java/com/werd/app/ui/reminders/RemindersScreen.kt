package com.werd.app.ui.reminders

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.werd.app.data.excludes
import androidx.compose.material.icons.rounded.Mosque
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.ALL_DAYS
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.reminder.Notifier
import com.werd.app.reminder.ReminderScheduler
import com.werd.app.ui.components.OrnateHeader
import com.werd.app.ui.components.currentLocale
import com.werd.app.ui.components.dayShort
import com.werd.app.ui.theme.LocalExtra
import com.werd.app.util.Days
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun typeIcon(t: ReminderType): ImageVector = when (t) {
    ReminderType.DAILY -> Icons.Rounded.Schedule
    ReminderType.PRAYER -> Icons.Rounded.Mosque
    ReminderType.ONCE -> Icons.Rounded.CalendarMonth
    ReminderType.INTERVAL -> Icons.Rounded.EventRepeat
    ReminderType.LOCATION -> Icons.Rounded.LocationOn
    ReminderType.WIFI -> Icons.Rounded.Wifi
    ReminderType.BLUETOOTH -> Icons.Rounded.Bluetooth
}

@Composable
fun timeText(h: Int, m: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(currentLocale()).format(LocalTime.of(h, m))

@Composable
fun reminderTitle(r: Reminder): String {
    val locale = currentLocale()
    return when (r.type) {
        ReminderType.DAILY -> {
            val days = when (r.daysMask) {
                ALL_DAYS -> stringResource(R.string.every_day)
                else -> Days.weekOrder(locale).filter { Days.maskHas(r.daysMask, it) }.map { dayShort(it) }.joinToString("، ".takeIf { locale.language == "ar" } ?: ", ")
            }
            "$days · ${timeText(r.hour, r.minute)}"
        }
        ReminderType.PRAYER -> {
            val days = if (r.daysMask == ALL_DAYS) stringResource(R.string.every_day)
            else Days.weekOrder(locale).filter { Days.maskHas(r.daysMask, it) }.map { dayShort(it) }
                .joinToString(if (locale.language == "ar") "، " else ", ")
            ReminderScheduler.prayerPhrase(LocalContext.current, r.prayer, r.offsetMin) + " · " + days
        }
        ReminderType.ONCE -> r.dateValue?.let {
            val dt = ReminderScheduler.formatTime(it)
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(dt)
        } ?: stringResource(R.string.type_once)
        ReminderType.INTERVAL -> stringResource(R.string.every_n_days, r.intervalDays) + " · " + timeText(r.hour, r.minute)
        ReminderType.LOCATION -> stringResource(if (r.onExit) R.string.leaving_place else R.string.arriving_place,
            r.placeName.ifEmpty { stringResource(R.string.place_default) }, r.radius.toInt())
        ReminderType.WIFI -> stringResource(R.string.wifi_connected_to, r.ssid)
        ReminderType.BLUETOOTH -> stringResource(R.string.bt_connected_to, r.btName.ifEmpty { r.btAddress })
    }
}

@Composable
fun ReminderSummary(r: Reminder, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(reminderTitle(r), style = MaterialTheme.typography.titleSmall,
            color = if (r.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        val sub = if (r.type.isTime) {
            ReminderScheduler.nextTrigger(r)?.takeIf { r.enabled }?.let {
                val dt = ReminderScheduler.formatTime(it)
                val day = when (dt.toLocalDate()) {
                    LocalDate.now() -> stringResource(R.string.today)
                    LocalDate.now().plusDays(1) -> stringResource(R.string.tomorrow)
                    else -> DateTimeFormatter.ofPattern("EEE d MMM", currentLocale()).format(dt)
                }
                stringResource(R.string.next_at, "$day ${timeText(dt.hour, dt.minute)}")
            } ?: if (r.enabled) stringResource(R.string.no_upcoming) else stringResource(R.string.disabled)
        } else stringResource(R.string.once_per_hours, r.cooldownHours)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun RemindersScreen(app: WerdApp, onEdit: (Long, Long) -> Unit, onEditGroup: (Long, Long) -> Unit = { _, _ -> }) {
    val dash by app.repo.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var pickWerd by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val reminders = dash?.reminders.orEmpty()
    val werds = dash?.werds.orEmpty()
    val x = LocalExtra.current

    // permission health, re-evaluated on resume
    val canNotify = remember(tick) { Notifier.canNotify(context) }
    val exact = remember(tick) { ReminderScheduler.canExact(context) }
    val battery = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val hasLocationRem = reminders.any { it.enabled && it.type == ReminderType.LOCATION }
    val hasWifiRem = reminders.any { it.enabled && it.type == ReminderType.WIFI }
    val bgLoc = remember(tick) { Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

    val fabLabel = stringResource(R.string.add_reminder)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item {
                OrnateHeader {
                    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(20.dp)) {
                        Text(stringResource(R.string.tab_reminders), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(stringResource(R.string.reminders_sub), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
            if (!canNotify) item {
                HealthCard(Icons.Rounded.NotificationsOff, stringResource(R.string.perm_notif), stringResource(R.string.allow)) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            if (!exact && reminders.any { it.enabled && it.type.isTime }) item {
                HealthCard(Icons.Rounded.Alarm, stringResource(R.string.perm_exact), stringResource(R.string.allow)) {
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            if ((hasLocationRem || hasWifiRem) && !bgLoc) item {
                HealthCard(Icons.Rounded.LocationOn, stringResource(R.string.perm_bg_location), stringResource(R.string.allow)) {
                    if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            if (!battery && reminders.any { it.enabled }) item {
                HealthCard(Icons.Rounded.BatteryAlert, stringResource(R.string.perm_battery), stringResource(R.string.fix)) {
                    runCatching {
                        @Suppress("BatteryLife")
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            if (reminders.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    com.werd.app.ui.components.BeadString(total = 3, done = 0, current = 0, bead = 14.dp, gap = 8.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.no_reminders), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.no_reminders_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // group reminders first: they cover several awrad
            val byGroup = reminders.filter { it.groupId != null }.groupBy { it.groupId!! }
            dash?.groups.orEmpty().filter { byGroup.containsKey(it.id) }.forEach { g ->
                item(key = "g${g.id}") {
                    Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp), tint = x.gold)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.group_label, g.name), style = MaterialTheme.typography.titleSmall, color = x.gold)
                    }
                }
                items(byGroup.getValue(g.id), key = { "r${it.id}" }) { r ->
                    Surface(
                        onClick = { onEditGroup(g.id, r.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, x.cardBorder),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(typeIcon(r.type), null, tint = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                ReminderSummary(r)
                                val n = werds.count { it.werd.groupId == g.id && !r.excludes(it.werd.id) }
                                Text(stringResource(R.string.applies_to_n, n), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(r.enabled, { on -> scope.launch { app.repo.setReminderEnabled(r.id, on) } })
                        }
                    }
                }
            }
            val byWerd = reminders.filter { it.werdId != null }.groupBy { it.werdId }
            werds.filter { byWerd.containsKey(it.werd.id) }.forEach { w ->
                item(key = "w${w.werd.id}") {
                    Text(w.werd.title, style = MaterialTheme.typography.titleSmall, color = x.gold,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp))
                }
                items(byWerd.getValue(w.werd.id), key = { "r${it.id}" }) { r ->
                    Surface(
                        onClick = { onEdit(w.werd.id, r.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, x.cardBorder),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(typeIcon(r.type), null, tint = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(12.dp))
                            ReminderSummary(r, Modifier.weight(1f))
                            Switch(r.enabled, { on -> scope.launch { app.repo.setReminderEnabled(r.id, on) } })
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { pickWerd = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.add_reminder)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.BottomRight).padding(18.dp)
                .semantics { contentDescription = fabLabel },
        )
    }

    if (pickWerd) {
        AlertDialog(
            onDismissRequest = { pickWerd = false },
            title = { Text(stringResource(R.string.pick_werd)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (werds.isEmpty()) Text(stringResource(R.string.empty_title))
                    val groups = dash?.groups.orEmpty()
                    if (groups.isNotEmpty()) {
                        Text(stringResource(R.string.for_group), style = MaterialTheme.typography.labelMedium, color = x.gold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        groups.forEach { g ->
                            Row(Modifier.fillMaxWidth().clickable { pickWerd = false; onEditGroup(g.id, 0) }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                        Text(stringResource(R.string.for_werd), style = MaterialTheme.typography.labelMedium, color = x.gold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                    werds.forEach { w ->
                        Text(w.werd.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clickable { pickWerd = false; onEdit(w.werd.id, 0) }.padding(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickWerd = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun HealthCard(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = LocalExtra.current.gold, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
    Spacer(Modifier.height(0.dp))
}
