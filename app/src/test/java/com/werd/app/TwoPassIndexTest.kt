package com.werd.app

import com.werd.app.data.SourceItem
import com.werd.app.sources.BookIndex
import com.werd.app.sources.TwoPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwoPassIndexTest {
    private fun item(id: Long, page: Int, title: String, text: String = "سبحان الله") =
        SourceItem(id = id, sourceId = 1, position = id.toInt(), page = page, title = title, text = text)

    @Test fun agreeingReadingsTickTheItem() {
        val withTashkeel = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ سُبْحَانَ اللَّهِ الْعَظِيمِ"
        val plain = "قال رسول الله ﷺ: سبحان الله وبحمده سبحان الله العظيم (رواه البخاري)"
        val r = TwoPass.annotate(item(1, 3, "t", withTashkeel), plain)
        assertTrue(r.checked); assertEquals("", r.diffWords)
    }

    @Test fun aMisreadWordIsFlaggedWithTheOtherReading() {
        // the reading with tashkeel turned «العظيم» into «العطيم»
        val withTashkeel = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ سُبْحَانَ اللَّهِ الْعَطِيمِ"
        val plain = "من قالها: سبحان الله وبحمده سبحان الله العظيم مائة مرة"
        val r = TwoPass.annotate(item(1, 3, "t", withTashkeel), plain)
        assertFalse(r.checked)
        assertEquals("5", r.diffWords)                 // the sixth word, العطيم
        assertTrue(r.alt.contains("العظيم"))
    }

    @Test fun doubtfulReadings() {
        assertTrue(TwoPass.doubtful("ا ل ل ه م  ص ل  ع ل ى"))                  // broken into letters
        assertTrue(TwoPass.doubtful("|||| ~~ @@ ## اللهم $$ %% ^^ صل على محمد"))  // junk characters
        assertFalse(TwoPass.doubtful("اللَّهُمَّ صَلِّ عَلَى سَيِّدِنَا مُحَمَّدٍ وَعَلَى آلِهِ وَصَحْبِهِ وَسَلِّمْ تَسْلِيماً كَثِيراً"))
    }

    private val contents = """
        الفهرس
        المقدمة ............................. ٥
        أذكار الصباح والمساء ................ ١٢
        دعاء الاستيقاظ من النوم ............. ٢٠
        أذكار بعد الصلاة .................... 24
        ٣١ ...... راتب الإمام الحداد
        الخاتمة ............................ ٤٠
    """.trimIndent()

    @Test fun readsAContentsPage() {
        assertTrue(BookIndex.looksLikeIndex(contents))
        val e = BookIndex.fromPage(contents)
        assertEquals(6, e.size)
        assertEquals("أذكار الصباح والمساء" to 12, e[1].title to e[1].page)
        assertEquals("راتب الإمام الحداد" to 31, e[4].title to e[4].page)
        // an ordinary page with a few numbers is not an index
        assertFalse(BookIndex.looksLikeIndex("سبحان الله ٣٣\nالحمد لله ٣٣\nالله أكبر ٣٤\nثم يقول لا إله إلا الله وحده لا شريك له"))
    }

    @Test fun linksByTitleThenByPageWithTheOffset() {
        // the scan starts 4 pages before the printed numbering
        val items = listOf(item(10, 16, "أذكار الصباح والمساء"), item(11, 24, "دعاء الاستيقاظ"), item(12, 26, "سبحان الله"),
            item(13, 28, "أذكار بعد الصلاة"), item(14, 35, "الورد"))
        val linked = BookIndex.link(BookIndex.fromPage(contents), items)
        assertEquals(10L, linked[1].item)                    // by title
        assertEquals(11L, linked[2].item)                    // "دعاء الاستيقاظ من النوم" ~ "دعاء الاستيقاظ"
        assertEquals(13L, linked[3].item)
        assertEquals(14L, linked[4].item)                    // page 31 + 4 → 35
        assertEquals(null, linked[0].item)                   // the introduction has no supplication
        // round trip
        assertEquals(linked, BookIndex.decode(BookIndex.encode(linked)))
    }
}
