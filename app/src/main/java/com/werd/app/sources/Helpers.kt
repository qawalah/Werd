package com.werd.app.sources

import android.content.Context
import com.werd.app.WerdApp
import com.werd.app.ai.AiClient
import com.werd.app.ai.AiJson
import com.werd.app.data.SourceItem
import org.json.JSONObject

/**
 * Proofreading helpers: they only suggest a word (with where the suggestion comes from); they never change the text.
 *  - [Quran]: a line that quotes a verse is matched against the Quran's text (in the app, offline), and a word that
 *    differs from the verse is offered the verse's word, found from the words before and after it.
 *  - [Spelling]: a word unknown to the Quran and to the rest of the book, which becomes a known word by swapping one
 *    look-alike letter (ر/ز, ع/غ, ح/ج/خ, د/ذ…), is offered that word.
 *  - [ContextModel]: an AI model (the user's Gemini or Claude, or the family server's model) asked which word fits
 *    where the readers disagree.
 */
object Helpers {
    /** Letters only, for comparing spellings across the Uthmani and the usual (imlaʾi) script: no tashkeel or Quranic marks,
     *  no alef (written in one, left out in the other), one form of hamza, ة=ه, ى=ي. */
    fun skeleton(w: String): String = buildString {
        for (c in w) when {
            c in 'ؐ'..'ؚ' || c in 'ً'..'ٟ' || c == 'ٰ' || c in 'ۖ'..'ۭ' || c in '࣓'..'ࣿ' || c == 'ـ' -> {}
            c == 'ا' || c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' || c == 'ء' -> {}
            c == 'ى' || c == 'ئ' -> append('ي')
            c == 'ؤ' -> append('و')
            c == 'ة' -> append('ه')
            c.isLetter() -> append(c)
        }
    }

    /** Positions of [w]'s letters that make up its [skeleton], so a suggestion can replace one letter and keep the tashkeel. */
    private fun skeletonPositions(w: String): IntArray {
        val out = ArrayList<Int>()
        w.forEachIndexed { i, c -> if (skeleton(c.toString()).isNotEmpty()) out += i }
        return out.toIntArray()
    }

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** The Uthmani rasm writes و or ي where the usual script has alef (الصلوة / الصلاة): not a misreading. */
    private fun rasmOnly(a: String, b: String) = a.replace("و", "").replace("ي", "") == b.replace("و", "").replace("ي", "")

    // ------------------------------------------------------------------ the Quran

    object Quran {
        class Verse(val surah: Int, val ayah: Int, val name: String, val words: List<String>, val keys: List<String>)

        @Volatile private var verses: List<Verse>? = null
        @Volatile private var grams: Map<String, IntArray>? = null
        @Volatile var vocabulary: Map<String, Int> = emptyMap(); private set

        fun load(context: Context): List<Verse> {
            verses?.let { return it }
            synchronized(this) {
                verses?.let { return it }
                val list = ArrayList<Verse>(6300)
                context.assets.open("quran.txt").bufferedReader().useLines { lines ->
                    lines.filter { !it.startsWith("#") && it.isNotBlank() }.forEach { l ->
                        val p = l.split('|', limit = 4)
                        if (p.size == 4) {
                            val ws = p[3].split(' ').filter { w -> w.any { it.isLetter() } }
                            list += Verse(p[0].toInt(), p[1].toInt(), p[2], ws, ws.map(::skeleton))
                        }
                    }
                }
                val g = HashMap<String, MutableList<Int>>()
                val vocab = HashMap<String, Int>()
                list.forEachIndexed { vi, v ->
                    v.keys.forEach { k -> if (k.isNotEmpty()) vocab[k] = (vocab[k] ?: 0) + 1 }
                    for (i in 0..v.keys.size - 2) g.getOrPut(v.keys[i] + " " + v.keys[i + 1]) { ArrayList() }.add(vi)
                }
                grams = g.mapValues { it.value.distinct().toIntArray() }
                vocabulary = vocab
                verses = list
                return list
            }
        }

        private fun same(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && distance(a, b) <= 1)

