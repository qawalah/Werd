package com.werd.app

import androidx.test.core.app.ApplicationProvider
import com.werd.app.cloud.CloudException
import com.werd.app.cloud.Progress
import com.werd.app.cloud.WerdApi
import com.werd.app.cloud.WerdProgress
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.Werd
import com.werd.app.data.WerdItem
import com.werd.app.sources.Sources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's library and family code against a real werd-server (Windows exe, Docker, or `go run`).
 * Skipped unless started with  -PwerdTestServer=http://127.0.0.1:8080
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealServerTest {
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private val dao get() = app.db.dao()
    private val url = System.getProperty("werd.server").orEmpty()
    private val tag = System.nanoTime().toString().takeLast(6)   // keeps runs against the same server apart

    @Before fun setUp() {
        assumeTrue("no -PwerdTestServer given", url.startsWith("http"))
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        System.setProperty("http.keepAlive", "false")
        app.cloud.store.update { it.copy(serverOverride = url) }
    }

    private suspend fun book(title: String, checkedAll: Boolean): Long {
        val sid = dao.insertSource(Source(title = title, kind = Sources.KIND_TEXT, path = "/none", pageCount = 10, pagesDone = 10, status = "DONE", provider = "gemini"))
        dao.insertSourceItems((0 until 7).map { i ->
            SourceItem(sourceId = sid, position = i, page = i + 1, title = "دعاء $i", text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ $tag $i", count = i + 1,
                occasion = "morning", checked = checkedAll || i != 3)
        })
        return sid
    }

    @Test fun libraryRoundTrip() = runBlocking {
        app.cloud.ensureAccount("أبو محمد")
        try { app.cloud.publish(book("مسودة $tag", false), ""); fail() } catch (e: CloudException) { assertEquals(CloudException.Kind.INVALID, e.kind) }
        val sid = book("حصن المسلم $tag", true)
        val id = app.cloud.publish(sid, "القحطاني")
        val dup = book("حصن المسلم $tag", true)
        assertEquals(id, app.cloud.publish(dup, ""))                                // duplicate → the same entry
        val hit = app.cloud.search("حِصْن المسلم $tag").books.single()
        assertEquals(id, hit.id); assertEquals("القحطاني", hit.author); assertEquals("أبو محمد", hit.publisher); assertEquals(0, hit.downloads)
        assertEquals(5, app.cloud.api().previewBook(id).getJSONArray("items").length())
        dao.deleteSource(sid); dao.deleteSource(dup)                                 // as if on another phone
        val local = app.cloud.download(id)
        val items = dao.sourceItems(local)
        assertEquals(7, items.size); assertTrue(items.all { it.checked }); assertEquals(Sources.KIND_IMPORTED, dao.source(local)!!.kind)
        assertEquals(1, app.cloud.search("حصن المسلم $tag").books.single().downloads)
        app.cloud.report(id, "كلمة ناقصة")
    }

    @Test fun familyRoundTrip() = runBlocking {
        val w1 = app.repo.saveWerd(Werd(title = "أذكار الصباح"), listOf(WerdItem(werdId = 0, position = 0, text = "سبحان الله", target = 3)))
        app.repo.setAll(w1, true)
        val fam = app.cloud.createFamily("عائلة $tag", "أبو محمد", share = true)
        val o = WerdApi(url, WerdApi(url).register("أم محمد").second)
        assertEquals(fam.familyId, o.joinFamily(fam.inviteCode.lowercase().replace("-", "")).familyId)
        o.putProgress(true, Progress(java.time.LocalDate.now().toString(), 0, 5, 2, 1, listOf(WerdProgress("سورة الملك", 30, 30, true))))
        val her = app.cloud.refreshFamily()!!.members.first { !it.isMe }
        assertEquals("أم محمد", her.displayName); assertEquals(5, her.progress!!.streak)
        assertTrue(o.myFamily().members.first { !it.isMe }.progress!!.werds.first { it.title == "أذكار الصباح" }.completed)
        app.cloud.setSharing(false)
        assertEquals(null, o.myFamily().members.first { !it.isMe }.progress)

        o.sendWerd(app.cloud.store.value.userId, "ورد النوم", "", listOf("باسمك ربي وضعت جنبي" to 1, "سبحان الله" to 33))
        assertEquals(1, app.cloud.syncInbox().size)
        val wid = app.cloud.accept(dao.inbox().single(), null)
        assertEquals(listOf(1, 33), dao.items(wid).map { it.target })
        assertTrue(o.inbox().isEmpty() || app.cloud.api().inbox().isEmpty())
        app.cloud.leaveFamily(); assertFalse(app.cloud.store.value.inFamily)
        o.leaveFamily()
    }

    /** A werd shared to the family library with its reminders; another phone adds it, keeping only some of them. */
    /** 1.15: an account with a password; the same name signs in again from a "new phone" instead of becoming a new member. */
    @Test fun signInRoundTrip() = runBlocking {
        val name = "قارئ $tag"
        app.cloud.store.update { it.copy(userId = "", token = "", hasPassword = false, familyId = "") }
        app.cloud.createAccount(name, "sabr1234")
        val first = app.cloud.store.value.userId
        assertTrue(app.cloud.store.value.hasPassword)
        try { WerdApi(url).register(name, "other123"); fail("same name made twice") } catch (e: CloudException) { assertEquals("name_taken", e.code) }
        app.cloud.createFamily("بيت $tag", name, share = true)
        // a new phone: nothing stored, signs in with the name (tashkeel and alef forms don't matter) and gets the family back
        app.cloud.store.update { it.copy(userId = "", token = "", familyId = "", familyName = "", hasPassword = false) }
        try { app.cloud.signIn(name, "wrong!!"); fail("wrong password accepted") } catch (e: CloudException) { assertEquals("wrong_password", e.code) }
        app.cloud.signIn(name, "sabr1234")
        assertEquals(first, app.cloud.store.value.userId)
        assertTrue(app.cloud.store.value.inFamily)
        assertTrue(app.cloud.refreshAccount()!!.hasPassword)
        // change the password, sign the other phones out, sign this one out
        app.cloud.setPassword("sabr5678", "sabr1234")
        app.cloud.signOutOthers()
        app.cloud.signOut()
        assertFalse(app.cloud.store.value.signedIn)
        app.cloud.signIn(name, "sabr5678")
        assertEquals(first, app.cloud.store.value.userId)
    }

    @Test fun sharedWerdRoundTrip() = runBlocking {
        val gid = app.repo.addGroup("الصباح $tag")
        val w = app.repo.saveWerd(Werd(title = "ورد $tag", groupId = gid, goalDaysMask = 0b0011111),
            listOf(WerdItem(werdId = 0, position = 0, text = "سُبْحَانَ اللَّهِ $tag", target = 33), WerdItem(werdId = 0, position = 1, text = "الحمد لله", target = 33)))
        app.repo.saveReminder(com.werd.app.data.Reminder(werdId = w, type = com.werd.app.data.ReminderType.DAILY, hour = 6, minute = 15))
        app.repo.saveReminder(com.werd.app.data.Reminder(werdId = w, type = com.werd.app.data.ReminderType.WIFI, ssid = "Home-$tag"))
        app.cloud.createFamily("عائلة $tag", "أبو محمد", share = false)
        // without the place reminders: only the time one goes
        val id = app.cloud.shareWerd(w, "family", keepSettings = true, includePlaces = false)
        assertEquals(id, app.cloud.shareWerd(w, "family", keepSettings = true, includePlaces = false))   // shared twice → the same entry
        val sw = app.cloud.sharedWerd(id)
        assertEquals(2, sw.lines.size); assertTrue(sw.mine); assertEquals("family", sw.visibility)
        val rem = app.cloud.sharedReminders(sw.settings)
        assertEquals(listOf("DAILY"), rem.map { it.optString("type") })
        // a stranger can't see it until it is made public
        val stranger = WerdApi(url, WerdApi(url).register("غريب").second)
        assertTrue(stranger.searchWerds(tag).werds.isEmpty())
        app.cloud.moveSharedWerd(id, "public")
        assertEquals(id, stranger.searchWerds(tag).werds.single().id)
        // added here (as if on another phone), keeping the group but not the days or the reminder
        val added = app.cloud.addShared(id, com.werd.app.cloud.CloudRepo.Keep(group = true, days = false, reminders = emptySet()))
        val nw = dao.werd(added)!!
        assertEquals(gid, nw.groupId); assertEquals(com.werd.app.data.ALL_DAYS, nw.goalDaysMask)
        assertEquals(listOf(33, 33), dao.items(added).map { it.target })
        assertTrue(dao.reminders().none { it.werdId == added })
        // keeping the reminder
        val again = app.cloud.addShared(id, com.werd.app.cloud.CloudRepo.Keep(reminders = setOf(0)))
        assertEquals(listOf(6 to 15), dao.reminders().filter { it.werdId == again }.map { it.hour to it.minute })
        // a text to read stays one
        val t = app.repo.saveWerd(Werd(title = "قصيدة $tag", textOnly = true), listOf(WerdItem(werdId = 0, position = 0, text = "بيت من الشعر $tag", target = 1)))
        val tid = app.cloud.shareWerd(t, "public", keepSettings = false, includePlaces = false)
        assertTrue(dao.werd(app.cloud.addShared(tid, com.werd.app.cloud.CloudRepo.Keep()))!!.textOnly)
        app.cloud.withdrawSharedWerd(id); app.cloud.withdrawSharedWerd(tid)
        assertTrue(stranger.searchWerds(tag).werds.isEmpty())
    }
}
