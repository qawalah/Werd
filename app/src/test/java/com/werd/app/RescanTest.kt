package com.werd.app

import com.werd.app.data.SourceItem
import com.werd.app.sources.Rescan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding a re-read of some pages into a book. */
class RescanTest {
    private fun item(id: Long, pos: Int, page: Int, text: String, checked: Boolean = false, werd: Long? = null) =
        SourceItem(id = id, sourceId = 1, position = pos, page = page, title = "t$id", text = text, checked = checked, werdId = werd)

    @Test fun replacesOnlyTheRangeAndKeepsWhatMatters() {
        val all = listOf(
            item(1, 0, 1, "اللهم أنت ربي", checked = true),
            item(2, 1, 2, "سبحان الله", checked = true),                  // re-read with tashkeel → tick dropped
            item(3, 2, 2, "الحمد لله", checked = true),                    // re-read identical → tick kept
            item(4, 3, 3, "نص مشوّه من القراءة الأولى"),                     // not found again → removed
            item(5, 4, 3, "دعاء أضيف إلى الأوراد", werd = 9),              // not found again, but in the user's awrad → kept
            item(6, 5, 5, "بعد النطاق", checked = true),
        )
        val found = listOf(
            SourceItem(sourceId = 1, position = 0, page = 2, title = "التسبيح", text = "سُبْحَانَ اللَّهِ", count = 33),
            SourceItem(sourceId = 1, position = 1, page = 2, title = "الحمد", text = "الحمد لله"),
            SourceItem(sourceId = 1, position = 2, page = 3, title = "جديد", text = "دعاء لم تجده القراءة الأولى"),
            SourceItem(sourceId = 1, position = 3, page = 3, title = "مكرر", text = "دعاء لم تجده القراءة الأولى"),   // duplicate in the reply
        )
        val plan = Rescan.merge(all, 2, 3, found, 1)
        assertEquals(listOf(4L), plan.removed)
        assertEquals(1, plan.inserted.size)
        val byId = plan.updated.associateBy { it.id }
        assertEquals("سُبْحَانَ اللَّهِ", byId[2]!!.text); assertEquals(33, byId[2]!!.count); assertFalse(byId[2]!!.checked)
        assertTrue(byId[3]!!.checked)
        assertEquals(9L, byId[5]!!.werdId)
        assertTrue(byId[1]!!.checked && byId[6]!!.checked)        // outside the range: untouched
        // reading order, renumbered
        val order = (plan.updated + plan.inserted).sortedBy { it.position }
        assertEquals(listOf("اللهم أنت ربي", "سُبْحَانَ اللَّهِ", "الحمد لله", "دعاء لم تجده القراءة الأولى", "دعاء أضيف إلى الأوراد", "بعد النطاق"),
            order.map { it.text })
        assertEquals((0..5).toList(), order.map { it.position })
    }
}
