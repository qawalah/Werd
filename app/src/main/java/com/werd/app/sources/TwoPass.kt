package com.werd.app.sources

import com.werd.app.data.SourceItem

/**
 * Two-pass scan: each page is read twice — once without tashkeel (plain letters, by the built-in reader) and once with
 * tashkeel (by the chosen reader) — and the two are compared letter by letter with the tashkeel removed. Two different
 * readers rarely make the same mistake, so a word they disagree on is likely misread in one of them.
 *
 * Words that agree are trusted; an item whose words all agree is ticked as proofread. An item with differences
 * keeps both readings so the proofread view can show them side by side.
 */
object TwoPass {
    const val SINGLE = 0
    const val TWO = 1
    const val DOUBTFUL = 2

    private val WS = Regex("\\s+")

    /** The words of a text (anything with a letter in it). */
    fun words(s: String): List<String> = s.split(WS).filter { w -> w.any { it.isLetter() } }

    /** A word with tashkeel, punctuation and letter variants removed, for comparing. */
    fun bareWord(w: String): String = OfflineExtract.bare(w).filter { it.isLetterOrDigit() }

    /** For each word of [a], whether it is matched (in order) by a word of [b] — a longest common subsequence. */
    fun matched(a: List<String>, b: List<String>): BooleanArray {
        val n = a.size; val m = b.size
        val out = BooleanArray(n)
        if (n == 0 || m == 0) return out
        // dp over a × b; pages are a few hundred words, so this stays small
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    data class Result(val diff: Set<Int>, val alt: String)

    /**
     * Compares an item's text (from the reading with tashkeel) with the other reading of its page.
     * [Result.diff]: indexes of the item's words the other reading doesn't have; [Result.alt]: the other reading's
     * words for the same stretch of the page (empty when they agree).
     */
    fun compare(itemText: String, otherPage: String): Result {
        val iw = words(itemText)
        val ow = words(otherPage)
        val a = iw.map(::bareWord)
        val b = ow.map(::bareWord)
        val m = matched(a, b)
        val diff = iw.indices.filter { !m[it] && a[it].isNotEmpty() }.toSet()
        if (diff.isEmpty()) return Result(emptySet(), "")
        // the other reading's stretch: from the word matching the item's first agreed word to the last one
        val mb = matched(b, a)
        val first = b.indices.firstOrNull { mb[it] && a.contains(b[it]) }
        val last = b.indices.lastOrNull { mb[it] && a.contains(b[it]) }
        val alt = if (first == null || last == null || last < first) "" else {
            val pad = 2
            ow.subList((first - pad).coerceAtLeast(0), (last + pad + 1).coerceAtMost(ow.size)).joinToString(" ")
        }
        return Result(diff, alt)
    }

    /** Marks an item with the comparison: proofread when both readings agree, flagged (with the other reading) when not. */
    fun annotate(item: SourceItem, otherPage: String): SourceItem {
        val r = compare(item.text, otherPage)
        return if (r.diff.isEmpty()) item.copy(checked = true, diffWords = "", alt = "")
        else item.copy(checked = false, diffWords = r.diff.sorted().joinToString(","), alt = r.alt.take(4000))
    }

    /**
     * A reading that looks doubtful: few Arabic letters among its characters, very short or very long words,
     * or almost nothing on a page that should have text.
     */
    fun doubtful(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters < 25) return true
        val arabic = text.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }
        val odd = text.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in ".,،؛:!?؟«»()[]{}\"'-–—…ـ﴾﴿*/•·" && it !in '؀'..'ۿ' }
        if (arabic < letters * 0.85) return true
        if (odd > text.length * 0.04) return true
        val ws = words(text).map(::bareWord).filter { it.isNotEmpty() }
        if (ws.isEmpty()) return true
        val avg = ws.sumOf { it.length }.toDouble() / ws.size
        if (avg < 2.3 || avg > 8.5) return true
        // many one-letter "words" = a broken line
        return ws.count { it.length == 1 } > ws.size * 0.18
    }
}
