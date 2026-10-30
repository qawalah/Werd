package com.werd.app

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.werd.app.ai.AiClient
import com.werd.app.ai.AiImage
import com.werd.app.ai.AiReply
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.Werd
import com.werd.app.data.WerdItem
import com.werd.app.data.excludes
import com.werd.app.reminder.ReminderScheduler
import com.werd.app.sources.SourceImport
import com.werd.app.sources.SourceWorker
import com.werd.app.sources.Sources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SourcesTest {
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private val dao get() = app.db.dao()

    /** Answers like a model would, per page range found in the prompt. */
    private class FakeAi(val reply: (prompt: String, images: Int) -> AiReply) : AiClient {
        override val provider = "fake"; override val model = "fake"
        val calls = ArrayList<Pair<String, Int>>()
        override suspend fun generate(prompt: String, images: List<AiImage>, json: Boolean, maxTokens: Int): AiReply {
            calls += prompt to images.size; return reply(prompt, images.size)
        }
        override suspend fun listModels() = emptyList<String>()
    }

    @After fun tearDown() { SourceWorker.clientOverride = null }

    private fun imagesSource(pages: Int): Long = runBlocking {
        val d = File(Sources.dir(app), "test_book").apply { mkdirs() }
        repeat(pages) { i ->
            val b = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            File(d, "%04d.jpg".format(i + 1)).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        dao.insertSource(Source(title = "حصن", kind = Sources.KIND_IMAGES, path = d.path, pageCount = pages))
    }

    private fun runWorker(id: Long) = runBlocking {
        TestListenableWorkerBuilder<SourceWorker>(app).setInputData(workDataOf("id" to id)).build().doWork()
    }

    private fun item(text: String, count: Int, occasion: String, page: Int) =
        """{"title":"$text","text":"$text","count":$count,"occasion":"$occasion","page":$page}"""

    @Test fun readsInBatchesWithLookaheadAndDedupes() {
        val id = imagesSource(6)
        val ai = FakeAi { p, _ ->
            when {
                p.contains("pages 1–4") -> AiReply("""{"items":[${item("أصبحنا وأصبح الملك لله", 1, "morning", 1)},
                    ${item("سبحان الله وبحمده", 100, "morning_evening", 2)},${item("أستغفر الله", 3, "after_prayer", 4)}]}""", false)
                p.contains("pages 5–6") -> AiReply("""{"items":[${item("أَسْتَغْفِرُ اللهَ", 3, "after_prayer", 5)},
                    ${item("باسمك ربي وضعت جنبي", 1, "sleep", 6)}]}""", false)
                else -> AiReply("""{"items":[]}""", false)
            }
        }
        SourceWorker.clientOverride = ai
        assertTrue(runWorker(id) is ListenableWorker.Result.Success)
        assertEquals(listOf(5, 2), ai.calls.map { it.second })                 // 4 pages + look-ahead, then the last 2
        assertTrue(ai.calls[0].first.contains("ONLY so you can finish"))
        assertFalse(ai.calls[1].first.contains("ONLY so you can finish"))
        val s = runBlocking { dao.source(id)!! }
        assertEquals("DONE", s.status); assertEquals(6, s.pagesDone)
        val items = runBlocking { dao.sourceItems(id) }
        assertEquals(4, items.size)                                               // the vocalised duplicate was dropped
        assertEquals(listOf(0, 1, 2, 3), items.map { it.position })
        assertEquals("sleep", items.last().occasion); assertEquals(6, items.last().page)
    }

    @Test fun rescanReplacesOnlyTheChosenPages() {
        val id = imagesSource(6)
        SourceWorker.clientOverride = FakeAi { p, _ ->
            if (p.contains("pages 1–4")) AiReply("""{"items":[${item("أصبحنا وأصبح الملك لله", 1, "morning", 1)},
                ${item("سبحان الله", 3, "general", 3)}]}""", false)
            else AiReply("""{"items":[${item("باسمك ربي", 1, "sleep", 6)}]}""", false)
        }
        runWorker(id)
        runBlocking { dao.sourceItems(id).forEach { dao.setSourceItemChecked(it.id, true) } }
        // re-read page 3 only, with a "stronger model" that gets the tashkeel and the count right
        val ai = FakeAi { p, n ->
            assertTrue(p.contains("pages 3–3")); assertEquals(1, n)
            AiReply("""{"items":[${item("سُبْحَانَ اللَّهِ", 33, "after_prayer", 3)}]}""", false)
        }
        SourceWorker.clientOverride = ai
        val r = runBlocking {
            dao.setRescan(id, 3, 3, 0)
            TestListenableWorkerBuilder<SourceWorker>(app).setInputData(workDataOf("id" to id, "from" to 3, "to" to 3,
                "provider" to "gemini", "model" to "gemini-3.8-pro")).build().doWork()
        }
        assertTrue(r is ListenableWorker.Result.Success)
        val items = runBlocking { dao.sourceItems(id) }
        assertEquals(listOf("أصبحنا وأصبح الملك لله", "سُبْحَانَ اللَّهِ", "باسمك ربي"), items.map { it.text })
        assertEquals(33, items[1].count); assertFalse(items[1].checked)           // changed → needs checking again
        assertTrue(items[0].checked && items[2].checked)                          // other pages untouched
        val s = runBlocking { dao.source(id)!! }
        assertEquals(0, s.rescanTo); assertEquals("", s.error)
    }

    @Test fun cutOffReplyIsRetriedPageByPage() {
        val id = imagesSource(3)
        val ai = FakeAi { p, n ->
            if (n == 3) AiReply("""{"items":[${item("نص مقطوع", 1, "general", 1)}""", true)   // whole batch truncated
            else AiReply("""{"items":[${item("ذكر صفحة " + Regex("pages (\\d)").find(p)!!.groupValues[1], 1, "general", 1)}]}""", false)
        }
        SourceWorker.clientOverride = ai
        runWorker(id)
        assertEquals(listOf(3, 2, 2, 1), ai.calls.map { it.second })   // each page re-read with the next page as look-ahead
        assertEquals(3, runBlocking { dao.sourceItems(id) }.size)
    }

    @Test fun quotaPausesAndResumeContinues() {
        val id = imagesSource(8)
        var fail = true
        SourceWorker.clientOverride = FakeAi { p, _ ->
            if (p.contains("pages 5–8") && fail) throw com.werd.app.ai.AiException("quota", com.werd.app.ai.AiException.Kind.QUOTA)
            AiReply("""{"items":[${item("ذكر " + p.length, 1, "general", 1)}]}""", false)
        }
        runWorker(id)
        runBlocking { dao.source(id)!! }.let { assertEquals("PAUSED", it.status); assertEquals(4, it.pagesDone) }
        fail = false
        runBlocking { dao.setSourceStatus(id, "QUEUED") }
        runWorker(id)
        runBlocking { dao.source(id)!! }.let { assertEquals("DONE", it.status); assertEquals(8, it.pagesDone) }
    }

    @Test fun offlineBookIsReadWithoutAi() {
        val f = File(Sources.dir(app), "offline.txt").apply {
            writeText("أذكار الصباح\n«أصبحنا وأصبح الملك لله والحمد لله» مرة واحدة\n«سبحان الله وبحمده» مائة مرة\n")
        }
        val sid = runBlocking { dao.insertSource(Source(title = "t", kind = Sources.KIND_TEXT, path = f.path, pageCount = 1, provider = Sources.OFFLINE)) }
        SourceWorker.clientOverride = null   // no AI configured at all
        val w = TestListenableWorkerBuilder<SourceWorker>(app).setInputData(workDataOf("id" to sid)).build()
        val r = runBlocking { w.doWork() }
        assertTrue(r is ListenableWorker.Result.Success)
        runBlocking {
            assertEquals("DONE", dao.source(sid)!!.status)
            val items = dao.sourceItems(sid)
            assertEquals(listOf(1, 100), items.map { it.count })
            assertTrue(items.all { it.occasion == "morning" })
        }
    }

    @Test fun proofreadInsertsMissedTextInPlaceAndTracksChecks() = runBlocking {
        val sid = dao.insertSource(Source(title = "b", kind = Sources.KIND_IMAGES, path = "/none", pageCount = 9, pagesDone = 9, status = "DONE"))
        dao.insertSourceItems(listOf(
            SourceItem(sourceId = sid, position = 0, page = 2, title = "a", text = "أ"),
            SourceItem(sourceId = sid, position = 1, page = 5, title = "b", text = "ب"),
            SourceItem(sourceId = sid, position = 2, page = 7, title = "c", text = "ج"),
        ))
        // a supplication the AI missed on page 5 goes after page 5's items, before page 7's
        dao.insertSourceItemAt(SourceItem(sourceId = sid, position = 2, page = 5, title = "missed", text = "د", checked = true))
        val items = dao.sourceItems(sid)
        assertEquals(listOf("a", "b", "missed", "c"), items.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), items.map { it.position })

        val ui = com.werd.app.ui.sources.proofPages(items, dao.source(sid), includeEmpty = false)
        assertEquals(listOf(2, 5, 7), ui)
        assertEquals((1..9).toList(), com.werd.app.ui.sources.proofPages(items, dao.source(sid), includeEmpty = true))
        // page 5 still has "b" unchecked; after page 5 the next unchecked is 7, wrapping to 2
        assertEquals(5, com.werd.app.ui.sources.nextUnchecked(items, 2))
        assertEquals(7, com.werd.app.ui.sources.nextUnchecked(items, 5))
        assertEquals(2, com.werd.app.ui.sources.nextUnchecked(items, 7))
        items.forEach { dao.setSourceItemChecked(it.id, true) }
        assertNull(com.werd.app.ui.sources.nextUnchecked(dao.sourceItems(sid), 2))
    }

    @Test fun addToWerdsWithSmartGrouping() = runBlocking {
        app.settings.update { it.copy(onboarded = true, seeded = true) }
        val morning = app.repo.addGroup("أذكار الصباح")
        val sid = dao.insertSource(Source(title = "ك", kind = Sources.KIND_TEXT, path = "/x", pageCount = 1))
        dao.insertSourceItems(listOf(
            SourceItem(sourceId = sid, position = 0, page = 1, title = "أصبحنا", text = "أصبحنا وأصبح الملك لله", count = 1, occasion = "morning"),
            SourceItem(sourceId = sid, position = 1, page = 1, title = "سيد الاستغفار", text = "اللهم أنت ربي", count = 1, occasion = "morning"),
            SourceItem(sourceId = sid, position = 2, page = 2, title = "التسبيح", text = "سبحان الله", count = 33, occasion = "after_prayer"),
            SourceItem(sourceId = sid, position = 3, page = 3, title = "عام", text = "لا حول ولا قوة إلا بالله", count = 10, occasion = "general"),
        ))
        val picked = dao.sourceItems(sid)
        val names = mapOf("morning" to "الصباح", "after_prayer" to "بعد الصلاة")
        // one werd per item, filed by occasion
        val ids = SourceImport.add(app.repo, dao, picked, SourceImport.Options(false, "", true, null, names))
        val werds = ids.map { dao.werd(it)!! }
        assertEquals(morning, werds[0].groupId); assertEquals(morning, werds[1].groupId)
        val created = dao.groups().first { it.name == "بعد الصلاة" }
        assertEquals(created.id, werds[2].groupId)
        assertNull(werds[3].groupId)
        assertEquals(33, dao.items(ids[2]).single().target)
        assertTrue(dao.sourceItems(sid).all { it.werdId != null })
        // combined: one werd, in the dominant occasion's group, items in order
        val combined = SourceImport.add(app.repo, dao, picked, SourceImport.Options(true, "أذكار", true, null, names)).single()
        assertEquals(morning, dao.werd(combined)!!.groupId)
        assertEquals(listOf(1, 1, 33, 10), dao.items(combined).map { it.target })
        assertEquals(2, dao.groups().size)                                   // no duplicate group created
    }

    @Test fun groupRemindersCoverGroupAndRespectAdjustments() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.settings.update { it.copy(onboarded = true, seeded = true) }
        val g = app.repo.addGroup("المساء")
        val a = app.repo.saveWerd(Werd(title = "راتب الحداد", groupId = g), listOf(WerdItem(werdId = 0, position = 0, text = "ذكر", target = 1)))
        val b = app.repo.saveWerd(Werd(title = "الوِرد اللطيف", groupId = g), listOf(WerdItem(werdId = 0, position = 0, text = "ذكر", target = 1)))
        val rid = app.repo.saveReminder(Reminder(groupId = g, type = ReminderType.DAILY, hour = 19, minute = 0))
        // both awrad inherit it
        val d = app.repo.dashboard.first()
        assertTrue(d.werds.all { w -> w.reminders.any { it.id == rid } })
        val nm = app.getSystemService(NotificationManager::class.java)
        ReminderScheduler.fire(app, dao.reminder(rid)!!, "", respectCooldown = false)
        val n1 = shadowOf(nm).allNotifications.single()
        assertTrue(n1.extras.getString("android.title")!!.contains("المساء"))
        assertTrue(n1.extras.getCharSequence("android.text")!!.contains("2"))
        // A done → only B is listed; both done → silent
        nm.cancelAll()
        app.repo.setAll(a, true)
        ReminderScheduler.fire(app, dao.reminder(rid)!!, "", respectCooldown = false)
        assertTrue(shadowOf(nm).allNotifications.single().extras.getCharSequence("android.text")!!.contains("الوِرد اللطيف"))
        nm.cancelAll()
        app.repo.setAll(b, true)
        ReminderScheduler.fire(app, dao.reminder(rid)!!, "", respectCooldown = false)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        // adjust for A: A gets its own copy and leaves the group reminder
        val own = app.repo.customiseGroupReminder(rid, a)
        assertTrue(dao.reminder(rid)!!.excludes(a))
        assertEquals(a, dao.reminder(own)!!.werdId)
        val d2 = app.repo.dashboard.first()
        val ra = d2.werds.first { it.werd.id == a }.reminders.map { it.id }
        assertEquals(listOf(own), ra)
        // switching B off for the group reminder
        app.repo.setGroupReminderApplies(rid, b, false)
        assertTrue(app.repo.dashboard.first().werds.first { it.werd.id == b }.reminders.none { it.id == rid })
        // deleting the group removes its reminders
        app.repo.deleteGroup(g)
        assertNull(dao.reminder(rid))
    }
}
