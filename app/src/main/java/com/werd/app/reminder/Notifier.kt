package com.werd.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.werd.app.R
import com.werd.app.isArabicUi
import com.werd.app.localized
import com.werd.app.ui.MainActivity

object Notifier {
    const val CHANNEL = "werd_reminders"
    const val EXTRA_WERD = "open_werd"

    fun createChannels(context: Context) {
        val c = context.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, c.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        ch.description = c.getString(R.string.channel_reminders_desc)
        nm.createNotificationChannel(ch)
    }

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun openWerdIntent(context: Context, werdId: Long): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction("com.werd.app.OPEN_$werdId")
            .putExtra(EXTRA_WERD, werdId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, werdId.toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, werdId: Long, reminderId: Long, title: String, firstLine: String, reason: String) {
        if (!canNotify(context)) return
        val c = context.localized()
        val snooze = Intent(context, SnoozeReceiver::class.java)
            .setAction("com.werd.app.SNOOZE_$reminderId")
            .putExtra(SnoozeReceiver.EXTRA_WERD, werdId)
            .putExtra(SnoozeReceiver.EXTRA_REMINDER, reminderId)
        val snoozePi = PendingIntent.getBroadcast(context, reminderId.toInt(), snooze,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0E6B4E.toInt())
            .setContentTitle(c.getString(R.string.notif_title, title))
            .setContentText(reason.ifEmpty { firstLine })
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (reason.isNotEmpty()) "$reason\n$firstLine" else firstLine))
            .setContentIntent(openWerdIntent(context, werdId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, c.getString(R.string.notif_start), openWerdIntent(context, werdId))
            .addAction(0, c.getString(R.string.notif_snooze), snoozePi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(werdId.toInt(), n)
        } catch (_: SecurityException) { }
    }

    fun cancel(context: Context, werdId: Long) = NotificationManagerCompat.from(context).cancel(werdId.toInt())

    fun groupNotificationId(groupId: Long) = 1_000_000 + groupId.toInt()

    /** One notification for a group reminder: "Evening awrad — 2 remaining: …". Tapping opens the first pending werd. */
    fun showGroup(context: Context, group: com.werd.app.data.WerdGroup, reminderId: Long, titles: List<String>, openWerdId: Long, reason: String) {
        if (!canNotify(context)) return
        val c = context.localized()
        val nid = groupNotificationId(group.id)
        val snooze = Intent(context, SnoozeReceiver::class.java)
            .setAction("com.werd.app.SNOOZE_$reminderId")
            .putExtra(SnoozeReceiver.EXTRA_NOTIF, nid)
            .putExtra(SnoozeReceiver.EXTRA_REMINDER, reminderId)
        val snoozePi = PendingIntent.getBroadcast(context, reminderId.toInt(), snooze,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val summary = c.getString(R.string.notif_group_text, titles.size, titles.joinToString("، ".takeIf { c.isArabicUi() } ?: ", "))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0E6B4E.toInt())
            .setContentTitle(c.getString(R.string.notif_group_title, group.name))
            .setContentText(reason.ifEmpty { summary })
            .setStyle(NotificationCompat.InboxStyle().also { st ->
                if (reason.isNotEmpty()) st.setSummaryText(reason)
                titles.take(6).forEach { st.addLine("• $it") }
            })
            .setContentIntent(openWerdIntent(context, openWerdId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, c.getString(R.string.notif_start), openWerdIntent(context, openWerdId))
            .addAction(0, c.getString(R.string.notif_snooze), snoozePi)
            .build()
        try { NotificationManagerCompat.from(context).notify(nid, n) } catch (_: SecurityException) { }
    }
}
