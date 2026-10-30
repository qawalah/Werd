package com.werd.app.sources

import com.werd.app.ai.AiTasks
import com.werd.app.data.SourceItem

/** Re-reading part of a book (e.g. with a stronger model) and folding the result into what is already there. */
object Rescan {

    data class Plan(val removed: List<Long>, val updated: List<SourceItem>, val inserted: List<SourceItem>) {
        val changes get() = removed.size + inserted.size
    }

    /**
     * [all]: the book's items now. [found]: what the re-read found on pages [from]..[to], in reading order.
     * - an item found again keeps its row (id, link to a werd); its proofread tick stays only if the text is unchanged;
     * - new items are added, unticked;
     * - items no longer found are removed — unless they were added to the user's awrad, then they stay;
     * - pages outside the range are untouched; the whole book is renumbered in reading order.
     */
    fun merge(all: List<SourceItem>, from: Int, to: Int, found: List<SourceItem>, sourceId: Long): Plan {
        val (inRange, outside) = all.partition { it.page in from..to }
        val oldByKey = inRange.sortedBy { it.position }.groupBy { AiTasks.dedupeKey(it.text) }
        val used = HashSet<Long>()
        val seen = HashSet<String>()
        val fresh = found.filter { seen.add(AiTasks.dedupeKey(it.text)) }.map { n ->
            val old = oldByKey[AiTasks.dedupeKey(n.text)]?.firstOrNull { it.id !in used }
            if (old != null) {
                used += old.id
                old.copy(title = n.title.ifBlank { old.title }, text = n.text, count = n.count, occasion = n.occasion,
                    occasionText = n.occasionText, note = n.note, page = n.page.coerceIn(from, to),
                    // a two-pass re-read that agrees ticks it; otherwise the tick stays only if the text didn't change
                    checked = (old.checked && old.text == n.text) || (n.checked && n.diffWords.isEmpty() && n.alt.isEmpty() && n.checked),
                    textOnly = n.textOnly, alt = n.alt, diffWords = n.diffWords)
            } else n.copy(id = 0, sourceId = sourceId, werdId = null, page = n.page.coerceIn(from, to),
                checked = n.checked && n.diffWords.isEmpty())
        }
        val leftover = inRange.filter { it.id !in used }
        val keptBecauseAdded = leftover.filter { it.werdId != null }
        val removed = leftover.filter { it.werdId == null }.map { it.id }

        val before = outside.filter { it.page < from }.sortedBy { it.position }
        val after = outside.filter { it.page > to }.sortedBy { it.position }
        val middle = (fresh + keptBecauseAdded).sortedBy { it.page }   // stable: keeps reading order within a page
        val ordered = (before + middle + after).mapIndexed { i, it -> it.copy(position = i) }
        return Plan(removed, ordered.filter { it.id != 0L }, ordered.filter { it.id == 0L })
    }
}
