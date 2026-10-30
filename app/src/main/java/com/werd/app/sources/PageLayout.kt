package com.werd.app.sources

import com.werd.app.data.SourceItem

/**
 * The parts of a printed page that are not its text: the running header (the book's or the chapter's title), the
 * footer (the page number, publisher marks) and the footnotes. They are taken out before the page's supplications are
 * found, so a page number isn't read as a repetition count and a header doesn't become every item's title.
 *
 * Found by position (the first and last lines) and by repetition: what the earlier pages had at the same place.
 */
object PageLayout {
    data class Page(
        val body: String,
        val header: String = "",
        val footer: String = "",
        val pageNumber: Int? = null,
        val footnotes: List<String> = emptyList(),
        /** Where the body is on the page, as fractions of its height (top, bottom), from the line counts. */
        val top: Float = 0f,
        val bottom: Float = 1f,
    )

    /** What the pages so far had at their top and bottom (keys without numbers), and the book's title. */
    class Running(val bookTitle: String = "") {
        val top = HashMap<String, Int>()
        val bottom = HashMap<String, Int>()
        /** The chapter named by the running header of the latest page. */
        var section = ""
    }

    private val PAGE_NO = Regex("^[\\s\\-–—ـ(\\[{«﴾]*(?:ص|صفحة|صفحه|page|p\\.)?\\s*[0-9٠-٩۰-۹]{1,4}\\s*[\\-–—ـ)\\]}»﴿]*$", RegexOption.IGNORE_CASE)
    private val EDGE_NO = Regex("^\\s*[\\(\\[]?[0-9٠-٩۰-۹]{1,4}[\\)\\]]?\\s+|\\s+[\\(\\[]?[0-9٠-٩۰-۹]{1,4}[\\)\\]]?\\s*$")
    private val SEPARATOR = Regex("^[\\s_\\-–—ـ=*·.•]{5,}$")
    private val FOOTNOTE = Regex("^\\s*[\\(\\[]\\s*([0-9٠-٩۰-۹]{1,2})\\s*[\\)\\]]\\s*\\S")
    private val MARK = Regex("[\\(\\[⁽]\\s*([0-9٠-٩۰-۹]{1,2})\\s*[\\)\\]⁾]")

    private fun latin(s: String) = buildString { for (c in s) append(when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c }) }
    private fun number(s: String) = Regex("[0-9]{1,4}").find(latin(s))?.value?.toIntOrNull()

    /** A line compared with others: letters only, no tashkeel or numbers. */
    fun key(s: String) = OfflineExtract.bare(s).filter { it.isLetter() }

    private fun words(s: String) = s.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }

    private fun similar(a: String, b: String): Boolean {
        val ka = key(a); val kb = key(b)
        if (ka.length < 4 || kb.length < 4) return false
        return ka == kb || ka.contains(kb) || kb.contains(ka)
    }

    /** Splits a page's text into its body and the rest, learning what repeats as it goes. */
    fun split(text: String, run: Running): Page {
        val lines = text.replace("\r", "").split('\n').map { it.trim() }
        val idx = lines.indices.filter { lines[it].isNotEmpty() }
        if (idx.size < 2) return Page(text.trim())
        var first = 0; var last = idx.size - 1      // the body is idx[first..last]
        var pageNo: Int? = null
        val header = ArrayList<String>(); val footer = ArrayList<String>(); val notes = ArrayList<String>()

        // the top: a page number, a line seen at the top of earlier pages, or a short line with the page number or the book's title
        while (first <= last && first < 2) {
            val l = lines[idx[first]]
            val k = key(l)
            when {
                PAGE_NO.matches(l) -> { pageNo = pageNo ?: number(l) }
                k.length >= 3 && (run.top[k] ?: 0) >= 2 && words(l) <= 10 -> { header += l.replace(EDGE_NO, " ").trim(); pageNo = pageNo ?: if (EDGE_NO.containsMatchIn(l)) number(l) else null }
                words(l) in 1..8 && EDGE_NO.containsMatchIn(l) && first == 0 && OfflineExtract.looksLikeDua(l).not() -> { header += l.replace(EDGE_NO, " ").trim(); pageNo = number(l) }
                words(l) in 1..10 && run.bookTitle.isNotBlank() && similar(l, run.bookTitle) -> header += l
                else -> break
            }
            first++
        }
        // the bottom: a page number or a line seen at the bottom of earlier pages
        while (last >= first && idx.size - 1 - last < 2) {
            val l = lines[idx[last]]
            val k = key(l)
            when {
                PAGE_NO.matches(l) -> { pageNo = pageNo ?: number(l) }
                k.length >= 3 && (run.bottom[k] ?: 0) >= 2 && words(l) <= 10 -> footer += l
                else -> break
            }
            last--
        }
        // footnotes: everything under a separator line in the lower part of the page, or numbered notes at the bottom
        val body = ArrayList<String>()
        var inNotes = false
        for (n in first..last) {
            val l = lines[idx[n]]
            if (!inNotes && SEPARATOR.matches(l) && n > first + (last - first) / 3) { inNotes = true; continue }
            if (inNotes) notes += l else body += l
        }
        if (!inNotes) {
            // "(1) رواه مسلم" lines at the very bottom, when the text above carries the same marks
            val marks = MARK.findAll(body.joinToString(" ")).map { latin(it.groupValues[1]) }.toSet()
            while (body.size > 2) {
                val l = body.last()
                val m = FOOTNOTE.find(l) ?: break
                if (latin(m.groupValues[1]) !in marks || OfflineExtract.looksLikeDua(l.replace(MARK, ""))) break
                notes.add(0, l); body.removeAt(body.size - 1)
            }
        }
        // learn the edges (without numbers) for the pages to come
        key(lines[idx.first()]).takeIf { it.length >= 3 }?.let { run.top[it] = (run.top[it] ?: 0) + 1 }
        key(lines[idx.last()]).takeIf { it.length >= 3 }?.let { run.bottom[it] = (run.bottom[it] ?: 0) + 1 }
        header.firstOrNull { it.isNotBlank() && !similar(it, run.bookTitle) }?.let { run.section = it.trim(' ', '-', '–', 'ـ', '|') }

        // marks of the footnotes in the text become ⁽١⁾, so they aren't read as repetition counts ("(٣)" = three times)
        val noted = notes.mapNotNull { n -> FOOTNOTE.find(n)?.let { latin(it.groupValues[1]) } }.toSet()
        if (noted.isNotEmpty()) for (i in body.indices) body[i] = body[i].replace(MARK) { m -> if (latin(m.groupValues[1]) in noted) "⁽" + m.groupValues[1] + "⁾" else m.value }
        val total = idx.size.toFloat()
        return Page(body.joinToString("\n"), header.joinToString(" ").trim(), footer.joinToString(" ").trim(), pageNo, notes,
            top = first / total, bottom = (last + 1) / total)
    }

    /** Gives each item the footnotes its text marks ((1), (٢)…), and takes the marks out of the text. */
    fun attachFootnotes(items: List<SourceItem>, notes: List<String>): List<SourceItem> {
        if (notes.isEmpty()) return items
        val byMark = notes.mapNotNull { n -> FOOTNOTE.find(n)?.let { latin(it.groupValues[1]) to n.trim() } }.toMap()
        val loose = notes.filter { FOOTNOTE.find(it) == null }
        return items.mapIndexed { i, it ->
            val marks = MARK.findAll(it.text).map { m -> latin(m.groupValues[1]) }.filter { it in byMark }.toList()
            var item = it
            if (marks.isNotEmpty()) item = item.copy(
                footnote = listOf(item.footnote, marks.joinToString("\n") { byMark.getValue(it) }).filter { s -> s.isNotBlank() }.joinToString("\n"),
                text = item.text.replace(MARK) { m -> if (latin(m.groupValues[1]) in byMark) "" else m.value }.replace(Regex("\\s{2,}"), " ").trim())
            // notes without a mark belong to the last item of the page
            if (i == items.lastIndex && loose.isNotEmpty()) item = item.copy(footnote = listOf(item.footnote, loose.joinToString("\n")).filter { s -> s.isNotBlank() }.joinToString("\n"))
            item
        }
    }
}

