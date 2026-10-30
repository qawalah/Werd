package com.werd.app.reminder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.werd.app.WerdApp
import com.werd.app.data.ReminderType
import com.werd.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block] off the main thread while keeping the receiver alive. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try { block() } catch (_: Throwable) { } finally { pending.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            ReminderScheduler.ACTION_ROLLOVER -> {
                WidgetUpdater.updateNow(context)
                ReminderScheduler.scheduleRollover(context)
            }
            else -> {
                val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER, -1)
                val dao = WerdApp.from(context).db.dao()
                val r = dao.reminder(id) ?: return@async
                ReminderScheduler.fire(context, r, ReminderScheduler.describeReason(context, r), respectCooldown = false)
                ReminderScheduler.scheduleOne(context, r)
            }
        }
    }
}

class SnoozeReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_WERD = "werd"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_NOTIF = "notif"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val werd = intent.getLongExtra(EXTRA_WERD, -1)
        val rem = intent.getLongExtra(EXTRA_REMINDER, -1)
        val nid = intent.getIntExtra(EXTRA_NOTIF, -1)
        if (nid >= 0) androidx.core.app.NotificationManagerCompat.from(context).cancel(nid)
        else Notifier.cancel(context, werd)
        if (rem > 0) ReminderScheduler.snooze(context, rem)
    }
}

class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        reschedule(context)
    }

    private fun reschedule(context: Context) = async {
        ReminderScheduler.syncAll(context)
        WidgetUpdater.updateNow(context)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        val event = GeofencingEvent.fromIntent(intent) ?: return@async
        if (event.hasError()) return@async
        val t = event.geofenceTransition
        if (t != Geofence.GEOFENCE_TRANSITION_ENTER && t != Geofence.GEOFENCE_TRANSITION_EXIT) return@async
        val dao = WerdApp.from(context).db.dao()
        event.triggeringGeofences.orEmpty().forEach { g ->
            val r = g.requestId.toLongOrNull()?.let { dao.reminder(it) } ?: return@forEach
            if (r.type != ReminderType.LOCATION) return@forEach
            ReminderScheduler.fire(context, r, ReminderScheduler.describeReason(context, r), respectCooldown = true)
        }
    }
}

/** Fired by ConnectivityManager whenever Wi-Fi networks change; we act on "newly connected to SSID X". */
class NetworkReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) = async {
        val app = WerdApp.from(context)
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val raw = runCatching { wm.connectionInfo?.ssid }.getOrNull().orEmpty()
        val ssid = raw.trim('"').takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
        val last = app.settings.getString("last_ssid")
        if (ssid == last) return@async
        app.settings.putString("last_ssid", ssid)
        if (ssid.isEmpty()) return@async
        app.db.dao().reminders()
            .filter { it.enabled && it.type == ReminderType.WIFI && it.ssid.trim().equals(ssid, ignoreCase = true) }
            .forEach { ReminderScheduler.fire(context, it, ReminderScheduler.describeReason(context, it), respectCooldown = true) }
    }
}

class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address ?: return
        async {
            WerdApp.from(context).db.dao().reminders()
                .filter { it.enabled && it.type == ReminderType.BLUETOOTH && it.btAddress.equals(address, ignoreCase = true) }
                .forEach { ReminderScheduler.fire(context, it, ReminderScheduler.describeReason(context, it), respectCooldown = true) }
        }
    }
}
