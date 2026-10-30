package com.werd.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.cloud.WerdApi
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.Werd
import com.werd.app.data.WerdItem
import com.werd.app.sources.BookIndex
import com.werd.app.sources.Sources
import com.werd.app.sources.TwoPass
import com.werd.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.14: the book's index with folded supplications, two readings in the proofreader, texts to read, and shared awrad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V114Shots {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)
    private lateinit var fake: FakeWerdServer

    @After fun tearDown() { fake.stop() }

    private fun shot(n: String) {
        compose.waitForIdle(); Thread.sleep(600); compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/v114_$n.png")
    }
    private fun wait(t: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tab(id: Int) = compose.onNode(hasText(s(id)) and hasClickAction()).performClick()
    private fun back() = compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()

    private val BOOK = "الأذكار المأثورة"
    private val POEM = "قصيدة البردة"

    private fun seed() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        System.setProperty("http.keepAlive", "false")
        fake = FakeWerdServer().start()
        WerdApi.retryBaseMs = 10
        app.settings.update { it.copy(onboarded = true, language = "ar") }
        app.cloud.store.update { it.copy(serverOverride = fake.url) }
        app.repo.seedIfNeeded(true); StoreDemo.replaceRatib(app)
        val dao = app.db.dao()
        // a scanned book with its فهرس; one page read twice and the readings disagree on a word
        val sid = dao.insertSource(Source(title = BOOK, kind = Sources.KIND_IMPORTED, path = "", pageCount = 60, pagesDone = 60, status = "DONE",
            provider = Sources.OFFLINE, scanMode = TwoPass.TWO))
        val texts = listOf(
            Triple("أذكار الصباح", "أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ", 1),
            Triple("سيد الاستغفار", "اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ", 1),
            Triple("التسبيح", "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ", 100),
            Triple("دعاء الاستيقاظ", "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا بَعْدَ مَا أَمَاتَنَا وَإِلَيْهِ النُّشُورُ", 1),
            Triple("آية الكرسي", "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ", 1),
            Triple("راتب الحداد", "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ يُحْيِي وَيُمِيتُ", 3),
        )
        dao.insertSourceItems(texts.mapIndexed { i, (t, x, n) ->
            SourceItem(sourceId = sid, position = i, page = 8 + i * 3, title = t, text = x, count = n, occasion = if (i < 3) "morning" else "general",
                checked = i != 1, textOnly = n == 1 && i != 0)
        })
        val items = dao.sourceItems(sid)
        // the second item: the reading without tashkeel has «عهدك» where the other read «عهدك» as «عهذك»
        val flagged = items[1]
        val wrongText = flagged.text.replace("عَهْدِكَ", "عَهْذِكَ")
        dao.updateSourceItem(TwoPass.annotate(flagged.copy(text = wrongText),
            "اللهم انت ربي لا اله الا انت خلقتني وانا عبدك وانا على عهدك ووعدك ما استطعت"))
        val index = listOf(BookIndex.Entry("المقدمة", 3)) + texts.mapIndexed { i, (t, _, _) -> BookIndex.Entry(t, 4 + i * 3) } +
            BookIndex.Entry("الخاتمة", 58)
        dao.setSourceIndex(sid, BookIndex.encode(BookIndex.link(index, dao.sourceItems(sid))))
        // a text to read (no counter)
        app.repo.saveWerd(Werd(title = POEM, textOnly = true), listOf(
            WerdItem(werdId = 0, position = 0, text = "أَمِنْ تَذَكُّرِ جِيرَانٍ بِذِي سَلَمٍ ... مَزَجْتَ دَمْعاً جَرَى مِنْ مُقْلَةٍ بِدَمِ", target = 1),
            WerdItem(werdId = 0, position = 1, text = "أَمْ هَبَّتِ الرِّيحُ مِنْ تِلْقَاءِ كَاظِمَةٍ ... وَأَوْمَضَ الْبَرْقُ فِي الظَّلْمَاءِ مِنْ إِضَمِ", target = 1),
            WerdItem(werdId = 0, position = 2, text = "فَمَا لِعَيْنَيْكَ إِنْ قُلْتَ اكْفُفَا هَمَتَا ... وَمَا لِقَلْبِكَ إِنْ قُلْتَ اسْتَفِقْ يَهِمِ", target = 1),
        ))
        // a family, and awrad shared to the library by others
        app.cloud.createFamily("عائلة أبي محمد", "أبو محمد", share = true)
        val umm = WerdApi(fake.url, WerdApi(fake.url).register("أم محمد").second)
        umm.joinFamily(app.cloud.store.value.inviteCode)
        val settings = JSONObject().put("group", "بعد الصلاة").put("goalDaysMask", 127).put("colorIndex", 2)
            .put("reminders", JSONArray().put(JSONObject().put("type", "PRAYER").put("prayer", 2).put("offsetMin", 10))
                .put(JSONObject().put("type", "WIFI").put("ssid", "Masjid-Noor")))
        umm.shareWerd(JSONObject().put("title", "تسبيح بعد الصلاة").put("visibility", "family").put("werd", settings)
            .put("lines", JSONArray().put(JSONObject().put("text", "سُبْحَانَ اللَّهِ").put("count", 33)).put(JSONObject().put("text", "الْحَمْدُ لِلَّهِ").put("count", 33))
                .put(JSONObject().put("text", "اللَّهُ أَكْبَرُ").put("count", 34))))
        val pub = WerdApi(fake.url, WerdApi(fake.url).register("أبو عبدالله").second)
        pub.shareWerd(JSONObject().put("title", "ورد السحر").put("visibility", "public")
            .put("lines", JSONArray().put(JSONObject().put("text", "أَسْتَغْفِرُ اللَّهَ الْعَظِيمَ").put("count", 70))))
        pub.shareWerd(JSONObject().put("title", "المنفرجة").put("visibility", "public").put("textOnly", true)
            .put("lines", JSONArray().put(JSONObject().put("text", "اشْتَدِّي أَزْمَةُ تَنْفَرِجِي ... قَدْ آذَنَ لَيْلُكِ بِالْبَلَجِ").put("count", 1))))
        sid
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun tour() {
        seed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ar"))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            wait(POEM)
            compose.onAllNodesWithText(POEM)[0].performScrollTo()
            shot("1_home_text")
            compose.onAllNodesWithText(POEM)[0].performClick()
            wait(s(R.string.mark_read))
            shot("2_text_reader")
            back(); wait(POEM)

            tab(R.string.tab_sources); wait(BOOK)
            compose.onAllNodesWithText(BOOK)[0].performClick()
            wait(app.getString(R.string.index_title, 8).take(6))
            shot("3_book_index")
            compose.onAllNodesWithText("التسبيح")[0].performTouchInput { click(center) }   // the index link
            // it scrolls to the supplication and shows it in full
            compose.waitUntil(10_000) { compose.onAllNodesWithText("سُبْحَانَ اللَّهِ وَبِحَمْدِهِ").fetchSemanticsNodes().isNotEmpty() }
            shot("4_book_opened")
            compose.onAllNodesWithText(s(R.string.proof_title))[0].performClick()
            wait(s(R.string.proof_add_missed))
            shot("5_two_readings")
            back(); wait(BOOK); back()

            compose.onAllNodesWithText(s(R.string.lib_title))[0].performClick()
            wait(s(R.string.wlib_awrad))
            compose.onAllNodesWithText(s(R.string.wlib_awrad))[0].performClick()
            wait("تسبيح بعد الصلاة")
            shot("6_awrad_library")
            compose.onAllNodesWithText("تسبيح بعد الصلاة")[0].performClick()
            wait(s(R.string.wlib_add))
            shot("7_shared_werd")
            compose.onAllNodesWithText(s(R.string.cancel))[0].performClick(); compose.waitForIdle()

            tab(R.string.tab_home); wait(StoreDemo.EVENING_TITLE)
            compose.onAllNodesWithContentDescription(s(R.string.more_options))[1].performClick()
            compose.onAllNodesWithText(s(R.string.wlib_share))[0].performClick()
            wait(s(R.string.wlib_keep_settings))
            shot("8_share_dialog")
        }
    }
}
