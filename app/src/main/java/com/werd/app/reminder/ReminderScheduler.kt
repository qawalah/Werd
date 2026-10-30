package com.werd.app.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.excludes
import com.werd.app.localized
import com.werd.app.util.Days
import com.werd.app.util.PrayerTimes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object ReminderScheduler {
    const val ACTION_ALARM = "com.werd.app.ALARM"
    const val ACTION_ROLLOVER = "com.werd.app.ROLLOVER"
    const val EXTRA_REMINDER = "reminder_id"
    private const val ROLLOVER_CODE = -77

    // ------------------------------------------------------------------ time maths

    /** Time-of-day this reminder fires on [date], for day-based types (DAILY, PRAYER, INTERVAL, ONCE). */
    fun occurrenceOn(r: Reminder, date: LocalDate): LocalDateTime? = when (r.type) {
        ReminderType.DAILY -> if (Days.maskHas(r.daysMask, date.dayOfWeek)) date.atTime(r.hour, r.minute) else null
        ReminderType.PRAYER -> if (!Days.maskHas(r.daysMask, date.dayOfWeek)) null else {
            val p = PrayerTimes.Prayer.entries.getOrElse(r.prayer) { PrayerTimes.Prayer.MAGHRIB }
            WerdApp.instance.settings.value.prayerTimes(date)[p]?.plusMinutes(r.offsetMin.toLong())
        }
        ReminderType.INTERVAL -> {
            val start = r.dateValue ?: date.toEpochDay()
            val d = date.toEpochDay()
            if (d >= start && (d - start) % r.intervalDays.coerceAtLeast(1) == 0L) date.atTime(r.hour, r.minute) else null
        }
        ReminderType.ONCE -> r.dateValue?.let { formatTime(it) }?.takeIf { it.toLocalDate() == date }
        else -> null
    }

    fun nextTrigger(r: Reminder, now: LocalDateTime = LocalDateTime.now()): Long? {
        val zone = ZoneId.systemDefault()
        fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
        return when (r.type) {
            ReminderType.PRAYER -> {
                if (r.daysMask == 0) return null
                for (i in -1..8) {
                    val t = occurrenceOn(r, now.toLocalDate().plusDays(i.toLong())) ?: continue
                    if (t.isAfter(now)) return ms(t)
                }
                null
            }
            ReminderType.DAILY -> {
                if (r.daysMask == 0) return null
                for (i in 0..7) {
                    val date = now.toLocalDate().plusDays(i.toLong())
                    if (!Days.maskHas(r.daysMask, date.dayOfWeek)) continue
                    val t = date.atTime(r.hour, r.minute)
                    if (t.isAfter(now)) return ms(t)
                }
                null
            }
            ReminderType.ONCE -> r.dateValue?.takeIf { it > System.currentTimeMillis() }
            ReminderType.INTERVAL -> {
                val start = LocalDate.ofEpochDay(r.dateValue ?: now.toLocalDate().toEpochDay())
                val n = r.intervalDays.coerceAtLeast(1).toLong()
                var date = if (start.isAfter(now.toLocalDate())) start else {
                    val diff = now.toLocalDate().toEpochDay() - start.toEpochDay()
                    start.plusDays((diff / n) * n)
                }
                repeat(3) {
                    val t = date.atTime(r.hour, r.minute)
                    if (t.isAfter(now)) return ms(t)
                    date = date.plusDays(n)
                }
                null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ alarms

    private fun alarmIntent(context: Context, id: Long): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("werd://reminder/$id"))
            .putExtra(EXTRA_REMINDER, id)
        return PendingIntent.getBroadcast(context, id.toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    @SuppressLint("MissingPermission")
    fun setAlarm(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelAlarms(context: Context, ids: List<Long>) {
        val am = context.getSystemService(AlarmManager::class.java)
        ids.forEach { am.cancel(alarmIntent(context, it)) }
    }

    fun scheduleOne(context: Context, r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, r.id)
        am.cancel(pi)
        if (!r.enabled || !r.type.isTime) return
        nextTrigger(r)?.let { setAlarm(context, it, pi) }
    }

    fun snooze(context: Context, reminderId: Long, minutes: Int = 15) {
        setAlarm(context, System.currentTimeMillis() + minutes * 60_000L, alarmIntent(context, reminderId))
    }

    /** Wakes the app at the start of the next logical day to refresh widgets. */
    fun scheduleRollover(context: Context) {
        val startHour = WerdApp.from(context).settings.value.dayStartHour
        val now = LocalDateTime.now()
        var t = now.toLocalDate().atTime(startHour, 0, 30)
        if (!t.isAfter(now)) t = t.plusDays(1)
        val i = Intent(context, AlarmReceiver::class.java).setAction(ACTION_ROLLOVER)
        val pi = PendingIntent.getBroadcast(context, ROLLOVER_CODE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(AlarmManager::class.java)
        am.set(AlarmManager.RTC_WAKEUP, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    suspend fun syncAll(context: Context) {
        val dao = WerdApp.from(context).db.dao()
        dao.reminders().forEach { runCatching { scheduleOne(context, it) } }
        runCatching { scheduleRollover(context) }
        runCatching { syncTriggers(context) }
    }

    // ------------------------------------------------------------------ geofences & connections

    private fun has(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun geofenceIntent(context: Context): PendingIntent {
        val i = Intent(context, GeofenceReceiver::class.java).setAction("com.werd.app.GEOFENCE")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 9001, i, flags)
    }

    private fun networkIntent(context: Context): PendingIntent {
        val i = Intent(context, NetworkReceiver::class.java).setAction("com.werd.app.NETWORK")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 9002, i, flags)
    }

    @SuppressLint("MissingPermission")
    suspend fun syncTriggers(context: Context) {
        val all = WerdApp.from(context).db.dao().reminders().filter { it.enabled }

        // Location (GPS / network / Wi-Fi positioning via fused provider). Devices without Google Play
        // services simply skip place reminders instead of crashing.
        runCatching { syncGeofences(context, all) }

        // Wi-Fi network connection
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val nPi = networkIntent(context)
        runCatching { cm.unregisterNetworkCallback(nPi) }
        if (all.any { it.type == ReminderType.WIFI }) {
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            runCatching { cm.registerNetworkCallback(req, nPi) }
        }
        // Bluetooth is a manifest receiver; nothing to register.
    }

    @SuppressLint("MissingPermission")
    private fun syncGeofences(context: Context, all: List<Reminder>) {
        val client = LocationServices.getGeofencingClient(context)
        val gPi = geofenceIntent(context)
        runCatching { client.removeGeofences(gPi) }
        val fences = all.filter { it.type == ReminderType.LOCATION && it.lat != null && it.lng != null }
        if (fences.isNotEmpty() && has(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            val list = fences.take(90).map { r ->
                Geofence.Builder()
                    .setRequestId(r.id.toString())
                    .setCircularRegion(r.lat!!, r.lng!!, r.radius.coerceAtLeast(100f))
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(if (r.onExit) Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER)
                    .setNotificationResponsiveness(60_000)
                    .build()
            }
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(list).build()
            runCatching { client.addGeofences(req, gPi) }
        }
    }

    // ------------------------------------------------------------------ firing

    /** Shared gate for every reminder source: enabled, cooldown, "skip if already done today". */
    suspend fun fire(context: Context, r: Reminder, reason: String, respectCooldown: Boolean) {
        val app = WerdApp.from(context)
        val dao = app.db.dao()
        if (!r.enabled) return
        val now = System.currentTimeMillis()
        if (respectCooldown && now - r.lastFired < r.cooldownHours * 3_600_000L) return
        val groupId = r.groupId
        if (groupId != null) {
            // one notification for the whole group, listing what is still to recite
            val group = dao.group(groupId) ?: return
            val today = app.repo.today()
            val werds = dao.werdsInGroup(groupId).filter { !r.excludes(it.id) && Days.isInMask(it.goalDaysMask, today) }
            val pending = werds.filter { !app.repo.isCompletedToday(it.id) }
            val list = if (r.skipIfDone) pending else werds
            if (list.isEmpty()) return
            Notifier.showGroup(context, group, r.id, list.map { it.title }, (pending.firstOrNull() ?: list.first()).id, reason)
        } else {
            val werdId = r.werdId ?: return
            val werd = dao.werd(werdId) ?: return
            if (r.skipIfDone && app.repo.isCompletedToday(werdId)) return
            val first = dao.items(werdId).firstOrNull()?.text.orEmpty()
            Notifier.show(context, werd.id, r.id, werd.title, first.take(140), reason)
        }
        dao.setLastFired(r.id, now)
    }

    fun describeReason(context: Context, r: Reminder): String {
        val c = context.localized()
        return when (r.type) {
            ReminderType.LOCATION -> c.getString(if (r.onExit) R.string.reason_left else R.string.reason_arrived,
                r.placeName.ifEmpty { c.getString(R.string.place_default) })
            ReminderType.WIFI -> c.getString(R.string.reason_wifi, r.ssid)
            ReminderType.PRAYER -> prayerPhrase(c, r.prayer, r.offsetMin)
            ReminderType.BLUETOOTH -> c.getString(R.string.reason_bt, r.btName.ifEmpty { r.btAddress })
            else -> ""
        }
    }

    /** "15 min after Maghrib" / "At Fajr" / "10 min before Isha". */
    fun prayerPhrase(c: Context, prayer: Int, offset: Int): String {
        val name = c.resources.getStringArray(R.array.prayer_names).getOrElse(prayer) { "" }
        return when {
            offset == 0 -> c.getString(R.string.at_prayer, name)
            offset > 0 -> c.getString(R.string.after_prayer, offset, name)
            else -> c.getString(R.string.before_prayer, -offset, name)
        }
    }

    fun formatTime(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
}
