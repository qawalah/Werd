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
import com.werd.app.sources.BookFile
import com.werd.app.sources.Sources
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Library and family flows of the phone, against the reference server (docs/server-api.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudTest {
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private val dao get() = app.db.dao()
    private lateinit var fake: FakeWerdServer

    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        System.setProperty("http.keepAlive", "false")   // each test has its own server; never reuse a connection to the last one
        fake = FakeWerdServer().start()
        WerdApi.retryBaseMs = 10
        WerdApi.connectTimeoutMs = 2_000; WerdApi.readTimeoutMs = 5_000; WerdApi.guessTimeoutMs = 2_000
        app.cloud.store.update { it.copy(serverOverride = fake.url) }
    }
    @After fun tearDown() { fake.stop() }

    private suspend fun book(title: String, checkedAll: Boolean): Long {
        val sid = dao.insertSource(Source(title = title, kind = Sources.KIND_TEXT, path = "/none", pageCount = 10, pagesDone = 10, status = "DONE", provider = "gemini"))
        dao.insertSourceItems((0 until 7).map { i ->
            SourceItem(sourceId = sid, position = i, page = i + 1, title = "دعاء $i", text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ رقم $i", count = i + 1,
                occasion = "morning", checked = checkedAll || i != 3)
        })
        return sid
    }

    @Test fun publishSearchPreviewDownloadReport() = runBlocking {
        val unchecked = book("كتاب لم يُراجع", checkedAll = false)
        try { app.cloud.publish(unchecked, ""); fail() } catch (e: CloudException) { assertEquals(CloudException.Kind.INVALID, e.kind) }
        assertTrue(fake.books.isEmpty())

        app.cloud.ensureAccount("أبو محمد")
        val sid = book("حصن المسلم", checkedAll = true)
        val id = app.cloud.publish(sid, "القحطاني")
        assertEquals(id, dao.source(sid)!!.libraryId)
        // publishing the same book again links to the existing entry
        val again = book("حصن المسلم", checkedAll = true)
        assertEquals(id, app.cloud.publish(again, ""))
        assertEquals(1, fake.books.size)

        // search ignores tashkeel and hamza forms
        val page = app.cloud.search("حصن")
        assertEquals(1, page.total); assertEquals("القحطاني", page.books[0].author); assertEquals("أبو محمد", page.books[0].publisher)
        assertEquals(0, app.cloud.search("لا يوجد").total)

        // preview is not a download
        val pv = app.cloud.api().previewBook(id)
        assertEquals(5, pv.getJSONArray("items").length()); assertEquals(0, fake.books[id]!!.downloads)

        // someone else downloads it: a finished source with every tick, no AI involved
        dao.deleteSource(sid); dao.deleteSource(again)
        val local = app.cloud.download(id)
        val s = dao.source(local)!!
        assertEquals(Sources.KIND_IMPORTED, s.kind); assertEquals("DONE", s.status); assertEquals(id, s.libraryId); assertEquals("القحطاني", s.author)
        val items = dao.sourceItems(local)
        assertEquals(7, items.size); assertTrue(items.all { it.checked }); assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), items.map { it.count })
        assertEquals(1, fake.books[id]!!.downloads)
        assertEquals(local, app.cloud.download(id))           // second tap opens the copy we have
        assertEquals(1, fake.books[id]!!.downloads)

        app.cloud.report(id, "كلمة ناقصة")
        assertEquals("كلمة ناقصة", fake.reports.single().getString("reason"))
    }

    @Test fun familyProgressAndSendingAwrad() = runBlocking {
        // this phone: two awrad, one finished today
        val w1 = app.repo.saveWerd(Werd(title = "أذكار الصباح"), listOf(WerdItem(werdId = 0, position = 0, text = "سبحان الله", target = 3)))
        app.repo.saveWerd(Werd(title = "ورد السحر"), listOf(WerdItem(werdId = 0, position = 0, text = "أستغفر الله", target = 100)))
        app.repo.setAll(w1, true)

        val fam = app.cloud.createFamily("عائلة أبي محمد", "أبو محمد", share = true)
        assertTrue(app.cloud.store.value.inFamily); assertEquals(fam.inviteCode, app.cloud.store.value.inviteCode)

        // a second phone joins with the code and shares its own progress
        val other = WerdApi(fake.url)
        val (_, tok) = other.register("أم محمد")
        val o = WerdApi(fake.url, tok)
        assertEquals(fam.familyId, o.joinFamily(fam.inviteCode.lowercase()).familyId)
        o.putProgress(true, Progress(java.time.LocalDate.now().toString(), 0, 5, 2, 1, listOf(WerdProgress("سورة الملك", 30, 30, true))))

        // each sees the other
        val mine = app.cloud.refreshFamily()!!
        assertEquals(2, mine.members.size)
        val her = mine.members.first { !it.isMe }
        assertEquals("أم محمد", her.displayName); assertEquals(5, her.progress!!.streak); assertEquals("سورة الملك", her.progress!!.werds[0].title)
        val me = o.myFamily().members.first { !it.isMe }
        assertEquals(2, me.progress!!.due); assertEquals(1, me.progress!!.done)
        assertTrue(me.progress!!.werds.first { it.title == "أذكار الصباح" }.completed)

        // turning sharing off hides it
        app.cloud.setSharing(false)
        assertNull(o.myFamily().members.first { !it.isMe }.progress)

        // she sends a werd → it lands in this phone's inbox → added as a werd → gone from the server
        o.sendWerd(app.cloud.store.value.userId, "ورد النوم", "قبل النوم", listOf("باسمك ربي وضعت جنبي" to 1, "سبحان الله" to 33))
        val fresh = app.cloud.syncInbox()
        assertEquals(1, fresh.size); assertEquals("أم محمد", fresh[0].fromName)
        assertTrue(app.cloud.syncInbox().isEmpty())              // not duplicated on the next sync
        val inbox = dao.inbox().single()
        val wid = app.cloud.accept(inbox, null)
        assertEquals("ورد النوم", dao.werd(wid)!!.title)
        assertEquals(listOf(1, 33), dao.items(wid).map { it.target })
        assertTrue(dao.inbox().isEmpty()); assertTrue(fake.inbox.isEmpty())

        // sending to someone outside the family is refused
        val stranger = WerdApi(fake.url).register("غريب").first
        try { app.cloud.sendWerd(stranger, wid, ""); fail() } catch (e: CloudException) { assertEquals(CloudException.Kind.NOT_FOUND, e.kind) }

        app.cloud.leaveFamily()
        assertFalse(app.cloud.store.value.inFamily)
        assertEquals(1, o.myFamily().members.size)
    }

    @Test fun forgottenAccountSignsUpAgainAndNoServerMeansSoon() = runBlocking {
        app.cloud.ensureAccount("أبو محمد")
        fake.users.clear()                                        // the server lost its accounts
        app.cloud.ensureAccount()
        val sid = book("كتاب", checkedAll = true)
        assertNotNull(app.cloud.publish(sid, ""))                 // 401 → new account → retried
        assertEquals(1, fake.users.size)

        app.cloud.store.update { it.copy(serverOverride = "") }   // no server built in (BuildConfig.SERVER_URL is empty in tests)
        assertFalse(app.cloud.store.value.configured)
        try { app.cloud.search(""); fail() } catch (e: CloudException) { assertEquals(CloudException.Kind.NOT_CONFIGURED, e.kind) }
    }

    @Test fun addressWithoutSchemeWorks() = runBlocking {
        val bare = fake.url.removePrefix("http://")                  // "127.0.0.1:12345", as typed in Settings
        app.cloud.store.update { it.copy(serverOverride = bare) }
        assertEquals(fake.url, app.cloud.store.value.serverUrl)
        assertTrue(app.cloud.store.value.configured)
        assertFalse(app.cloud.store.value.insecure)                  // a home-network address: no warning
        assertEquals(0, app.cloud.search("").total)
        app.cloud.store.update { it.copy(serverOverride = "http://203.0.113.9:8080") }
        assertTrue(app.cloud.store.value.insecure)                   // plain http over the internet: warned
        app.cloud.store.update { it.copy(serverOverride = "100.101.102.103:8080") }
        assertFalse(app.cloud.store.value.insecure)                  // Tailscale encrypts by itself
        assertTrue(com.werd.app.cloud.isTailscale("http://100.101.102.103:8080"))
        assertTrue(com.werd.app.cloud.isTailscale("https://nas.tail1234.ts.net"))
        assertFalse(com.werd.app.cloud.isTailscale("http://192.168.1.20:8080"))
    }

    /** An address nothing listens on (connection refused at once). */
    private fun deadUrl(): String {
        val port = java.net.ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    @Test fun switchesToTailscaleWhenHomeIsUnreachableAndRemembers() = runBlocking {
        val home = deadUrl()                                         // away from home: the LAN address doesn't answer
        app.cloud.store.update { it.copy(serverOverride = home, altServers = fake.url, activeServer = "") }
        assertEquals(listOf(home, fake.url), app.cloud.store.value.candidates)
        assertEquals(0, app.cloud.search("").total)                  // answered through the second address
        assertEquals(fake.url, app.cloud.store.value.activeServer)
        assertEquals(fake.url, app.cloud.store.value.candidates.first())   // tried first next time
        app.cloud.ensureAccount("أبو محمد")                          // writes work the same way
        assertEquals(1, fake.users.size)

        // back home with Tailscale off: the remembered address fails, the home one answers again
        app.cloud.store.update { it.copy(serverOverride = fake.url, altServers = deadUrl(), activeServer = it.altServers) }
        assertEquals(0, app.cloud.search("").total)
        assertEquals(fake.url, app.cloud.store.value.activeServer)
    }

    @Test fun httpsTypedForAPlainHttpServerStillConnects() = runBlocking {
        val typed = fake.url.replace("http://", "https://")
        app.cloud.store.update { it.copy(serverOverride = typed) }
        assertEquals(0, app.cloud.search("").total)
        assertEquals(fake.url, app.cloud.store.value.activeServer)   // the form that worked
        val p = app.cloud.probe(typed)
        assertEquals(fake.url, p.url); assertEquals("fake", p.version)
    }

    @Test fun learnsTheServersTailscaleAddress() = runBlocking {
        fake.addresses += fake.url to "lan"
        fake.addresses += "http://100.101.102.103:8080" to "tailscale"
        app.cloud.learnAddresses()
        val s = app.cloud.store.value
        assertEquals(listOf("http://100.101.102.103:8080"), s.alternates)   // the home address itself isn't repeated
        assertEquals(listOf(fake.url, "http://100.101.102.103:8080"), s.candidates)
    }

    @Test fun explainsWhyAnAddressFails() = runBlocking {
        WerdApi.retryBaseMs = 1
        assertEquals("refused", app.cloud.probe(deadUrl()).reason)
        assertNull(app.cloud.probe(deadUrl()).url)
        // some other program on that port (the NAS's own web page)
        val other = okhttp3.mockwebserver.MockWebServer()
        other.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("<html>DiskStation</html>")
        }
        other.start()
        try {
            val url = other.url("").toString().trimEnd('/')
            assertEquals("not_werd", app.cloud.probe(url).reason)
            // …and the app moves on to the real server
            app.cloud.store.update { it.copy(serverOverride = url, altServers = fake.url) }
            assertEquals(0, app.cloud.search("").total)
            assertEquals(fake.url, app.cloud.store.value.activeServer)
            // nothing reachable: the error lists each address and why
            app.cloud.store.update { it.copy(serverOverride = deadUrl(), altServers = url, activeServer = "") }
            try { app.cloud.search(""); fail() } catch (e: CloudException) {
                assertEquals(CloudException.Kind.NETWORK, e.kind)
                assertEquals(listOf("refused", "not_werd"), e.tried.take(2).map { it.second })
            }
        } finally { other.shutdown() }
    }

    @Test fun bookFileRoundTrip() = runBlocking {
        val sid = book("الأذكار", checkedAll = false)
        val src = dao.source(sid)!!.copy(author = "النووي")
        val text = BookFile.toJson(src, dao.sourceItems(sid)).toString(1)
        assertTrue(BookFile.looksLikeBook(text))
        assertFalse(BookFile.looksLikeBook("{\"items\": []}"))
        val b = BookFile.parse(text)
        assertEquals("الأذكار", b.title); assertEquals("النووي", b.author); assertEquals(7, b.items.size); assertEquals(6, b.checked)
        val id = BookFile.import(dao, b)
        val items = dao.sourceItems(id)
        assertEquals(dao.sourceItems(sid).map { it.text }, items.map { it.text })
        assertEquals(dao.sourceItems(sid).map { it.checked }, items.map { it.checked })
        assertEquals("الأذكار.werdbook", BookFile.fileName("الأذكار"))
        assertEquals("a b.werdbook", BookFile.fileName("a/b"))
        try { BookFile.parse("{\"format\":\"werd-book\",\"version\":99,\"items\":[]}"); fail() } catch (_: BookFile.FormatException) {}
        try { BookFile.parse("not json"); fail() } catch (_: BookFile.FormatException) {}
    }
}
