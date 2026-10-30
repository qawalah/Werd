package com.werd.app.sources

import android.content.Context
import com.werd.app.WerdApp
import com.werd.app.ai.AiImage
import com.werd.app.ai.AiTasks
import com.werd.app.data.Source
import com.werd.app.ocr.readCancellable

/** "Read the index": looks for the فهرس on the first and last pages of a book that was scanned without one. */
object IndexReader {
    /** Reads, links and saves the index; returns its entries (empty when no contents page was found). */
    suspend fun read(context: Context, src: Source, progress: (Int, Int) -> Unit): List<BookIndex.Entry> {
        val dao = WerdApp.from(context).db.dao()
        val pages = BookIndex.candidatePages(src.pageCount)
        var found = emptyList<BookIndex.Entry>()
        PageSource(src).use { ps ->
            val ai = if (src.provider != Sources.OFFLINE) Sources.aiClient(context) else null
            if (ai != null) {
                pages.chunked(4).forEachIndexed { k, chunk ->
                    progress(minOf((k + 1) * 4, pages.size), pages.size)
                    found = BookIndex.merge(found, AiTasks.readIndex(ai, chunk.map { AiImage(ps.jpeg(it)) }))
                }
            } else {
                com.werd.app.ocr.AiReader.open(context, task = "index:${src.id}", title = src.title).use { r ->
                    pages.forEachIndexed { k, p ->
                        progress(k + 1, pages.size)
                        val bmp = ps.bitmap(p, 2000)
                        val text = try { r.readCancellable(bmp, true) } finally { bmp.recycle() }
                        found = BookIndex.merge(found, BookIndex.fromPage(text))
                    }
                }
            }
        }
        val linked = BookIndex.link(found, dao.sourceItems(src.id))
        if (linked.isNotEmpty()) dao.setSourceIndex(src.id, BookIndex.encode(linked))
        return linked
    }
}
