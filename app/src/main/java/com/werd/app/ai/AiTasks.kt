package com.werd.app.ai

import com.werd.app.data.SourceItem
import org.json.JSONObject

/** The prompts and result parsing for Werd's AI features. */
object AiTasks {

    val OCCASIONS = listOf(
        "morning", "evening", "morning_evening", "after_prayer", "sleep", "waking", "friday",
        "travel", "home", "food", "mosque", "distress", "ramadan", "general",
    )

    // ---------------------------------------------------------------- transcription (editor scan)

    private const val TRANSCRIBE = """Transcribe all of the Arabic text in the image(s) exactly as printed, including every tashkeel mark (harakat, shadda, sukun, tanween).
Put each separate supplication or dhikr on its own line. Keep repetition instructions such as (ثلاثاً) or «٣ مرات» exactly where they appear.
Do not translate, explain, correct, summarise or add anything. Output plain text only."""

    suspend fun transcribe(ai: AiClient, images: List<AiImage>): String =
        ai.generate(TRANSCRIBE, images, json = false, maxTokens = 16_000).text.trim()
            .removePrefix("```").removeSuffix("```").trim()

    // ---------------------------------------------------------------- werd text → tally lines (editor "Convert")

    private const val SPLIT = """Below is the text of an Islamic werd / hizb / ratib (Arabic) that the user will recite with a tally counter.
Split it into recitation units, in order. A unit is a phrase that is recited a number of times before moving on (a dhikr, a verse, a supplication, a salawat).
- "text": the exact words, copied character for character with all tashkeel; do not correct, shorten or add anything. Leave out repetition instructions such as (ثلاثاً) or «٣ مرات» and numbering.
- "count": how many times to recite it as an integer (1 if not stated; ثلاثاً=3, سبعاً=7, مائة مرة=100). A phrase written several times in a row, e.g. «حم حم حم», is one unit with that count.
- Every word of the text must end up in exactly one unit. A heading that is not recited goes in "title" instead.
Respond with JSON only: {"title": "", "lines": [{"text": "...", "count": 1}, ...]}"""

    data class SplitResult(val title: String?, val lines: List<Pair<String, Int>>)

    suspend fun splitWerd(ai: AiClient, text: String): SplitResult {
        val reply = ai.generate(SPLIT + "\n\nTEXT:\n" + text, emptyList(), json = true, maxTokens = 32_000)
        return parseSplit(reply.text)
    }

