package com.werd.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Astronomical prayer-time calculation (the classic PrayTimes.org algorithm), offline, no dependencies.
 * Used for "N minutes after Maghrib"-style reminders.
 */
object PrayerTimes {

    enum class Prayer { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

    /** Fajr angle, and either an Isha angle or a fixed number of minutes after Maghrib. */
    enum class Method(val fajr: Double, val ishaAngle: Double?, val ishaMinutes: Int?) {
        UMM_AL_QURA(18.5, null, 90),
        MWL(18.0, 17.0, null),
        EGYPT(19.5, 17.5, null),
        KARACHI(18.0, 18.0, null),
        ISNA(15.0, 15.0, null),
        DUBAI(18.2, 18.2, null),
        KUWAIT(18.0, 17.5, null),
        QATAR(18.0, null, 90),
        SINGAPORE(20.0, 18.0, null),
        TURKEY(18.0, 17.0, null),
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = deg(asin(x))
    private fun darccos(x: Double) = deg(acos(x))
    private fun darctan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun darccot(x: Double) = deg(atan(1 / x))
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(a: Double) = a - 24.0 * floor(a / 24.0)

    private fun julian(date: LocalDate): Double {
        var y = date.year
        var m = date.monthValue
        val d = date.dayOfMonth
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** (declination, equation of time) */
    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    /** Times for [date] as local date-times; null where the sun never reaches the angle. */
    fun compute(
        date: LocalDate, lat: Double, lng: Double, method: Method, hanafi: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<Prayer, LocalDateTime?> {
        val jDate = julian(date) - lng / (15.0 * 24.0)
        val tz = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0

        fun midDay(t: Double) = fixHour(12 - sunPosition(jDate + t).second)
        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPosition(jDate + t).first
            val noon = midDay(t)
            val v = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            if (v.isNaN() || v < -1 || v > 1) return Double.NaN
            val tt = darccos(v) / 15.0
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Int, t: Double): Double {
            val decl = sunPosition(jDate + t).first
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return sunAngleTime(angle, t, false)
        }

        // Two passes: the second uses the first pass' results as better day-fraction estimates.
        var h = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        repeat(2) {
            val t = DoubleArray(6) { i -> (if (h[i].isNaN()) doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)[i] else h[i]) / 24.0 }
            val fajr = sunAngleTime(method.fajr, t[0], true)
            val sunrise = sunAngleTime(0.833, t[1], true)
            val dhuhr = midDay(t[2])
            val asr = asrTime(if (hanafi) 2 else 1, t[3])
            val sunset = sunAngleTime(0.833, t[4], false)
            val isha = method.ishaAngle?.let { sunAngleTime(it, t[5], false) } ?: (sunset + (method.ishaMinutes ?: 90) / 60.0)
            h = doubleArrayOf(fajr, sunrise, dhuhr, asr, sunset, isha)
        }

        // High-latitude fallback (angle-based night portions).
        val night = 24 - (h[4] - h[1])
        if (!night.isNaN()) {
            val fajrPortion = method.fajr / 60.0 * night
            if (h[0].isNaN() || (h[1] - h[0]) > fajrPortion) h[0] = h[1] - fajrPortion
            if (method.ishaAngle != null) {
                val ishaPortion = method.ishaAngle / 60.0 * night
                if (h[5].isNaN() || (h[5] - h[4]) > ishaPortion) h[5] = h[4] + ishaPortion
            }
        }

        val adjust = tz - lng / 15.0
        val start = date.atStartOfDay()
        return Prayer.entries.associateWith { p ->
            val v = h[p.ordinal]
            if (v.isNaN()) null else start.plusSeconds(((v + adjust) * 3600.0).roundToLong()).let {
                // round to the nearest minute
                if (it.second >= 30) it.plusMinutes(1).withSecond(0) else it.withSecond(0)
            }
        }
    }
}
