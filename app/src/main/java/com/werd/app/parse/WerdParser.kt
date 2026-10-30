package com.werd.app.parse

/**
 * Turns pasted plain text (or an exported tally HTML file) into werd lines with repetition counts.
 *
 * Detects counts written as: (3)  [٣]  ×3  3x  x 3  "3 مرات"  "٣ مرة"  "(ثلاث مرات)"  "ثلاثاً"
 * "مرتين"  "مائة مرة"  "إحدى عشرة مرة"  "ثلاثاً وثلاثين"  "يكرر 7"  "3 times"  "thrice" …
 * A line that contains only a count applies it to the previous line.
 */
object WerdParser {

    data class Item(val text: String, val count: Int, val detected: Boolean)
    data class Result(val title: String?, val items: List<Item>)
    /** [start]/[end]: where the count marker sat in the input (-1 when none). */
    data class Detection(val rest: String, val count: Int?, val start: Int = -1, val end: Int = -1)

    // ---------------------------------------------------------------- public API

    fun parse(raw: String, splitSentences: Boolean = false): Result {
        if (looksLikeHtml(raw)) {
            parseTallyHtml(raw)?.let { return it }
            return parse(htmlToText(raw), splitSentences).let { r ->
                if (r.title == null) r.copy(title = htmlTitle(raw)) else r
            }
        }
        var lines = raw.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (splitSentences) lines = lines.flatMap { splitIntoSentences(it) }

        val items = ArrayList<Item>()
        for (line0 in lines) {
            val line = stripNumbering(line0)
            // A paragraph whose counts sit in the middle ("…، شاهت الوجوه (ثلاثاً)، …") is split at its counts.
            val clauses = splitClauses(line)
            if (clauses.size > 1 && clauses.dropLast(1).any { c -> detect(c).let { it.count != null || repeatedToken(it.rest) != null } }) {
                parseClauses(clauses, items)
                continue
            }
            val d = detect(line)
            val rest = d.rest
            repeatedToken(rest)?.takeIf { d.count == null }?.let { (t, n) -> items += Item(t, n, true); continue }
            if (isEmptyText(rest)) {
                // count-only line -> applies to previous item
                if (d.count != null && items.isNotEmpty()) {
                    items[items.lastIndex] = items.last().copy(count = d.count, detected = true)
                }
                continue
            }
            items += Item(rest, d.count ?: 1, d.count != null)
        }

        var title: String? = null
        if (items.size >= 3) {
            val first = items.first()
            if (!first.detected && looksLikeTitle(first.text, items.size)) {
                title = first.text
                items.removeAt(0)
            }
        }
        return Result(title, items)
    }

    /** Clause-level split of one paragraph: counted phrases become their own items, the text between them ×1. */
    private fun parseClauses(clauses: List<String>, items: MutableList<Item>) {
        val acc = ArrayList<String>()
        fun flush() {
            if (acc.isEmpty()) return
            // keep uncounted stretches readable: break them into chunks of a few hundred characters
            val chunk = StringBuilder()
            for (c in acc) {
                if (chunk.isNotEmpty() && chunk.length + c.length > MAX_CHUNK) {
                    items += Item(cleanup(chunk.toString()), 1, false); chunk.clear()
                }
                if (chunk.isNotEmpty()) chunk.append(' ')
                chunk.append(c)
            }
            if (chunk.isNotBlank()) items += Item(cleanup(chunk.toString()), 1, false)
            acc.clear()
        }
        for (cl in clauses) {
            val d = detect(cl)
            val rep = if (d.count == null) repeatedToken(d.rest) else null
            when {
                rep != null -> { flush(); items += Item(rep.first, rep.second, true) }
                d.count == null -> acc += cl
                isEmptyText(d.rest) -> {
                    // "…، (ثلاثاً)،" — a bare count applies to the phrase just before it
                    if (acc.isNotEmpty()) {
                        val last = acc.removeAt(acc.lastIndex)
                        flush(); items += Item(cleanup(last), d.count, true)
                    } else if (items.isNotEmpty()) {
                        items[items.lastIndex] = items.last().copy(count = d.count, detected = true)
                    }
                }
                else -> { flush(); items += Item(d.rest, d.count, true) }
            }
        }
        flush()
    }