        /**
         * Suggestions for [text] from the verse(s) it quotes: (word index, the verse's word, "quran:<surah name>:<ayah>").
         * Only where the item clearly quotes a verse (most of its words in order) and a word's letters differ.
         */
        fun suggest(context: Context, text: String): List<Proof.Hint> {
            val vs = load(context)
            val g = grams ?: return emptyList()
            val iw = TwoPass.words(text)
            val ik = iw.map(::skeleton)
            if (ik.size < 4) return emptyList()
            val hits = HashMap<Int, Int>()
            for (i in 0..ik.size - 2) g[ik[i] + " " + ik[i + 1]]?.forEach { hits[it] = (hits[it] ?: 0) + 1 }
            if (hits.isEmpty()) return emptyList()
            val out = ArrayList<Proof.Hint>()
            val used = HashSet<Int>()
            // the best verses first; a quote may run over several verses, so look at a window of them
            for ((vi, n) in hits.entries.sortedByDescending { it.value }.take(4)) {
                if (n < 2 || vi in used) continue
                val window = (vi - 1..vi + 4).filter { it in vs.indices && vs[it].surah == vs[vi].surah }
                val ww = window.flatMap { k -> vs[k].words.map { w -> w to k } }
                val wk = ww.map { skeleton(it.first) }
                // align the item with the window (fuzzy word equality)
                val a = ik.size; val b = wk.size
                val dp = Array(a + 1) { IntArray(b + 1) }
                for (i in a - 1 downTo 0) for (j in b - 1 downTo 0) dp[i][j] = if (same(ik[i], wk[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
                val pairs = ArrayList<Pair<Int, Int>>()
                var i = 0; var j = 0
                while (i < a && j < b) when {
                    same(ik[i], wk[j]) -> { pairs += i to j; i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> i++
                    else -> j++
                }
                if (pairs.size < 5) continue
                val span = pairs.last().first - pairs.first().first + 1
                if (pairs.size < span * 0.7) continue
                used += window
                fun hint(ii: Int, jj: Int) {
                    val (w, k) = ww[jj]
                    if (ik[ii] == wk[jj] || rasmOnly(ik[ii], wk[jj])) return
                    // without the verse's pause and section marks (ۚ ۖ ۗ ۛ ۞ ۩), which a book's text doesn't carry
                    out += Proof.Hint(ii, w.filter { c -> c !in '\u06D6'..'\u06DC' && c != '\u06DE' && c != '\u06E9' }, "quran:${vs[k].name}:${vs[k].ayah}")
                }
                // fuzzy matches whose letters differ
                pairs.forEach { (ii, jj) -> if (ik[ii] != wk[jj]) hint(ii, jj) }
                // one item word left between two matched words, against one verse word there
                for (k in 0 until pairs.size - 1) {
                    val (i0, j0) = pairs[k]; val (i1, j1) = pairs[k + 1]
                    if (i1 - i0 == 2 && j1 - j0 == 2 && distance(ik[i0 + 1], wk[j0 + 1]) <= 2) hint(i0 + 1, j0 + 1)
                }
            }
            return out.distinctBy { it.word }
        }
    }

    // ------------------------------------------------------------------ spelling

    object Spelling {
        private val LOOKALIKE = listOf("رز", "عغ", "حجخ", "دذ", "سش", "صض", "طظ", "فق", "بتثني", "هة")

        /**
         * Words of [text] that are unknown (not in the Quran, not seen elsewhere in the book) but become a known word by one
         * look-alike letter: (word index, the corrected word with its tashkeel, "spelling").
         */
        fun suggest(context: Context, text: String, bookWords: Map<String, Int>): List<Proof.Hint> {
            Quran.load(context)
            val q = Quran.vocabulary
            fun known(k: String) = (q[k] ?: 0) + (bookWords[k] ?: 0)
            val out = ArrayList<Proof.Hint>()
            TwoPass.words(text).forEachIndexed { wi, w ->
                val k = skeleton(w)
                if (k.length < 3 || known(k) > 0 || (bookWords[k] ?: 0) >= 2) return@forEachIndexed
                val pos = skeletonPositions(w)
                var best: Pair<String, Int>? = null
                for (p in k.indices) for (grp in LOOKALIKE) {
                    if (k[p] !in grp) continue
                    for (c in grp) {
                        if (c == k[p]) continue
                        val cand = k.substring(0, p) + c + k.substring(p + 1)
                        val n = known(skeleton(cand))
                        if (n > 0 && (best == null || n > best.second)) {
                            val chars = w.toCharArray()
                            // the skeleton dropped alefs and hamzas: find the letter that maps to position p
                            val at = pos.getOrNull(p) ?: continue
                            chars[at] = c
                            best = String(chars) to n
                        }
                    }
                }
                best?.let { out += Proof.Hint(wi, it.first, "spelling") }
            }
            return out
        }

        /** How often each word (skeleton) appears in the book's items. */
        fun bookWords(items: List<SourceItem>): Map<String, Int> {
            val m = HashMap<String, Int>()
            items.forEach { it -> TwoPass.words(it.text).forEach { w -> val k = skeleton(w); if (k.isNotEmpty()) m[k] = (m[k] ?: 0) + 1 } }
            return m
        }
    }

    // ------------------------------------------------------------------ a small model, with the context

    object ContextModel {
        /** "gemini" / "claude" (the user's key) or "server" / "server:<model>"; "" when none can be used. */
        fun pick(context: Context): String {
            val app = WerdApp.from(context)
            val s = app.settings.value
            return when {
                s.aiKeyGemini.isNotBlank() -> Readers.GEMINI
                s.aiKeyClaude.isNotBlank() -> Readers.CLAUDE
                app.cloud.store.value.configured -> "server"
                else -> ""
            }
        }

        private fun prompt(text: String, words: List<Int>): String {
            val ws = TwoPass.words(text)
            var k = -1
            val marked = text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { p ->
                if (p.isBlank() || p.none { it.isLetter() }) p else { k++; if (k in words) "⟦$k:$p⟧" else p }
            }
            return """This Arabic line from a book of supplications (adhkar) was read from a page photo by OCR. The words marked ⟦n:word⟧ may be misread.
For each marked word, give the word that most likely appears in the book, judging from the words around it (well-known adhkar, hadith and Quranic wording), keeping full tashkeel. If a marked word is already right, give it unchanged.
Respond with JSON only: {"words":[{"i":n,"s":"word"}]}

$marked""".also { if (ws.isEmpty()) return "" }
        }

        /** Suggestions for the flagged words of [item] (or those in [words]) from [model] (see [pick]). */
        suspend fun suggest(context: Context, model: String, item: SourceItem, words: List<Int> = item.diffWords.split(',').mapNotNull { it.trim().toIntOrNull() }): List<Proof.Hint> {
            if (words.isEmpty() || model.isBlank()) return emptyList()
            val app = WerdApp.from(context)
            val p = prompt(item.text, words.take(20)).ifBlank { return emptyList() }
            val reply = when (model) {
                Readers.GEMINI, Readers.CLAUDE -> {
                    val s = app.settings.value
                    val key = if (model == Readers.CLAUDE) s.aiKeyClaude else s.aiKeyGemini
                    AiClient.create(model, key, if (model == Readers.CLAUDE) s.aiModelClaude else s.aiModelGemini)
                        .generate(p, emptyList(), json = true, maxTokens = 2000).text
                }
                else -> app.cloud.complete(p, model.substringAfter(':', ""))
            }
            val o: JSONObject = AiJson.obj(reply) ?: return emptyList()
            val a = o.optJSONArray("words") ?: return emptyList()
            val mine = TwoPass.words(item.text)
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { w ->
                val i = w.optInt("i", -1); val sug = w.optString("s").trim()
                if (i !in words || sug.isBlank() || sug.contains(' ') && sug.split(' ').size > 3) null
                else if (TwoPass.bareWord(sug) == TwoPass.bareWord(mine.getOrElse(i) { "" })) null
                else Proof.Hint(i, sug, "model:" + Readers.label(context, model))
            }
        }
    }

    /** Runs the offline helpers (Quran, spelling) on a book's unchecked items; returns the items that changed. */
    fun offline(context: Context, items: List<SourceItem>): List<SourceItem> {
        val book = Spelling.bookWords(items)
        return items.filter { !it.checked && it.text.length < 6000 }.mapNotNull { it ->
            val hints = Quran.suggest(context, it.text) + Spelling.suggest(context, it.text, book)
            val q = hints.filter { h -> h.by.startsWith("quran") }
            val sp = hints.filter { h -> h.by == "spelling" && q.none { x -> x.word == h.word } }
            val next = Proof.addHints(Proof.addHints(it, q, "quran"), sp, "spelling")
            next.takeIf { n -> n.hints != it.hints || n.diffWords != it.diffWords }
        }
    }
}
