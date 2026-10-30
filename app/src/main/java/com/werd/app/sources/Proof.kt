package com.werd.app.sources

import com.werd.app.data.SourceItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Proofreading an item against other readings of its page (any readers: the built-in one, the server's models, the
 * user's AI) and suggestions from the helpers (known texts, spelling, a small model).
 *
 * Each reading is kept with the reader that made it ([SourceItem.reads]); the item's flagged words ([SourceItem.diffWords])
 * are the words any reading disagrees with. An item every reading agrees with is ticked as proofread, unless the
 * user already ticked or corrected it: then a later reading only adds its suggestions and changes nothing.
 */
object Proof {
    data class Reading(val by: String, val text: String) {
        /** The reader read the same words (nothing to show). */
        val agrees get() = text.isBlank()
    }

    data class Hint(val word: Int, val suggestion: String, val by: String)

    // ------------------------------------------------------------------ stored form

    fun reads(item: SourceItem): List<Reading> {
        val list = parse(item.reads).mapNotNull { o -> o.optString("by").takeIf { it.isNotBlank() }?.let { Reading(it, o.optString("text")) } }
        // books read twice before 1.15 kept one other reading (the built-in one) in [SourceItem.alt]
        return if (list.isEmpty() && item.alt.isNotBlank()) listOf(Reading(Readers.BUILTIN, item.alt)) else list
    }

    fun hints(item: SourceItem): List<Hint> = parse(item.hints).mapNotNull { o ->
        val s = o.optString("s"); if (s.isBlank()) null else Hint(o.optInt("i", -1), s, o.optString("by"))
    }.filter { it.word >= 0 }

    private fun parse(json: String): List<JSONObject> {
        if (json.isBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun encodeReads(list: List<Reading>): String = if (list.isEmpty()) "" else
        JSONArray().apply { list.forEach { put(JSONObject().put("by", it.by).put("text", it.text)) } }.toString()

    fun encodeHints(list: List<Hint>): String = if (list.isEmpty()) "" else
        JSONArray().apply { list.forEach { put(JSONObject().put("i", it.word).put("s", it.suggestion).put("by", it.by)) } }.toString()

    // ------------------------------------------------------------------ adding a reading

    /**
     * Compares [item] with [page], the whole page as reader [by] read it, and keeps that reader's words for the item's
     * stretch of the page. Ticks the item when every reading agrees; never changes an item the user already ticked.
     */
    fun addReading(item: SourceItem, by: String, page: String): SourceItem {
        val r = TwoPass.compare(item.text, page)
        val reading = Reading(by, if (r.diff.isEmpty()) "" else r.alt.ifBlank { page.take(4000) }.take(4000))
        val list = reads(item).filter { it.by != by } + reading
        return settle(item.copy(reads = encodeReads(list), alt = list.lastOrNull { !it.agrees }?.text.orEmpty()))
    }

    /** Recomputes the flagged words from the readings and hints; ticks the item when all readings agree. */
    fun settle(item: SourceItem): SourceItem {
        val list = reads(item)
        val diff = HashSet<Int>()
        list.filter { !it.agrees }.forEach { diff += TwoPass.compare(item.text, it.text).diff }
        hints(item).forEach { diff += it.word }
        if (item.checked) return item.copy(diffWords = "")
        return if (diff.isEmpty() && list.isNotEmpty()) item.copy(checked = true, diffWords = "")
        else item.copy(diffWords = diff.sorted().joinToString(","))
    }

    /** Adds helper suggestions (replacing earlier ones from the same helper kind). */
    fun addHints(item: SourceItem, more: List<Hint>, kind: String): SourceItem {
        val keep = hints(item).filter { !it.by.startsWith(kind) }
        return settle(item.copy(hints = encodeHints(keep + more)))
    }

    // ------------------------------------------------------------------ what to offer for a word

    /** For each word of [text] that [other] reads differently: the other reading's word(s) in its place. */
    fun alternatives(text: String, other: String): Map<Int, String> {
        val iw = TwoPass.words(text); val ow = TwoPass.words(other)
        if (iw.isEmpty() || ow.isEmpty()) return emptyMap()
        val a = iw.map(TwoPass::bareWord); val b = ow.map(TwoPass::bareWord)
        // the matched pairs (i, j), in order
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = 0; var j = 0
        while (i < n && j < m) when {
            a[i] == b[j] -> { pairs += i to j; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
        val out = HashMap<Int, String>()
        // the gaps between matched words: the item's words there are replaced by the other reading's words there
        val anchors = listOf(-1 to -1) + pairs + listOf(n to m)
        for (k in 0 until anchors.size - 1) {
            val (i0, j0) = anchors[k]; val (i1, j1) = anchors[k + 1]
            val gi = (i0 + 1 until i1).toList(); val gj = (j0 + 1 until j1).toList()
            if (gi.isEmpty() || gj.isEmpty()) continue
            // trim the edges of the other reading's stretch to what lies between the anchors on this item's side
            // before the first or after the last matched word, the other reading may run on past this item: keep the near side
            val near = when (k) { 0 -> gj.takeLast(gi.size); anchors.size - 2 -> gj.take(gi.size); else -> gj }
            if (gi.size == near.size) gi.forEachIndexed { x, w -> out[w] = ow[near[x]] }
            else { val joined = near.joinToString(" ") { ow[it] }; if (near.size <= gi.size + 3) gi.forEach { out[it] = joined } }
        }
        return out
    }

    /** Everything to offer for word [word] of [item]: each reader's word and each helper's suggestion, as (text, by). */
    fun offers(item: SourceItem, word: Int): List<Pair<String, String>> {
        val mine = TwoPass.words(item.text).getOrNull(word)?.let(TwoPass::bareWord)
        val out = LinkedHashMap<String, String>()
        reads(item).filter { !it.agrees }.forEach { r -> alternatives(item.text, r.text)[word]?.let { w -> if (TwoPass.bareWord(w) != mine) out.putIfAbsent(w, r.by) } }
        hints(item).filter { it.word == word }.forEach { h -> out.putIfAbsent(h.suggestion, h.by) }
        return out.map { it.key to it.value }
    }

    /** [text] with word [word] replaced by [with] (the item's spacing and punctuation elsewhere kept). */
    fun replaceWord(text: String, word: Int, with: String): String {
        var k = -1
        val parts = text.split(Regex("(?<=\\s)|(?=\\s)"))
        return parts.joinToString("") { p -> if (p.isBlank() || p.none { it.isLetter() }) p else { k++; if (k == word) with else p } }
    }
}
