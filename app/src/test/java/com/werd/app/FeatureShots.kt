package com.werd.app

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.cloud.Progress
import com.werd.app.cloud.WerdApi
import com.werd.app.cloud.WerdProgress
import com.werd.app.data.Seed
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.sources.BookFile
import com.werd.app.sources.Sources
import com.werd.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 1.7 screens (library, rescan, family, inbox), driven against the reference server. → app/screenshots/f_<lang>_*.png */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureShots {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)
    private lateinit var lang: String
    private lateinit var fake: FakeWerdServer

    @After fun tearDown() { fake.stop() }

    private fun shot(n: String) { compose.waitForIdle(); Thread.sleep(600); compose.waitForIdle(); compose.onRoot().captureRoboImage("screenshots/f_${lang}_$n.png") }
    /** Dialogs with text fields never report idle under Robolectric: capture them with a frozen clock. */
    private fun frozen(n: String, open: () -> Unit, close: () -> Unit, expect: String) {
        open()
        compose.mainClock.autoAdvance = false
        repeat(6) { compose.mainClock.advanceTimeBy(500); Thread.sleep(150) }
        compose.onRoot().captureRoboImage("screenshots/f_${lang}_$n.png")
        close(); compose.mainClock.advanceTimeBy(1000); compose.mainClock.autoAdvance = true
    }
    private fun wait(t: String) = try {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        compose.onRoot().captureRoboImage("screenshots/f_${lang}_FAIL.png"); throw AssertionError("waiting for $t", e)
    }
    private fun tab(id: Int) = compose.onNode(hasText(s(id)) and hasClickAction()).performClick()
    private fun back() = compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()

    private fun book(title: String, author: String, n: Int, offset: Int = 0) = BookFile.toJson(
        Source(title = title, kind = Sources.KIND_PDF, path = "", pageCount = 120, provider = "gemini", model = "gemini-3.8-pro", author = author),
        listOf("أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ", "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ", "حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ", "اللَّهُمَّ بِكَ أَصْبَحْنَا", "رَضِيتُ بِاللَّهِ رَبًّا",
            "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ").take(n).mapIndexed { i, t ->
            SourceItem(sourceId = 0, position = i, page = 10 + i + offset, title = listOf("الصباح", "التسبيح", "التوكل", "الصباح", "الرضا", "الحفظ")[i],
                text = t, count = listOf(1, 100, 7, 1, 3, 3)[i], occasion = "morning", checked = true)
        })

    private fun seed(arabic: Boolean) = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        System.setProperty("http.keepAlive", "false")   // each test has its own server; never reuse a connection to the last one
        fake = FakeWerdServer().start()
        WerdApi.retryBaseMs = 10
        app.settings.update { it.copy(onboarded = true, language = if (arabic) "ar" else "en", aiKeyGemini = "demo", aiKeyClaude = "demo") }
        app.cloud.store.update { it.copy(serverOverride = fake.url) }
        app.repo.seedIfNeeded(arabic); StoreDemo.replaceRatib(app)
        // the library, as other users filled it
        val pub = WerdApi(fake.url, WerdApi(fake.url).register("أبو عبدالله").second)
        pub.publishBook(book("حصن المسلم", "سعيد بن وهف القحطاني", 6))
        pub.publishBook(book("الأذكار", "الإمام النووي", 5, 1))
        pub.publishBook(book("الوابل الصيب", "ابن قيم الجوزية", 4, 2))
        fake.books.values.forEachIndexed { i, b -> b.downloads = listOf(1520, 870, 312)[i]; b.madhhab = listOf("general", "shafii", "hanbali")[i] }
        // a book of mine, finished and proofread
        val dao = app.db.dao()
        val sid = dao.insertSource(Source(title = "دلائل الخيرات", kind = Sources.KIND_IMAGES, path = "/none", pageCount = 96, pagesDone = 96, status = "DONE",
            provider = "gemini", model = "gemini-3.8-flash"))
        dao.insertSourceItems(listOf(
            SourceItem(sourceId = sid, position = 0, page = 12, title = "الصلاة الإبراهيمية", text = "اللَّهُمَّ صَلِّ عَلَى مُحَمَّدٍ وَعَلَى آلِ مُحَمَّدٍ", count = 10, occasion = "friday", checked = true),
            SourceItem(sourceId = sid, position = 1, page = 14, title = "صلاة النور", text = "اللَّهُمَّ صَلِّ عَلَى سَيِّدِنَا مُحَمَّدٍ النُّورِ الذَّاتِيِّ", count = 1, occasion = "general", checked = true),
            SourceItem(sourceId = sid, position = 2, page = 15, title = "حزب يوم الجمعة", text = "اللَّهُمَّ إِنِّي أَسْأَلُكَ مِنْ خَيْرِ مَا سَأَلَكَ مِنْهُ", count = 1, occasion = "friday"),
        ))
        // family: this phone and two others; one of them sent a werd
        app.cloud.createFamily(if (arabic) "عائلة أبي محمد" else "Abu Muhammad's family", if (arabic) "أبو محمد" else "Abu Muhammad", share = true)
        val ratib = dao.werds().first { it.title == StoreDemo.EVENING_TITLE }
        dao.items(ratib.id).take(10).forEach { app.repo.increment(ratib.id, it.id, it.target) }
        app.repo.setAll(dao.werds().first { it.title == Seed.TASBIH_TITLE }.id, true)
        app.cloud.syncProgress()
        val today = java.time.LocalDate.now().toString()
        val umm = WerdApi(fake.url, WerdApi(fake.url).register(if (arabic) "أم محمد" else "Umm Muhammad").second)
        umm.joinFamily(app.cloud.store.value.inviteCode)
        umm.putProgress(true, Progress(today, 0, 23, 3, 3, listOf(WerdProgress("أذكار الصباح", 33, 33, true),
            WerdProgress("سورة الملك", 30, 30, true), WerdProgress("التسبيح بعد الصلاة", 99, 99, true))))
        val son = WerdApi(fake.url, WerdApi(fake.url).register(if (arabic) "عبدالله" else "Abdullah").second)
        son.joinFamily(app.cloud.store.value.inviteCode)
        son.putProgress(true, Progress(today, 0, 4, 2, 1, listOf(WerdProgress("أذكار الصباح", 33, 33, true), WerdProgress("أذكار المساء", 12, 33, false))))
        // roles: Umm Muhammad is a parent too; Abdullah is a child, with two weeks of history
        app.cloud.setRole(fake.users.values.first { it.name == (if (arabic) "أم محمد" else "Umm Muhammad") }.id, true)
        val kid = fake.users.values.first { it.name == (if (arabic) "عبدالله" else "Abdullah") }
        for (b in 1..13) {
            val d = java.time.LocalDate.now().minusDays(b.toLong()).toString()
            val done = if (b == 3 || b == 8) 1 else if (b == 11) 0 else 2
            kid.days[d] = org.json.JSONObject().put("day", d).put("streak", if (b < 3) 3 - b else 1).put("due", 2).put("done", done)
                .put("werds", org.json.JSONArray().put(org.json.JSONObject().put("title", "أذكار الصباح").put("done", if (done > 0) 33 else 10).put("total", 33).put("completed", done > 0))
                    .put(org.json.JSONObject().put("title", "أذكار المساء").put("done", if (done > 1) 33 else 20).put("total", 33).put("completed", done > 1)))
        }
        // a family-only book
        umm.publishBook(book(if (arabic) "أذكار بيتنا" else "Our home adhkar", if (arabic) "أم محمد" else "Umm Muhammad", 3).put("visibility", "family").put("madhhab", "general"))
        fake.voices += Triple("azure:ar-SA-HamedNeural", "حامد", "Arabic (Saudi Arabia) · Male")
        fake.voices += Triple("azure:ar-SA-ZariyahNeural", "زارية", "Arabic (Saudi Arabia) · Female")
        fake.voices += Triple("gemini:Charon", "Charon", "")
        umm.sendWerd(app.cloud.store.value.userId, "ورد السحر", if (arabic) "اقرأه قبل الفجر" else "Before Fajr",
            listOf("أَسْتَغْفِرُ اللَّهَ" to 100, "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ" to 100))
        app.cloud.syncInbox()
    }

    private fun tour() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(localeTag(app.settings.value.language)))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            wait("ورد السحر")
            shot("1_home_inbox")

            tab(R.string.tab_sources); wait("دلائل الخيرات")
            compose.onAllNodesWithText(s(R.string.lib_title))[0].performClick()
            wait("الوابل الصيب")
            shot("2_library")
            compose.onAllNodesWithText("حصن المسلم")[0].performClick()
            // (the preview sometimes doesn't reach the screen under Robolectric: reopen until it does)
            run {
                repeat(4) { attempt ->
                    if (attempt > 0) compose.onAllNodesWithText("حصن المسلم")[0].performClick()
                    wait(s(R.string.lib_download))
                    val ok = runCatching { compose.waitUntil(6_000) { compose.onAllNodesWithText("رَضِيتُ", substring = true).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
                    if (ok) return@run
                    compose.onAllNodesWithText(s(R.string.cancel))[0].performClick(); compose.waitForIdle()
                }
                error("preview never shown")
            }
            shot("3_library_book")
            compose.onAllNodesWithText(s(R.string.lib_download))[0].performClick()
            wait(s(R.string.src_from_library))
            shot("4_downloaded")
            back(); wait(s(R.string.lib_mine))
            compose.onAllNodesWithText(s(R.string.lib_mine))[0].performClick()
            compose.onAllNodesWithText("دلائل الخيرات")[0].performClick()
            wait("صلاة النور")
            compose.onAllNodesWithContentDescription(s(R.string.more_options))[0].performClick()
            shot("5_book_menu")
            compose.onAllNodesWithContentDescription(s(R.string.more_options))[0].performClick()   // close the menu
            back()

            tab(R.string.tab_stats); wait(s(R.string.total_recitations))
            compose.onAllNodesWithText(s(R.string.fam_title))[0].performClick()
            val kidName = if (lang == "ar") "عبدالله" else "Abdullah"
            wait(kidName)
            shot("8_family")
            back()

            // voices
            tab(R.string.tab_settings); wait(s(R.string.s_voice))
            app.voice.update { it.copy(engine = com.werd.app.voice.VoicePrefs.GEMINI, geminiVoice = "Charon", geminiStyle = if (lang == "ar") "هادئ وخاشع" else "calm and reverent") }
            compose.onAllNodesWithText(s(R.string.s_voice))[0].performScrollTo().performClick()
            wait(s(R.string.ve_style))
            compose.onAllNodesWithText(s(R.string.ve_style))[0].performScrollTo()
            shot("12_voices")
            app.voice.update { it.copy(engine = com.werd.app.voice.VoicePrefs.SERVER) }
            wait(s(R.string.ve_server_lib))
            compose.onAllNodesWithText(s(R.string.ve_server_lib))[0].performScrollTo()
            shot("13_voice_server")
            app.voice.update { it.copy(engine = com.werd.app.voice.VoicePrefs.SYSTEM) }
            // the server: home address and Tailscale
            app.cloud.store.update { it.copy(serverOverride = "192.168.1.20:8080", altServers = "http://100.101.102.103:8080", activeServer = "") }
            wait(s(R.string.cloud_address_remote))
            compose.onAllNodesWithText(s(R.string.cloud_address_remote))[0].performScrollTo()
            compose.onAllNodesWithText(s(R.string.cloud_server))[0].performScrollTo()
            // bring the server card up to the middle of the screen
            compose.onAllNodesWithText(s(R.string.cloud_server))[0].performTouchInput { swipeUp(startY = centerY + 900f, endY = centerY - 300f, durationMillis = 600) }
            shot("14_server")
        }
        // a child's last two weeks (the member page on its own)
        app.cloud.store.update { it.copy(serverOverride = fake.url, altServers = "", activeServer = "") }
        val kid = fake.users.values.first { it.name == (if (lang == "ar") "عبدالله" else "Abdullah") }
        ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { sc ->
            sc.onActivity { act ->
                act.setContent {
                    com.werd.app.ui.theme.WerdTheme(com.werd.app.data.ThemeMode.LIGHT) {
                        com.werd.app.ui.family.MemberScreen(app, kid.id, kid.name, onBack = {})
                    }
                }
            }
            wait(s(R.string.fam_days_missed))
            shot("11_member_history")
        }
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun arabic() { lang = "ar"; seed(true); tour() }

    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun english() { lang = "en"; seed(false); tour() }
}