/**
 * Titles that tell the supplications apart: the heading printed over each one, not the book's title or a running
 * header repeated on every item; a heading that ran into the text is taken out of it.
 */
object Titles {
    private fun key(s: String) = PageLayout.key(s)

    /** The items whose title or text changes (items the user ticked as proofread are left as they are). */
    fun fix(items: List<SourceItem>, bookTitle: String): List<SourceItem> {
        if (items.isEmpty()) return emptyList()
        val bookKey = key(bookTitle)
        val groups = items.groupBy { key(it.title) }
        val changed = LinkedHashMap<Long, SourceItem>()
        for ((k, group) in groups) {
            val pages = group.map { it.page }.distinct().size
            val sameAsBook = k.isEmpty() || (bookKey.length >= 4 && (k == bookKey || k.contains(bookKey) || bookKey.contains(k)))
            val everywhere = items.size >= 5 && group.size >= 4 && group.size >= items.size * 0.4 && pages >= 3
            if (!sameAsBook && !everywhere) continue
            for (it in group) {
                if (it.checked) continue
                val split = OfflineExtract.leadingHeading(it.text)
                val title = split?.first ?: firstWords(it.text)
                changed[it.id] = it.copy(title = title, text = split?.second ?: it.text,
                    section = it.section.ifBlank { if (!sameAsBook) group.first().title else "" })
            }
        }
        // a heading that is also the start of the text (read twice): keep it as the title only
        for (it in items) {
            if (it.checked || it.id in changed) continue
            val tk = key(it.title)
            if (tk.length < 6 || TwoPass.words(it.title).size < 2) continue
            if (key(it.text).startsWith(tk) && TwoPass.words(it.text).size > TwoPass.words(it.title).size + 2 && OfflineExtract.headingLike(it.title)) {
                val n = TwoPass.words(it.title).size
                var seen = 0
                val rest = it.text.split(Regex("(?<=\\s)|(?=\\s)")).dropWhile { p -> if (p.any(Char::isLetter)) seen++ < n else true }.joinToString("").trim()
                if (rest.isNotBlank()) changed[it.id] = it.copy(text = rest)
            }
        }
        return changed.values.toList()
    }

    fun firstWords(text: String, n: Int = 5): String {
        val ws = TwoPass.words(text)
        return ws.take(n).joinToString(" ") + if (ws.size > n) "…" else ""
    }
}
