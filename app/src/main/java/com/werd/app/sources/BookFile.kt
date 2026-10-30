package com.werd.app.sources

import com.werd.app.ai.AiTasks
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.WerdDao
import org.json.JSONArray
import org.json.JSONObject

/**
 * A book's extracted supplications as a shareable file (".werdbook", JSON, UTF-8).
 * The same document is what the shared library stores and serves (see docs/server-api.md).
 * Page images are never included — only the texts, their counts, occasions and notes.
 */
object BookFile {
    const val FORMAT = "werd-book"
    const val VERSION = 1
    const val EXT = "werdbook"
    const val MIME = "application/json"
    /** Larger files are refused on import (a book of a few thousand supplications is well under this). */
    const val MAX_BYTES = 8 * 1024 * 1024

    class FormatException(message: String) : Exception(message)

    data class Book(
        val title: String,
        val author: String,
        val language: String,
        val provider: String,
        val model: String,
        val pageCount: Int,
        val items: List<SourceItem>,
        /** Set when the file came from the library. */
        val libraryId: String = "",
        /** The book's فهرس; [BookIndex.Entry.item] here is the index of the item in [items] (not an id). */
        val index: List<BookIndex.Entry> = emptyList(),
    ) {
        val checked: Int get() = items.count { it.checked }
    }

    fun looksLikeBook(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") && t.take(400).contains("\"$FORMAT\"")
    }

    fun toJson(source: Source, items: List<SourceItem>, author: String = source.author): JSONObject = JSONObject().apply {
        put("format", FORMAT); put("version", VERSION)
        put("title", source.title); put("author", author); put("language", "ar")
        put("provider", source.provider); put("model", source.model)
        put("pageCount", source.pageCount)
        put("exportedAt", System.currentTimeMillis())
        val sorted = items.sortedBy { it.position }
        put("items", JSONArray().apply {
            sorted.forEach { i ->
                put(JSONObject().apply {
                    put("title", i.title); put("text", i.text); put("count", i.count)
                    put("occasion", i.occasion); put("occasionText", i.occasionText); put("note", i.note)
                    put("page", i.page); put("checked", i.checked)
                    if (i.textOnly) put("textOnly", true)
                    if (i.section.isNotBlank()) put("section", i.section)
                    if (i.footnote.isNotBlank()) put("footnote", i.footnote)
                })
            }
        })
        val index = BookIndex.decode(source.indexJson)
        if (index.isNotEmpty()) put("index", JSONArray().apply {
            index.forEach { e -> put(JSONObject().put("title", e.title).put("page", e.page).put("item", sorted.indexOfFirst { it.id == e.item })) }
        })
    }

    fun parse(text: String): Book {
        if (text.length > MAX_BYTES) throw FormatException("too large")
        val o = runCatching { JSONObject(text) }.getOrElse { throw FormatException("not JSON") }
        if (o.optString("format") != FORMAT) throw FormatException("not a werd book")
        if (o.optInt("version", 0) > VERSION) throw FormatException("made by a newer version of Werd")
        val arr = o.optJSONArray("items") ?: throw FormatException("no items")
        val items = (0 until arr.length()).mapNotNull { k ->
            val i = arr.optJSONObject(k) ?: return@mapNotNull null
            val t = i.optString("text").trim()
            if (t.none { it.isLetter() }) return@mapNotNull null
            SourceItem(
                sourceId = 0, position = k, page = i.optInt("page", 1).coerceAtLeast(1),
                title = i.optString("title").trim().ifEmpty { t.take(40) }.take(200),
                text = t, count = i.optInt("count", 1).coerceIn(1, 100_000),
                occasion = i.optString("occasion").takeIf { it in AiTasks.OCCASIONS } ?: "general",
                occasionText = i.optString("occasionText").take(300), note = i.optString("note").take(600),
                checked = i.optBoolean("checked", false), textOnly = i.optBoolean("textOnly", false),
                section = i.optString("section").take(120), footnote = i.optString("footnote").take(1000),
            )
        }
        if (items.isEmpty()) throw FormatException("no items")
        val title = o.optString("title").trim().ifEmpty { "—" }.take(200)
        return Book(title, o.optString("author").trim().take(200), o.optString("language", "ar"),
            o.optString("provider"), o.optString("model"),
            o.optInt("pageCount", items.maxOf { it.page }).coerceAtLeast(1), items, o.optString("id"),
            o.optJSONArray("index")?.let { a -> (0 until a.length()).mapNotNull { k ->
                val e = a.optJSONObject(k) ?: return@mapNotNull null
                val t = e.optString("title").trim()
                if (t.isEmpty()) null else BookIndex.Entry(t.take(200), e.optInt("page", 0), e.optInt("item", -1).takeIf { it in items.indices }?.toLong())
            } }.orEmpty())
    }

    /** Adds the book as a finished source (no AI, no pages). Returns the new source id. */
    suspend fun import(dao: WerdDao, book: Book, libraryId: String = book.libraryId): Long {
        val sid = dao.insertSource(Source(
            title = book.title, kind = Sources.KIND_IMPORTED, path = "", pageCount = book.pageCount, pagesDone = book.pageCount,
            status = "DONE", provider = book.provider, model = book.model, author = book.author, libraryId = libraryId,
        ))
        dao.insertSourceItems(book.items.mapIndexed { k, it -> it.copy(id = 0, sourceId = sid, position = k, werdId = null) })
        if (book.index.isNotEmpty()) {
            val ids = dao.sourceItems(sid).sortedBy { it.position }.map { it.id }
            dao.setSourceIndex(sid, BookIndex.encode(book.index.map { e -> e.copy(item = e.item?.toInt()?.let { ids.getOrNull(it) }) }))
        }
        return sid
    }

    /** A file name for the book: its title with characters that file systems dislike removed. */
    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().ifEmpty { "book" }.take(80) + ".$EXT"
}
