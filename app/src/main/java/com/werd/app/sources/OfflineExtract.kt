package com.werd.app.sources

import com.werd.app.data.SourceItem
import com.werd.app.parse.WerdParser

/**
 * Finds supplications in a page of plain text without AI.
 *
 * Rules of thumb that hold for most adhkar books:
 *  - a short line such as "دعاء الاستيقاظ من النوم" or "باب ما يقول إذا أصبح" is a heading: it titles what follows
 *    and usually names the occasion;
 *  - a supplication opens with words like اللهم / سبحان / أعوذ / بسم الله / ربنا…, or is quoted «…»;
 *  - "من قالها…", "رواه…" lines are notes (virtue / reference) for the item before them;
 *  - a count ("ثلاث مرات", "(٣)", "×7") anywhere in the paragraph is the repetition.
 * Less precise than AI — the proofread view is there to fix what it gets wrong.
 */
object OfflineExtract {

    /** Recent heading carried from page to page (a heading at the bottom of one page titles the top of the next). */
    class State { var heading: String = "" }

    fun bare(s: String): String = s.filter { it !in 'ً'..'ٟ' && it != 'ٰ' && it != 'ـ' }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

    private val DUA_START = listOf(
        "اللهم", "سبحان", "الحمد لله", "الحمدلله", "لا اله الا", "استغفر", "اعوذ", "بسم الله", "باسم الله", "باسمك", "ربنا", "رب ",
        "حسبي", "حسبنا", "اصبحنا", "امسينا", "اصبحت", "امسيت", "يا حي", "لا حول", "رضيت", "يا رب", "قل هو الله", "قل اعوذ",
        "الله لا اله", "سبوح", "الله اكبر", "يا مقلب", "توكلت", "استودع", "انا لله", "لا اله", "اللهما", "يا الله", "يا ذا الجلال",
        "اشهد", "استغفرالله", "صلى الله", "بارك الله", "اسال الله", "اسالك", "يا من", "الهي", "سيدي",
    )
    private val DUA_ANYWHERE = listOf("اللهم", "سبحان الله", "استغفر الله", "اعوذ ب", "لا اله الا", "الحمد لله", "ربنا ", "حسبي الله", "صل علي")
    private val HEADING_START = listOf(
        "باب", "دعاء", "ادعيه", "ما يقول", "ما يقال", "اذكار", "ذكر", "الذكر", "فضل", "فصل", "كتاب", "التسبيح", "الاستعاذه", "الاستغفار",
        "عند", "اذا ", "حين", "قبل", "بعد", "في ", "من اذكار", "الدعاء", "ورد", "حزب", "راتب", "المجموعه", "القسم",
    )
    private val NOTE_START = listOf("من قال", "من قالها", "رواه", "اخرجه", "اخرجها", "صحيح", "حسن", "قال رسول", "قال النبي", "عن ", "وعن ",
        "حديث", "وفي روايه", "متفق عليه", "اسناده", "اي ", "يعني", "المعنى", "شرح", "فائده", "تنبيه", "ملاحظه", "قوله")

    private val OCCASION_WORDS: List<Pair<String, List<String>>> = listOf(
        "morning_evening" to listOf("الصباح والمساء", "صباحا ومساء", "يصبح ويمسي", "اصبح وامسى", "اذا اصبح واذا امسى", "الصباح و المساء"),
        "morning" to listOf("الصباح", "اذا اصبح", "حين يصبح", "صباحا", "بعد الفجر", "اصبحنا"),
        "evening" to listOf("المساء", "اذا امسى", "حين يمسي", "مساء", "بعد العصر", "بعد المغرب", "امسينا"),
        "after_prayer" to listOf("دبر", "بعد الصلاه", "بعد السلام", "ادبار الصلوات", "بعد كل صلاه", "عقب الصلاه", "بعد التسليم"),
        "waking" to listOf("الاستيقاظ", "اذا استيقظ", "من النوم", "الانتباه"),
        "sleep" to listOf("النوم", "اذا اوى", "عند المنام", "اذا نام", "اذا اخذ مضجعه", "فراشه", "اضطجع"),
        "friday" to listOf("الجمعه"),
        "travel" to listOf("السفر", "المسافر", "ركب", "الركوب", "الدابه"),
        "home" to listOf("المنزل", "البيت", "دخل بيته", "خرج من بيته"),
        "food" to listOf("الطعام", "الاكل", "الشراب", "اكل", "شرب", "الافطار"),
        "mosque" to listOf("المسجد", "الاذان", "الوضوء", "الخلاء"),
        "distress" to listOf("الكرب", "الهم", "الحزن", "الغم", "الضيق", "المصيبه", "الشده", "الخوف"),
        "ramadan" to listOf("رمضان", "الصيام", "الصائم", "ليله القدر"),
    )

