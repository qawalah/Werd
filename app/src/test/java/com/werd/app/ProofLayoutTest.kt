package com.werd.app

import androidx.test.core.app.ApplicationProvider
import com.werd.app.data.SourceItem
import com.werd.app.sources.Helpers
import com.werd.app.sources.OfflineExtract
import com.werd.app.sources.PageLayout
import com.werd.app.sources.Proof
import com.werd.app.sources.Titles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.15: headers, footers and footnotes; titles; carrying on across a page; proofreading with any reader and the helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProofLayoutTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun item(id: Long, page: Int, title: String, text: String, checked: Boolean = false) =
        SourceItem(id = id, sourceId = 1, position = id.toInt(), page = page, title = title, text = text, checked = checked)

    @Test fun headerFooterAndFootnotesComeOff() {
        val run = PageLayout.Running("الأذكار المأثورة")
        fun page(n: Int, body: String) = "الأذكار المأثورة   ${n}\n$body\n________\n(١) رواه مسلم.\n${n}"
        val p1 = PageLayout.split(page(12, "دعاء الاستيقاظ\nالحمد لله الذي أحيانا بعد ما أماتنا وإليه النشور (١)"), run)
        assertEquals(12, p1.pageNumber)
        assertFalse(p1.body.contains("المأثورة"))
        assertTrue(p1.body.contains("الحمد لله"))
        assertEquals(listOf("(١) رواه مسلم."), p1.footnotes)
        // the page number at the bottom is not a count
        assertFalse(p1.body.trim().endsWith("12"))
        // a running chapter header, learned from the pages before
        val run2 = PageLayout.Running("كتاب")
        repeat(2) { PageLayout.split("باب أذكار الصباح\nسبحان الله وبحمده\nسبحان الله العظيم", run2) }
        val p3 = PageLayout.split("باب أذكار الصباح\nأصبحنا وأصبح الملك لله", run2)
        assertEquals("أصبحنا وأصبح الملك لله", p3.body)
        assertEquals("باب أذكار الصباح", run2.section)
        // the footnote goes to the item whose text marks it, and the mark leaves the text
        val items = OfflineExtract.extract(p1.body, 13, top = p1.top, bottom = p1.bottom)
        val withNotes = PageLayout.attachFootnotes(items, p1.footnotes)
        assertEquals("(١) رواه مسلم.", withNotes.single().footnote)
        assertFalse(withNotes.single().text.contains("(١)"))
        assertTrue(withNotes.single().region.matches(Regex("0\\.\\d+-[01]\\.\\d+")))
    }

    @Test fun titlesTellItemsApart() {
        val book = "حصن المسلم"
        val items = listOf(
            item(1, 3, book, "دعاء الاستيقاظ من النوم الحمد لله الذي أحيانا بعد ما أماتنا وإليه النشور"),
            item(2, 4, book, "اللهم بك أصبحنا وبك أمسينا وبك نحيا وبك نموت وإليك النشور"),
            item(3, 5, book, "سبحان الله وبحمده", checked = true),
        )
        val fixed = Titles.fix(items, book).associateBy { it.id }
        assertEquals("دعاء الاستيقاظ من النوم", fixed.getValue(1).title)
        assertTrue(fixed.getValue(1).text.startsWith("الحمد لله"))
        assertTrue(fixed.getValue(2).title.startsWith("اللهم بك أصبحنا"))
        assertNull(fixed[3])     // proofread items are left alone
        // a heading read twice (title and the start of the text)
        val dup = Titles.fix(listOf(item(5, 9, "دعاء الكرب", "دعاء الكرب لا إله إلا الله العظيم الحليم")), book).single()
        assertEquals("لا إله إلا الله العظيم الحليم", dup.text)
        // a title that is the first words of a supplication is never cut from it
        assertTrue(Titles.fix(listOf(item(6, 9, "اللهم إني أسألك", "اللهم إني أسألك العفو والعافية")), book).isEmpty())
    }

    @Test fun aSupplicationCarriesOnOnTheNextPage() {
        val (more, rest) = OfflineExtract.continuation("اللهم أنت ربي لا إله إلا أنت خلقتني وأنا عبدك",
            "وأنا على عهدك ووعدك ما استطعت.\nدعاء الكرب\nلا إله إلا الله العظيم الحليم")
        assertEquals("وأنا على عهدك ووعدك ما استطعت.", more)
        assertTrue(rest.startsWith("دعاء الكرب"))
        // a finished one doesn't take anything
        assertEquals("", OfflineExtract.continuation("سبحان الله.", "الحمد لله رب العالمين").first)
    }

    @Test fun anyReaderProofreadsAndOffersItsWord() {
        val it = item(1, 2, "t", "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ سُبْحَانَ اللَّهِ الْعَطِيمِ")
        val a = Proof.addReading(it, "server:qari4", "سبحان الله وبحمده سبحان الله العظيم")
        assertEquals("5", a.diffWords); assertFalse(a.checked)
        assertEquals(listOf("العظيم" to "server:qari4"), Proof.offers(a, 5))
        // a second reader that agrees with the first reading keeps the flag (one reader still disagrees)
        val b = Proof.addReading(a, "tesseract", "سبحان الله وبحمده سبحان الله العطيم")
        assertEquals(2, Proof.reads(b).size); assertEquals("5", b.diffWords)
        // replacing the word settles it
        val fixed = Proof.settle(b.copy(text = Proof.replaceWord(b.text, 5, "الْعَظِيمِ")))
        assertEquals("الْعَظِيمِ", fixed.text.split(' ').last())
        // a reading of an item the user already ticked never unticks it or flags it
        val mine = Proof.addReading(it.copy(checked = true), "gemini", "سبحان الله وبحمده سبحان الله العظيم")
        assertTrue(mine.checked); assertEquals("", mine.diffWords)
    }

    @Test fun alternativesLineUpWordForWord() {
        val alt = Proof.alternatives("اللهم أنت ربي لا اله الا أنت خلقتني وانا عبذك", "اللهم انت ربي لا اله الا انت خلقتني وانا عبدك وانا على عهدك")
        assertEquals("عبدك", alt[9])
    }

    @Test fun theQuranSuggestsTheMisreadWord() {
        // آية الكرسي with «القيوم» misread as «الفيوم»
        val text = "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْفَيُّومُ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ لَهُ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ"
        val hints = Helpers.Quran.suggest(ctx, text)
        val h = hints.single { it.word == 6 }
        assertEquals("لقيوم", Helpers.skeleton(h.suggestion))
        assertFalse(h.suggestion.contains('\u06DA'))       // without the verse's pause mark
        assertTrue(h.by, h.by.startsWith("quran:البقرة:255"))
        // the Uthmani spelling of a correct word is not a suggestion (السماوات / ٱلسَّمَٰوَٰتِ)
        assertTrue(hints.none { it.word == 15 })
    }

    @Test fun spellingFindsALookAlikeLetter() {
        // «الزحمن» is no word; one look-alike letter away is «الرحمن»
        val hints = Helpers.Spelling.suggest(ctx, "بسم الله الزحمن الرحيم", emptyMap())
        assertEquals(2, hints.single().word)
        assertEquals("الرحمن", hints.single().suggestion)
        assertTrue(Helpers.Spelling.suggest(ctx, "بسم الله الرحمن الرحيم", emptyMap()).isEmpty())
    }
}
