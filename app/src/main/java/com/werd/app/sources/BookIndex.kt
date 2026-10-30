package com.werd.app.sources

import com.werd.app.data.SourceItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The book's own table of contents (فهرس): read from its contents pages, shown above the book's supplications,
 * each entry a link to the supplication it names.
 *
 * Page numbers in a فهرس are the printed ones, which often differ from the scan's page numbers (cover, front matter):
 * entries matched by title give the offset, which then links the rest by page.
 */
object BookIndex {
    /** [item]: the id of the supplication (SourceItem) it opens, null when not linked. */
    data class Entry(val title: String, val page: Int, val item: Long? = null)

    fun encode(entries: List<Entry>): String = if (entries.isEmpty()) "" else JSONArray().apply {
        entries.forEach { e -> put(JSONObject().put("title", e.title).put("page", e.page).apply { e.item?.let { put("item", it) } }) }
    }.toString()

    fun decode(json: String): List<Entry> {
        if (json.isBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("title").trim()
            if (t.isEmpty()) null else Entry(t, o.optInt("page", 0), if (o.has("item") && !o.isNull("item")) o.optLong("item") else null)
        }
    }

    // ---------------------------------------------------------------- reading a contents page (from its text)

    private fun latinDigits(s: String) = buildString {
        for (c in s) append(when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c })
    }

    private val FILL = "[\\s.\\-–—…_·•ـ:]"
    private val NUM_END = Regex("^(.*?\\p{L}.*?)$FILL*[\\(\\[]?(\\d{1,4})[\\)\\]]?\\s*$")
    private val NUM_START = Regex("^\\s*[\\(\\[]?(\\d{1,4})[\\)\\]]?$FILL+(.*\\p{L}.*)$")
    private val HEADERS = listOf("الفهرس", "فهرس", "المحتويات", "فهرس المحتويات", "الموضوع", "الموضوعات", "الصفحة", "الصفحه", "رقم الصفحة", "ص")

    /** Lines of a page that look like contents entries: "title ....... 12" (or the number first). */
    fun parseText(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (raw in latinDigits(text).replace("\r", "").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val (title, page) = NUM_END.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
                ?: NUM_START.find(line)?.let { it.groupValues[2] to it.groupValues[1] } ?: continue
            val t = title.trim().trim('.', '-', '–', '…', '_', '·', 'ـ', ':', ' ').replace(Regex("\\s+"), " ")
            val p = page.toIntOrNull() ?: continue
            if (t.count { it.isLetter() } < 3 || t in HEADERS || p <= 0 || p > 3000) continue
            out += t to p
        }
        return out
    }

    /** A contents page: most of its lines are entries, and there are enough of them. */
    fun looksLikeIndex(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { l -> l.count { it.isLetter() } >= 3 }
        val entries = parseText(text)
        if (entries.size < 4 || entries.size < lines.size * 0.45) return false
        // page numbers mostly go up (a list of verses or a numbered dhikr would not)
        val ups = entries.zipWithNext().count { (a, b) -> b.second >= a.second }
        return ups >= (entries.size - 1) * 0.7
    }

    /** Entries from a page's text, or nothing when it isn't a contents page. */
    fun fromPage(text: String): List<Entry> = if (looksLikeIndex(text)) parseText(text).map { (t, p) -> Entry(t, p) } else emptyList()

    /** Adds [more] to [have], dropping repeats (the same contents page read twice). */
    fun merge(have: List<Entry>, more: List<Entry>): List<Entry> {
        val keys = have.map { key(it.title) to it.page }.toHashSet()
        return have + more.filter { keys.add(key(it.title) to it.page) }
    }

    /** The pages a فهرس is usually on: the first and the last few (0-based). */
    fun candidatePages(pageCount: Int): List<Int> =
        ((0 until minOf(8, pageCount)) + (maxOf(0, pageCount - 8) until pageCount)).distinct()

    // ---------------------------------------------------------------- linking entries to supplications

    private fun bare(s: String) = OfflineExtract.bare(s).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun key(s: String) = bare(s).replace(" ", "")
    private fun words(s: String) = bare(s).split(' ').filter { it.length > 1 && it !in STOP }.toSet()
    private val STOP = setOf("في", "من", "على", "الى", "عن", "او", "ما", "ذكر", "دعاء", "باب", "فصل")

    private fun similar(a: String, b: String): Double {
        val ka = key(a); val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        if (ka.length >= 4 && kb.length >= 4 && (ka.contains(kb) || kb.contains(ka))) return 0.9
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return (wa intersect wb).size.toDouble() / (wa union wb).size
    }

    /**
     * Links each entry to a supplication: by title first, then by page (with the offset between the printed
     * numbers and the scan's pages learned from the title matches). Entries linked by hand ([Entry.item] set and
     * still existing) are kept.
     */
    fun link(entries: List<Entry>, items: List<SourceItem>): List<Entry> {
        if (items.isEmpty()) return entries.map { it.copy(item = null) }
        val ids = items.map { it.id }.toSet()
        val byTitle = entries.map { e ->
            if (e.item != null && e.item in ids) return@map e.item
            items.maxByOrNull { similar(e.title, it.title) }?.takeIf { similar(e.title, it.title) >= 0.6 }?.id
        }
        val offsets = entries.indices.mapNotNull { i ->
            val id = byTitle[i] ?: return@mapNotNull null
            items.first { it.id == id }.page - entries[i].page
        }.sorted()
        val offset = if (offsets.isEmpty()) 0 else offsets[offsets.size / 2]
        val sorted = items.sortedBy { it.position }
        return entries.mapIndexed { i, e ->
            val id = byTitle[i] ?: run {
                val target = e.page + offset
                // the first supplication starting on that page, or just after it
                sorted.firstOrNull { it.page == target }?.id ?: sorted.firstOrNull { it.page in target..target + 1 }?.id
            }
            e.copy(item = id)
        }
    }
}