    private const val MAX_CHUNK = 380

    /** Splits a paragraph after ، , ؛ . ? ! — but never inside brackets. Punctuation-only pieces stick to the previous clause. */
    fun splitClauses(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var ornate = false
        fun emit() {
            val t = sb.toString().trim(); sb.clear()
            if (t.isEmpty()) return
            if (t.none { it.isLetter() } && out.isNotEmpty() && !t.any { it.isDigit() }) out[out.lastIndex] = out.last() + t
            else out += t
        }
        var bracketStart = -1
        for (c in line) {
            sb.append(c)
            when (c) {
                '(', '[', '{', '«' -> { if (depth == 0) bracketStart = sb.length - 1; depth++ }
                ')', ']', '}', '»' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    // "… (ثلاثاً) أعوذ …" without a comma: a closing count bracket also ends the clause
                    if (depth == 0 && bracketStart >= 0 && !ornate) {
                        val inner = sb.substring(bracketStart)
                        bracketStart = -1
                        if (detect(inner).count != null) { emit(); continue }
                    }
                }
                '﴿', '﴾' -> ornate = !ornate
            }
            // OCR often loses a closing bracket: don't let one shield the rest of the paragraph
            if (depth > 0 && bracketStart >= 0 && sb.length - bracketStart > 60) { depth = 0; bracketStart = -1 }
            if (depth == 0 && !ornate && c in "،,؛;.؟?!۔") emit()
        }
        emit()
        return out.flatMap { splitAfterMarker(it) }
    }

    /** "… كل شيء (كهيعص ثلاثاً أنصرنا …" — text that follows a count belongs to the next clause. */
    private fun splitAfterMarker(clause: String): List<String> {
        val d = detect(clause)
        if (d.count == null || d.end < 0) return listOf(clause)
        val after = clause.substring(d.end)
        if (after.count { it.isLetter() } < 12) return listOf(clause)
        val head = clause.substring(0, d.end).trim()
        if (head.none { it.isLetter() }) return listOf(clause)
        return listOf(head) + splitAfterMarker(after.trim().trimStart('،', ',', ')', ' '))
    }

    /** "(حم حم حم حم حم حم حم)" → ("حم", 7): the same word written out three or more times. */
    fun repeatedToken(text: String): Pair<String, Int>? {
        val t = text.trim().trim('(', ')', '[', ']', '«', '»', '﴿', '﴾', '،', ',', ' ')
        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 3) return null
        val first = nw(tokens[0])
        if (first.length < 2 || tokens.any { nw(it) != first }) return null
        return tokens[0] to tokens.size
    }

    /** Detect a repetition count in one line and return the line with the marker removed. */
    fun detect(line: String): Detection {
        val norm = Norm(line)
        val n = norm.text
        for (p in patterns) {
            val m = p.regex.find(n) ?: continue
            val count = p.value(m) ?: continue
            if (count <= 0 || count > 100_000) continue
            val (s, e) = norm.originalRange(m.range.first, m.range.last + 1)
            val kept = if (p.keep > 0) m.groups[p.keep]?.range?.let { r ->
                val (ks, ke) = norm.originalRange(r.first, r.last + 1); line.substring(ks, ke)
            }.orEmpty() else ""
            val rest = cleanup(line.substring(0, s) + " " + kept + " " + line.substring(e))
            return Detection(rest, count, s, e)
        }
        return Detection(cleanup(line), null)
    }

    // ---------------------------------------------------------------- normalisation

    /** Normalised copy of a string (no diacritics, unified letters, ASCII digits) with an index map back. */
    private class Norm(private val original: String) {
        val text: String
        private val map: IntArray

        init {
            val sb = StringBuilder(original.length)
            val idx = IntArray(original.length + 1)
            var k = 0
            for ((i, c0) in original.withIndex()) {
                if (isIgnorable(c0)) continue
                sb.append(normChar(c0))
                idx[k++] = i
            }
            text = sb.toString()
            map = idx.copyOf(k)
        }

        fun originalRange(start: Int, endExcl: Int): Pair<Int, Int> {
            if (map.isEmpty()) return 0 to 0
            val s = map[start.coerceIn(0, map.lastIndex)]
            var e = map[(endExcl - 1).coerceIn(0, map.lastIndex)] + 1
            // swallow diacritics/tanween that trail the match (e.g. the ً of سبعاً)
            while (e < original.length && isIgnorable(original[e])) e++
            return s to e
        }
    }

    private fun isIgnorable(c: Char): Boolean =
        c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ' ||
            c == '‌' || c == '‍' || c == '‎' || c == '‏' || c == '؜'

    private fun normChar(c: Char): Char = when (c) {
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ة' -> 'ه'
        'ى' -> 'ي'
        'ؤ' -> 'و'
        '（' -> '('
        '）' -> ')'
        else -> c.lowercaseChar()
    }

    /** Same normalisation applied to a vocabulary word. */
    private fun nw(s: String): String = buildString { for (c in s) if (!isIgnorable(c)) append(normChar(c)) }

    // ---------------------------------------------------------------- Arabic number words

    private val units = mapOf(
        "واحد" to 1, "واحده" to 1, "احدي" to 1, "احد" to 1,
        "اثنين" to 2, "اثنان" to 2, "اثنتين" to 2, "اثنتان" to 2, "اثني" to 2, "اثنا" to 2,
        "ثلاث" to 3, "ثلاثه" to 3, "ثلاثا" to 3,
        "اربع" to 4, "اربعه" to 4, "اربعا" to 4,
        "خمس" to 5, "خمسه" to 5, "خمسا" to 5,
        "ست" to 6, "سته" to 6, "ستا" to 6,
        "سبع" to 7, "سبعه" to 7, "سبعا" to 7,
        "ثمان" to 8, "ثماني" to 8, "ثمانيه" to 8, "ثمانيا" to 8,
        "تسع" to 9, "تسعه" to 9, "تسعا" to 9,
    )
    private val tenWords = setOf("عشر", "عشره", "عشرا")
    private val tens = mapOf(
        "عشرين" to 20, "عشرون" to 20, "ثلاثين" to 30, "ثلاثون" to 30,
        "اربعين" to 40, "اربعون" to 40, "خمسين" to 50, "خمسون" to 50,
        "ستين" to 60, "ستون" to 60, "سبعين" to 70, "سبعون" to 70,
        "ثمانين" to 80, "ثمانون" to 80, "تسعين" to 90, "تسعون" to 90,
    )
    private val hundredWords = setOf("مائه", "مئه", "مايه", "مائة", "مئة")
    private val twoHundredWords = setOf("مائتين", "مئتين", "مائتان", "مئتان", "مايتين")
    private val thousandWords = setOf("الف", "الاف")
    private val twoThousandWords = setOf("الفين", "الفان")

    private val allNumberWords: List<String> by lazy {
        val base = units.keys + tenWords + tens.keys + hundredWords + twoHundredWords + thousandWords + twoThousandWords
        val compounds = units.keys.flatMap { u -> listOf(u + "مائه", u + "مئه") }
        (base + compounds).map { nw(it) }.distinct().sortedByDescending { it.length }
    }

    private val numWordsRegex: String by lazy {
        val alt = allNumberWords.joinToString("|") { Regex.escape(it) }
        // After the first word only "و…" words or ten/hundred/thousand words may follow, so that
        // "قل هو الله احد ثلاث مرات" yields 3 (not 1+3).
        val follow = "عشره|عشرا|عشر|مائه|مئه|الاف|الف"
        "(?:$alt)(?:\\s+(?:و(?:$alt)|$follow))*"
    }

    /** Parses a normalised Arabic number phrase like "ثلاثا وثلاثين" or "احدي عشره". */
    fun arabicNumber(phrase: String): Int? {
        val tokens = nw(phrase).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var total = 0
        var pending = 0
        var i = 0
        fun strip(t: String): String {
            if (t.length > 2 && t.startsWith("و")) {
                val r = t.substring(1)
                if (isNumberWord(r)) return r
            }
            return t
        }
        while (i < tokens.size) {
            val t = strip(tokens[i])
            when {
                t in units -> {
                    val u = units.getValue(t)
                    val next = tokens.getOrNull(i + 1)?.let { strip(it) }
                    if (next != null && next in tenWords) {
                        pending += u + 10; i++
                    } else pending += u
                }
                t in tenWords -> pending += 10
                t in tens -> pending += tens.getValue(t)
                t in hundredWords -> pending = if (pending in 1..9) pending * 100 else pending + 100
                t in twoHundredWords -> pending += 200
                (t.endsWith("مائه") || t.endsWith("مئه")) && units.containsKey(t.removeSuffix("مائه").removeSuffix("مئه")) ->
                    pending += units.getValue(t.removeSuffix("مائه").removeSuffix("مئه")) * 100
                t in thousandWords -> {
                    total += (if (pending == 0) 1 else pending) * 1000; pending = 0
                }
                t in twoThousandWords -> total += 2000
                else -> return null
            }
            i++
        }
        val v = total + pending
        return if (v > 0) v else null
    }

    private fun isNumberWord(t: String) =
        t in units || t in tenWords || t in tens || t in hundredWords || t in twoHundredWords ||
            t in thousandWords || t in twoThousandWords ||
            ((t.endsWith("مائه") || t.endsWith("مئه")) && units.containsKey(t.removeSuffix("مائه").removeSuffix("مئه")))

    // ---------------------------------------------------------------- patterns

    private class P(val regex: Regex, val keep: Int = 0, val value: (MatchResult) -> Int?)

    private const val ACC =
        "(?:ثلاثا|اربعا|خمسا|ستا|سبعا|ثمانيا|تسعا|عشرا)(?:\\s+و(?:عشرين|ثلاثين|اربعين|خمسين|ستين|سبعين|ثمانين|تسعين))?"

    /** Value of a normalised count phrase: "7", "×7", "7 مرات", "ثلاث مرات", "ثلاثا", "مرتين". */
    private fun countOf(s: String): Int? {
        val t = s.trim()
        Regex("\\d{1,5}").find(t)?.let { return it.value.toIntOrNull() }
        if (t.startsWith("مرت")) return 2
        val words = t.replace(Regex("\\s*(?:مرات|مره|مرار|مرارا|times|time)$"), "")
        return arabicNumber(words)
    }

    private const val TIMES = "(?:مرات|مره|مرار|مرارا|times|time|marrat|marra)"
    private const val OPEN = "[\\(\\[\\{«﴾﴿]"
    private const val CLOSE = "[\\)\\]\\}»﴾﴿]"
    private const val XS = "[x×✕*]"
    private const val NOTL_B = "(?<![\\p{L}])"
    private const val NOTL_A = "(?![\\p{L}])"

    private val englishWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "nineteen" to 19,
        "thirty-three" to 33, "thirty three" to 33, "thirty-four" to 34, "thirty four" to 34,
        "forty" to 40, "forty-one" to 41, "forty one" to 41, "seventy" to 70,
        "hundred" to 100, "a hundred" to 100, "one hundred" to 100, "a thousand" to 1000, "thousand" to 1000,
    )

    private val patterns: List<P> by lazy {
        val opts = setOf(RegexOption.IGNORE_CASE)
        val engAlt = englishWords.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        listOf(
            // (3)  (3 مرات)  [×3]  (x 3)  — bare numbers inside ornate Quran brackets ﴿٢٥٥﴾ are ayah numbers
            P(Regex("($OPEN)\\s*(?:$XS\\s*)?(\\d{1,5})\\s*($XS|$TIMES)?\\s*$CLOSE", opts)) { m ->
                val v = m.groupValues[2].toIntOrNull()
                val ornate = m.groupValues[1] == "﴾" || m.groupValues[1] == "﴿"
                val qualified = m.groupValues[3].isNotEmpty() || m.value.contains('×') || m.value.lowercase().contains('x')
                if (ornate && !qualified) null else v
            },
            // (مرة) (مرة واحدة) (مرتين)
            P(Regex("$OPEN\\s*(مره\\s+واحده|مره|مرتين|مرتان|once|twice|thrice)\\s*$CLOSE", opts)) { m ->
                when (m.groupValues[1].lowercase()) {
                    "مرتين", "مرتان", "twice" -> 2
                    "thrice" -> 3
                    else -> 1
                }
            },
            // (ثلاث مرات)  [سبعاً]
            P(Regex("$OPEN\\s*($numWordsRegex)\\s*(?:$TIMES)?\\s*$CLOSE", opts)) { m -> arabicNumber(m.groupValues[1]) },
            // (كهيعص ثلاثاً)  [حسبي الله ٧ مرات]  — the phrase and its count share one bracket; keep the phrase
            P(Regex("$OPEN\\s*([^()\\[\\]{}«»﴾﴿]*?[^\\s()\\[\\]{}«»﴾﴿])\\s*[-–—:،,]?\\s+" +
                "((?:$XS\\s*)?\\d{1,5}\\s*(?:$TIMES|$XS)?|(?:$numWordsRegex)\\s*$TIMES|مرتين|مرتان|$ACC)\\s*$CLOSE", opts), keep = 1) { m ->
                if (isEmptyText(m.groupValues[1])) null else countOf(m.groupValues[2])
            },
            // يكرر 7 / repeat 7
            P(Regex("$NOTL_B(?:يكرر|تكرر|كرر|تكرار|يقال|repeat|recite)\\s*:?\\s*(\\d{1,5})$NOTL_A(?:\\s*$TIMES)?", opts)) { m ->
                m.groupValues[1].toIntOrNull()
            },
            // 3 مرات / 100 مرة / 3 times
            P(Regex("(?:$XS\\s*)?(\\d{1,5})\\s*$TIMES$NOTL_A", opts)) { m -> m.groupValues[1].toIntOrNull() },
            // ثلاث مرات / مائة مرة / إحدى عشرة مرة
            P(Regex("$NOTL_B($numWordsRegex)\\s*$TIMES$NOTL_A", opts)) { m -> arabicNumber(m.groupValues[1]) },
            // مرتين / مرة واحدة
            P(Regex("$NOTL_B(مرتين|مرتان)$NOTL_A")) { 2 },
            P(Regex("$NOTL_B(مره\\s+واحده)$NOTL_A")) { 1 },
            // "… – 3" / "…: 7" — a number set off by a dash or colon at the end of the line
            P(Regex("\\s[-–—:]\\s*(\\d{1,5})\\s*[.،,؛]?\\s*$")) { m -> m.groupValues[1].toIntOrNull() },
            // ×3 / 3x at the end of the line
            P(Regex("(?:$XS\\s*(\\d{1,5})|(\\d{1,5})\\s*$XS)\\s*[.:،,؛]?\\s*$", opts)) { m ->
                (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
            },
            // 3x / ×3 at the start of the line
            P(Regex("^\\s*(?:$XS\\s*(\\d{1,5})|(\\d{1,5})\\s*$XS)(?![\\p{L}\\d])", opts)) { m ->
                (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
            },
            // ... ثلاثاً  /  ثلاثاً وثلاثين  at the end of the line (accusative form)
            P(Regex("$NOTL_B((?:ثلاثا|اربعا|خمسا|ستا|سبعا|ثمانيا|تسعا|عشرا)(?:\\s+و(?:عشرين|ثلاثين|اربعين|خمسين|ستين|سبعين|ثمانين|تسعين))?)\\s*[.:،,؛]?\\s*$")) { m ->
                arabicNumber(m.groupValues[1])
            },
            // three times / thrice / twice
            P(Regex("$NOTL_B($engAlt)\\s+times$NOTL_A", opts)) { m -> englishWords[m.groupValues[1].lowercase()] },
            P(Regex("$NOTL_B(thrice|twice)$NOTL_A", opts)) { m -> if (m.groupValues[1].lowercase() == "twice") 2 else 3 },
        )
    }

    // ---------------------------------------------------------------- helpers

    private val leadingNumbering = Regex("^\\s*[\\d\\u0660-\\u0669\\u06F0-\\u06F9]{1,3}\\s*[-.)–:]\\s+")

    private fun stripNumbering(line: String): String {
        val m = leadingNumbering.find(line) ?: return line
        val rest = line.substring(m.range.last + 1)
        return if (rest.isNotBlank()) rest else line
    }

    private val edgeJunk = Regex("^[\\s\\-–—:،,؛|*•·]+|[\\s\\-–—:،,؛|*•·]+$")
    private val emptyBrackets = Regex("[\\(\\[\\{«]\\s*[\\)\\]\\}»]")

    private fun cleanup(s: String): String {
        var r = s.replace(emptyBrackets, " ").replace(Regex("[ \\t]{2,}"), " ")
        r = r.replace(edgeJunk, "")
        return r.trim()
    }

    private fun isEmptyText(s: String) = s.none { it.isLetter() }

    private val titleWords = listOf(
        "ورد", "راتب", "اذكار", "أذكار", "حزب", "دعاء", "وظيفه", "وظيفة", "اوراد", "أوراد", "صلوات",
        "wird", "werd", "adhkar", "azkar", "ratib", "hizb", "dua", "du'a", "litany", "awrad",
    )

    private fun looksLikeTitle(s: String, count: Int): Boolean {
        if (s.length > 60) return false
        val l = nw(s)
        val keyword = titleWords.any { l.contains(nw(it)) }
        val terminal = s.trimEnd().lastOrNull()?.let { it in ".!؟?،:" } ?: false
        return (keyword && !terminal) || (s.length <= 40 && !terminal && count >= 4 && s.split(' ').size <= 6 && keyword)
    }

    private fun splitIntoSentences(line: String): List<String> =
        line.split(Regex("(?<=[.!؟?۔])\\s+|\\s*۞\\s*|\\s*\\*\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- HTML

    private fun looksLikeHtml(s: String): Boolean {
        val t = s.trimStart().take(400).lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html") || (t.contains("<body") || t.contains("<div") || t.contains("<p>"))
    }

    /** Reads the `sentences = [["text", n], ...]` array of a tally page like the original Ratib file. */
    private fun parseTallyHtml(html: String): Result? {
        val start = Regex("sentences\\s*=\\s*\\[").find(html) ?: return null
        val body = html.substring(start.range.last)
        val pair = Regex("\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*(\\d+)\\s*\\]")
        val items = ArrayList<Item>()
        var pos = 0
        while (true) {
            val m = pair.find(body, pos) ?: break
            // stop at end of the outer array
            val between = body.substring(pos, m.range.first)
            if (pos > 0 && between.contains(';')) break
            items += Item(unescapeJs(m.groupValues[1]).trim(), m.groupValues[2].toInt().coerceAtLeast(1), true)
            pos = m.range.last + 1
        }
        if (items.isEmpty()) return null
        return Result(htmlTitle(html), items)
    }

    private fun htmlTitle(html: String): String? {
        val t = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            ?: Regex("<h[12][^>]*>(.*?)</h[12]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        return t?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.let(::decodeEntities)?.trim()?.ifEmpty { null }
    }

    private fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        return decodeEntities(s)
    }

    private fun decodeEntities(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> if (i + 5 < s.length) {
                        s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }; i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
