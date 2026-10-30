package com.werd.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.Werd
import com.werd.app.data.WerdItem
import com.werd.app.reminder.AlarmReceiver
import com.werd.app.reminder.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderTest {
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()

    private fun werd(): Long = runBlocking {
        app.settings.update { it.copy(onboarded = true, seeded = true) }
        app.repo.saveWerd(Werd(title = "Test"), listOf(WerdItem(werdId = 0, position = 0, text = "سبحان الله", target = 3)))
    }

    @Test fun dailyNextTrigger() {
        val r = Reminder(werdId = 1, type = ReminderType.DAILY, hour = 6, minute = 30)
        val now = LocalDateTime.of(2026, 10, 13, 21, 0)
        val t = ReminderScheduler.formatTime(ReminderScheduler.nextTrigger(r, now)!!)
        assertEquals(LocalDateTime.of(2026, 10, 14, 6, 30), t)
        // Fridays only (bit 4)
        val f = ReminderScheduler.formatTime(ReminderScheduler.nextTrigger(r.copy(daysMask = 0b0010000), now)!!)
        assertEquals(LocalDate.of(2026, 10, 16), f.toLocalDate())
    }

    @Test fun prayerNextTrigger() {
        app.settings.update { it.copy(prayerLat = 24.7136, prayerLng = 46.6753) }
        val r = Reminder(werdId = 1, type = ReminderType.PRAYER, prayer = 4, offsetMin = 20)
        val now = LocalDateTime.of(2026, 10, 13, 12, 0)
        val t = ReminderScheduler.formatTime(ReminderScheduler.nextTrigger(r, now)!!)
        val maghrib = app.settings.value.prayerTimes(LocalDate.of(2026, 10, 13))[com.werd.app.util.PrayerTimes.Prayer.MAGHRIB]!!
        assertEquals(maghrib.plusMinutes(20), t)
    }

    @Test fun alarmScheduledAndNotificationShown() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id = werd()
        val rid = runBlocking { app.repo.saveReminder(Reminder(werdId = id, type = ReminderType.DAILY, hour = 7, minute = 0)) }
        val am = app.getSystemService(AlarmManager::class.java)
        val next = shadowOf(am).scheduledAlarms.firstOrNull { it.triggerAtTime == ReminderScheduler.nextTrigger(Reminder(werdId = id, hour = 7, minute = 0)) }
        assertNotNull("alarm scheduled at the next 07:00", next)

        // fire it
        val intent = Intent(app, AlarmReceiver::class.java).setAction(ReminderScheduler.ACTION_ALARM)
            .putExtra(ReminderScheduler.EXTRA_REMINDER, rid)
        AlarmReceiver().onReceive(app, intent)
        Thread.sleep(1500)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        assertTrue(posted[0].extras.getString("android.title")!!.contains("Test"))

        // completed today -> a reminder with skipIfDone stays quiet
        nm.cancelAll()
        runBlocking { app.repo.setAll(id, true) }
        AlarmReceiver().onReceive(app, intent)
        Thread.sleep(1500)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
