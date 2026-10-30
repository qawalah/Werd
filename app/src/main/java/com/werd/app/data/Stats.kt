package com.werd.app.data

import com.werd.app.util.Days

object Stats {
    /** Consecutive due days completed, ending today (if done) or yesterday. Non-due days are skipped. */
    fun streak(mask: Int, completed: Set<Long>, today: Long): Int {
        if (mask == 0) return 0
        var d = if (today in completed || !Days.isInMask(mask, today)) today else today - 1
        var n = 0
        var guard = 0
        while (guard++ < 3660) {
            if (Days.isInMask(mask, d)) {
                if (d in completed) n++ else break
            }
            d--
        }
        return n
    }

    fun bestStreak(mask: Int, completed: Set<Long>, from: Long, today: Long): Int {
        if (mask == 0 || completed.isEmpty()) return 0
        var best = 0
        var run = 0
        var d = from
        while (d <= today) {
            if (Days.isInMask(mask, d)) {
                if (d in completed) { run++; if (run > best) best = run }
                else if (d != today) run = 0
            }
            d++
        }
        return best
    }

    /** Share of due days completed within the last [days] days (today excluded if still pending). */
    fun rate(mask: Int, completed: Set<Long>, today: Long, days: Int, since: Long): Float {
        var due = 0
        var done = 0
        for (i in 0 until days) {
            val d = today - i
            if (d < since) break
            if (!Days.isInMask(mask, d)) continue
            if (d == today && d !in completed) continue
            due++
            if (d in completed) done++
        }
        return if (due == 0) 0f else done.toFloat() / due
    }
}
