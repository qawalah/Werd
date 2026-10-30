package com.werd.app

import com.werd.app.util.PrayerTimes
import com.werd.app.util.PrayerTimes.Prayer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PrayerTimesTest {
    private fun near(expected: String, actual: java.time.LocalDateTime?, tolMin: Long = 4) {
        val a = actual!!.toLocalTime()
        val d = Duration.between(LocalTime.parse(expected), a).abs().toMinutes()
        assertTrue("expected ~$expected got $a", d <= tolMin)
    }

    @Test fun makkahUmmAlQura() {
        // Published Umm al-Qura timetable, Makkah, 1 Jan 2024: 05:39 06:59 12:25 15:30 17:52 19:22
        val t = PrayerTimes.compute(LocalDate.of(2024, 1, 1), 21.4225, 39.8262,
            PrayerTimes.Method.UMM_AL_QURA, false, ZoneId.of("Asia/Riyadh"))
        near("05:39", t[Prayer.FAJR]); near("06:59", t[Prayer.SUNRISE]); near("12:25", t[Prayer.DHUHR])
        near("15:30", t[Prayer.ASR]); near("17:52", t[Prayer.MAGHRIB]); near("19:22", t[Prayer.ISHA])
        println("Makkah 2024-01-01: $t")
    }

    @Test fun riyadhToday() {
        val t = PrayerTimes.compute(LocalDate.of(2026, 10, 13), 24.7136, 46.6753,
            PrayerTimes.Method.UMM_AL_QURA, false, ZoneId.of("Asia/Riyadh"))
        println("Riyadh 2026-10-13: $t")
        assertTrue(t.values.all { it != null })
        val order = Prayer.entries.map { t[it]!! }
        assertTrue(order.zipWithNext().all { (a, b) -> a.isBefore(b) })
    }

    @Test fun hanafiAsrIsLater() {
        val d = LocalDate.of(2026, 6, 1)
        val s = PrayerTimes.compute(d, 31.5, 74.3, PrayerTimes.Method.KARACHI, false, ZoneId.of("Asia/Karachi"))
        val h = PrayerTimes.compute(d, 31.5, 74.3, PrayerTimes.Method.KARACHI, true, ZoneId.of("Asia/Karachi"))
        assertTrue(h[Prayer.ASR]!!.isAfter(s[Prayer.ASR]!!.plusMinutes(30)))
    }
}