    fun parseSplit(reply: String): SplitResult {
        val o = AiJson.obj(reply) ?: return SplitResult(null, emptyList())
        val arr = o.optJSONArray("lines") ?: return SplitResult(null, emptyList())
        val lines = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = it.optString("text").trim()
            if (t.none { c -> c.isLetter() }) null else t to it.optInt("count", 1).coerceIn(1, 100_000)
        }
        return SplitResult(o.optString("title").trim().takeIf { it.isNotEmpty() }, lines)
    }

    // ---------------------------------------------------------------- extraction (sources)

    private fun extractPrompt(first: Int, last: Int, lookahead: Boolean, fromText: Boolean): String {
        val what = if (fromText) "part $first of the text of an Islamic book of supplications/adhkar (Arabic)"
        else "pages $first–$last (in order, one image per page) of an Islamic book of supplications/adhkar (Arabic)"
        val look = if (lookahead) "\nThe last image is page ${last + 1}. It is included ONLY so you can finish a supplication that runs over from page $last — do not start new items from it." else ""
        val pageRule = if (fromText) "\"page\": $first." else "\"page\": the page it starts on ($first–$last)."
        return """You are reading $what.$look

Extract every supplication, dhikr, salawat or Quranic passage that the book tells the reader to recite. Skip commentary, introductions, chains of narration and explanations.
Each page may have a running header (the book's or chapter's title, repeated on every page), a page number and a footer: never copy them into an item, and a page number is never a repetition count.

For each item give:
- "title": the heading printed just above this item (e.g. «دعاء الاستيقاظ من النوم»), in Arabic. Never the book's title or the running header at the top of the page. If the item has no heading of its own, its first few words. Items should not all share one title.
- "text": the exact words to recite, copied with full tashkeel as printed. Leave out its heading (it goes in "title"), the repetition instruction (e.g. «ثلاث مرات»), footnote marks such as (١), and narrator lead-ins such as «قال رسول الله ﷺ».
- "section": the chapter or section it belongs to (from the running header or a chapter heading), or "".
- "footnote": the footnote(s) at the bottom of the page that this item's text refers to, copied as printed, or "".
- "count": how many times to recite it, as an integer (ثلاثاً=3, سبع مرات=7, مائة مرة=100), or 0 when the book gives no number (a text read once: a supplication, poem, sermon or passage with no repetition).
- "occasion": exactly one of ${OCCASIONS.joinToString(", ")} — taken from the book's heading or its words about when to say it; "general" if none.
- "occasion_text": the book's own short Arabic phrase about when to say it, or "".
- "note": the stated virtue or reference in a short Arabic phrase, or "".
- $pageRule

If these pages include the book's table of contents (فهرس / المحتويات), also return "index": [{"title": "...", "page": <the page number printed in it>}] with every entry in order; otherwise leave "index" out.

Respond with JSON only: {"items":[ ... ]} in reading order, or {"items":[]} if these pages contain none."""
    }

    data class Extracted(val items: List<SourceItem>, val truncated: Boolean, val index: List<com.werd.app.sources.BookIndex.Entry> = emptyList())

    suspend fun extractFromPages(ai: AiClient, images: List<AiImage>, firstPage: Int, lastPage: Int, lookahead: Boolean): Extracted {
        val reply = ai.generate(extractPrompt(firstPage, lastPage, lookahead, false), images, json = true, maxTokens = 32_000)
        return Extracted(parseItems(reply.text, firstPage, lastPage), reply.truncated, parseIndex(reply.text))
    }

    suspend fun extractFromText(ai: AiClient, text: String, part: Int): Extracted {
        val reply = ai.generate(extractPrompt(part, part, false, true) + "\n\nTEXT:\n" + text, emptyList(), json = true, maxTokens = 32_000)
        return Extracted(parseItems(reply.text, part, part), reply.truncated, parseIndex(reply.text))
    }

    fun parseItems(reply: String, firstPage: Int, lastPage: Int): List<SourceItem> {
        val o: JSONObject = AiJson.obj(reply) ?: return emptyList()
        val arr = o.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = it.optString("text").trim()
            if (text.count { c -> c.isLetter() } < 2) return@mapNotNull null
            SourceItem(
                sourceId = 0, position = 0,
                page = it.optInt("page", firstPage).coerceIn(firstPage, lastPage),
                title = it.optString("title").trim().ifEmpty { text.take(40) }.take(120),
                text = text,
                count = it.optInt("count", 0).coerceIn(1, 100_000),
                textOnly = it.optInt("count", 0) <= 0,
                occasion = it.optString("occasion").trim().lowercase().takeIf { o -> o in OCCASIONS } ?: "general",
                occasionText = it.optString("occasion_text").trim().take(200),
                note = it.optString("note").trim().take(300),
                section = it.optString("section").trim().take(120),
                footnote = it.optString("footnote").trim().take(1000),
            )
        }.let(::placeOnPages)
    }

    /** No positions from the AI: each item's place on its page is guessed from its order there. */
    fun placeOnPages(items: List<SourceItem>): List<SourceItem> {
        val perPage = items.groupBy { it.page }
        return items.map { item ->
            val same = perPage.getValue(item.page)
            val total = same.sumOf { it.text.length + 40 }.coerceAtLeast(1)
            var start = 0
            for (o in same) { if (o === item) break; start += o.text.length + 40 }
            val f = { x: Int -> 0.07f + 0.86f * x / total }
            item.copy(region = String.format(java.util.Locale.ROOT, "%.3f-%.3f", (f(start) - 0.03f).coerceAtLeast(0f), (f(start + item.text.length + 40) + 0.03f).coerceAtMost(1f)))
        }
    }

    fun parseIndex(reply: String): List<com.werd.app.sources.BookIndex.Entry> {
        val a = AiJson.obj(reply)?.optJSONArray("index") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("title").trim()
            if (t.count { it.isLetter() } < 2) null else com.werd.app.sources.BookIndex.Entry(t.take(200), o.optInt("page", 0))
        }
    }

    /** Reads a فهرس from page images (the "Read the index" action): entries with their printed page numbers. */
    suspend fun readIndex(ai: AiClient, images: List<AiImage>): List<com.werd.app.sources.BookIndex.Entry> {
        val prompt = """These are some pages of an Arabic book. If any of them is the book's table of contents (فهرس / المحتويات),
return every entry in order with the page number printed next to it, copied exactly. Ignore pages that are not a table of contents.
Respond with JSON only: {"index": [{"title": "...", "page": 12}, ...]}, or {"index": []} if there is none."""
        return parseIndex(ai.generate(prompt, images, json = true, maxTokens = 16_000).text)
    }

    /** Diacritic-free, whitespace-collapsed key used to drop items already found in an earlier batch. */
    fun dedupeKey(text: String): String = text
        .filter { it !in 'ً'..'ٟ' && it != 'ٰ' && it != 'ـ' }
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().take(80)
}