    fun occasionOf(vararg texts: String): String {
        val t = bare(texts.joinToString(" "))
        // the first heading match wins; the morning_evening phrases are checked before the single ones
        return OCCASION_WORDS.firstOrNull { (_, ws) -> ws.any { t.contains(it) } }?.first ?: "general"
    }

    private val NUMBERING = Regex("^\\s*[\\(\\[]?[0-9٠-٩]{1,3}[\\)\\]]?\\s*[-–ـ.:/)]\\s*")
    private fun stripNumbering(s: String) = s.replace(NUMBERING, "")

    private fun words(s: String) = s.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }

    /** The paragraph opens (or reads) like a supplication. */
    fun looksLikeDua(p: String) = isDua(p)

    /** A heading by its words ("دعاء…", "باب…", "ذكر…", or ending with ":"), not just by being short. */
    fun headingLike(p: String): Boolean {
        val b = bare(p).trim()
        return words(p) in 1..12 && !isDua(p) && (b.endsWith(":") || HEADING_START.any { b.startsWith(it) })
    }

    /** A heading run into the text under it: ("دعاء الاستيقاظ من النوم", "الحمد لله الذي…"), or null. */
    fun leadingHeading(text: String): Pair<String, String>? {
        val parts = splitLeadingHeading(text)
        if (parts.size != 2) return null
        return cleanHeading(parts[0]) to parts[1].trim()
    }

    /** Ends like a finished sentence (else the next page probably carries on with it). */
    private fun finished(s: String): Boolean {
        val t = s.trimEnd()
        return t.isEmpty() || t.last() in ".۔؛!؟?»)﴾﴿:]" || WerdParser.detect(t).count != null
    }

    /**
     * The start of a page that carries on the last supplication of the page before (no heading, no new supplication),
     * and the rest of the page. ("", page) when the page starts with something new or [previous] was finished.
     */
    fun continuation(previous: String, pageText: String): Pair<String, String> {
        if (finished(previous)) return "" to pageText
        val lines = pageText.replace("\r", "").split('\n')
        var n = 0
        val taken = ArrayList<String>()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) { n++; continue }
            if (isHeading(l) || isNote(l) || (isDua(l) && taken.isEmpty() && DUA_START.any { bare(l).trimStart('«', '"', '(').startsWith(it) })) break
            if (WerdParser.detect(l).let { it.count != null && it.rest.none(Char::isLetter) }) { taken += l; n++; break }
            taken += l; n++
            if (finished(l) || taken.size >= 6) break
        }
        if (taken.isEmpty()) return "" to pageText
        return taken.joinToString(" ") to lines.drop(n).joinToString("\n")
    }

    private fun isDua(p: String): Boolean {
        val b = bare(p).trimStart('«', '"', '“', '(', '﴿', ' ', '-', '«')
        if (DUA_START.any { b.startsWith(it) }) return true
        if (p.trimStart().startsWith("«") || p.trimStart().startsWith("﴿")) return true
        return DUA_ANYWHERE.any { b.contains(it) } && words(p) >= 3
    }

    private fun isNote(p: String): Boolean {
        val b = bare(p).trimStart('(', '[', '*', ' ', '-')
        return NOTE_START.any { b.startsWith(it) } || Regex("رواه|اخرجه|متفق عليه|صححه|حسنه").containsMatchIn(bare(p)) && !isDua(p)
    }

    private fun isHeading(p: String): Boolean {
        val n = words(p)
        if (n == 0 || n > 12) return false
        val b = bare(p).trim()
        if (b.endsWith(":") || b.endsWith("：")) return true
        if (HEADING_START.any { b.startsWith(it) } && !b.contains("«")) return true
        // a short line with no sentence punctuation and no dua opening
        return n <= 6 && !isDua(p) && !b.contains('،') && !b.contains('.') && WerdParser.detect(p).count == null
    }

    /** A trailing reference: "… (رواه مسلم)". */
    private val REFERENCE = Regex("\\s*[\\(\\[]?\\s*(رواه|أخرجه|اخرجه|متفق عليه|صحيح ال)[^«»]*$")

    private fun cleanText(s: String): String = s.trim()
        .removePrefix("«").removeSuffix("»").replace("«", "").replace("»", "")
        .trim(' ', '"', '“', '”', '-', '–', '.', '،', ':')
        .replace(Regex("\\s+"), " ")

    private fun cleanHeading(s: String) = stripNumbering(s).trim().trimEnd(':', '：', '.', '،', ' ').replace(Regex("\\s+"), " ")

    /** Joins a quote that runs over several lines: «… / … / …». */
    private fun joinQuotes(paras: List<String>): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var open = false
        for (p in paras) {
            if (open) { sb.append(' ').append(p); if (p.count { it == '»' } > p.count { it == '«' }) { out += sb.toString(); sb.clear(); open = false }; continue }
            if (p.count { it == '«' } > p.count { it == '»' }) { sb.append(p); open = true } else out += p
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** OCR may run a heading into the supplication under it: "دعاء الاستيقاظ من النوم الحمد لله الذي…" → two paragraphs. */
    private fun splitLeadingHeading(p: String): List<String> {
        val ws = p.split(' ')
        if (isDua(ws.take(3).joinToString(" "))) return listOf(p)
        for (i in 2..minOf(10, ws.size - 2)) {
            val rest = ws.drop(i).joinToString(" ")
            val restBare = bare(rest).trimStart('«', '"', '(')
            if (rest.trimStart().startsWith("«") || DUA_START.any { restBare.startsWith(it) }) {
                val head = ws.take(i).joinToString(" ")
                val hb = bare(head).trim()
                if (HEADING_START.any { hb.startsWith(it) } || hb.endsWith(":")) return listOf(head, rest)
                return listOf(p)
            }
        }
        return listOf(p)
    }

    /**
     * The supplications on a page. [top]..[bottom]: where the text is on the page (fractions of its height), to note
     * where each item is ([SourceItem.region]); [section]: the chapter (the page's running header).
     */
    fun extract(pageText: String, page: Int, state: State = State(), top: Float = 0f, bottom: Float = 1f, section: String = ""): List<SourceItem> {
        val paras = joinQuotes(pageText.replace("\r", "").split('\n').map { stripNumbering(it.trim()) }
            .filter { it.any(Char::isLetter) || WerdParser.detect(it).count != null }
            .flatMap { splitLeadingHeading(it) })
        val out = ArrayList<SourceItem>()
        val total = paras.sumOf { it.length + 1 }.coerceAtLeast(1)
        var at = 0
        fun f(x: Int) = top + (bottom - top) * x.toFloat() / total
        fun where(start: Int, end: Int): String =
            String.format(java.util.Locale.ROOT, "%.3f-%.3f", (f(start) - 0.02f).coerceAtLeast(0f), (f(end) + 0.02f).coerceAtMost(1f))
        // a count or note under an item: its place on the page reaches down to there
        fun SourceItem.stretch(end: Int): SourceItem = if (region.isBlank()) this
            else copy(region = region.substringBefore('-') + "-" + String.format(java.util.Locale.ROOT, "%.3f", (f(end) + 0.02f).coerceAtMost(1f)))
        for (p in paras) {
            val pStart = at; at += p.length + 1
            val d = WerdParser.detect(p)
            val restIsEmpty = d.rest.none { it.isLetter() }
            when {
                // "ثلاث مرات" on its own line → the previous item's count
                d.count != null && (restIsEmpty || words(d.rest) <= 2 && !isDua(d.rest)) -> {
                    if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(count = d.count, textOnly = false).stretch(at)
                }
                isNote(p) && !p.trimStart().startsWith("«") -> {
                    if (out.isNotEmpty()) {
                        val last = out.last()
                        // a note may also carry the count: "من قالها ثلاثاً…"
                        val c = d.count?.takeIf { last.count == 1 } ?: last.count
                        out[out.lastIndex] = last.copy(note = listOf(last.note, cleanText(p)).filter { it.isNotBlank() }.joinToString(" "),
                            count = c, textOnly = last.textOnly && d.count == null, occasion = if (last.occasion == "general") occasionOf(p) else last.occasion).stretch(at)
                    }
                }
                isDua(p) -> {
                    val text = cleanText(cleanText(d.rest).ifBlank { cleanText(p) }.replace(REFERENCE, ""))
                    if (words(text) < 2) continue
                    val h = state.heading
                    out += SourceItem(sourceId = 0, position = 0, page = page,
                        title = h.ifBlank { text.split(' ').take(4).joinToString(" ") },
                        text = text, count = d.count ?: 1, textOnly = d.count == null,
                        occasion = occasionOf(h).takeIf { it != "general" } ?: occasionOf(text),
                        occasionText = h.takeIf { occasionOf(it) != "general" }.orEmpty(), section = section, region = where(pStart, at))
                }
                isHeading(p) -> state.heading = cleanHeading(p)
                // other prose: explanation, chapter text — skipped
            }
        }
        return out
    }
}
