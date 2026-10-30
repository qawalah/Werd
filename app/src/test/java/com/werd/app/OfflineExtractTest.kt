package com.werd.app

import com.werd.app.sources.OfflineExtract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book reading without AI: headings, counts, notes and occasions from plain page text. */
class OfflineExtractTest {
    private val page = javaClass.classLoader!!.getResource("hisn_page.txt")!!.readText()

    @Test fun findsSupplicationsWithCountsNotesAndOccasions() {
        val items = OfflineExtract.extract(page, 31)
        items.forEach { println("${it.count}× [${it.occasion}] ${it.title} | ${it.text.take(40)} | ${it.note.take(30)}") }
        assertEquals(6, items.size)
        val (sayyid, hasbi, bism, waking, istighfar, salam) = items.let { listOf(it[0], it[1], it[2], it[3], it[4], it[5]) }.let { Six(it) }
        assertTrue(sayyid.text.startsWith("اللهم أنت ربي")); assertTrue(sayyid.text.endsWith("إلا أنت"))
        assertEquals("أذكار الصباح والمساء", sayyid.title); assertEquals("morning_evening", sayyid.occasion)
        assertTrue(sayyid.note.contains("دخل الجنة"))
        assertEquals(7, hasbi.count); assertEquals(3, bism.count)
        assertTrue(!bism.text.contains("ثلاث"))
        assertEquals("waking", waking.occasion); assertEquals("دعاء الاستيقاظ من النوم", waking.title)
        assertTrue(waking.text.startsWith("الحمد لله الذي أحيانا"))
        assertEquals(3, istighfar.count); assertEquals("after_prayer", istighfar.occasion)
        assertEquals("after_prayer", salam.occasion)
        assertTrue(items.all { it.page == 31 })
    }

    @Test fun headingCarriesToNextPage() {
        val st = OfflineExtract.State()
        assertTrue(OfflineExtract.extract("ما يقول إذا أخذ مضجعه", 9, st).isEmpty())
        val next = OfflineExtract.extract("«باسمك ربي وضعت جنبي، وبك أرفعه»", 10, st)
        assertEquals(1, next.size); assertEquals("sleep", next[0].occasion); assertEquals(10, next[0].page)
    }

    @Test fun proseIsSkipped() {
        assertTrue(OfflineExtract.extract("وقد ذكر العلماء أن المداومة على الأذكار من أسباب الحفظ والبركة في العمر.", 1).isEmpty())
    }

    private class Six(l: List<com.werd.app.data.SourceItem>) {
        val a = l[0]; val b = l[1]; val c = l[2]; val d = l[3]; val e = l[4]; val f = l[5]
        operator fun component1() = a; operator fun component2() = b; operator fun component3() = c
        operator fun component4() = d; operator fun component5() = e; operator fun component6() = f
    }
}
