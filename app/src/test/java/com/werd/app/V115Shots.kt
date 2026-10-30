package com.werd.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.cloud.WerdApi
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.sources.Proof
import com.werd.app.sources.Sources
import com.werd.app.sources.TwoPass
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

/** 1.15: the simpler proofreader (original + editing, alternatives in a popup), "Proofread with…", accounts, the slim counter. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V115Shots {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)
    private lateinit var fake: FakeWerdServer

    @After fun tearDown() { fake.stop() }

    private fun shot(n: String) {
        compose.waitForIdle(); Thread.sleep(700); compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/v115_$n.png")
    }
    private fun wait(t: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tab(id: Int) = compose.onNode(hasText(s(id)) and hasClickAction()).performClick()
    private fun back() = compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()

    private val BOOK = "الأذكار المأثورة"
    private val ISTIGHFAR = "اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ"
    private val KURSI = "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ لَهُ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ"

    /** A printed page: a running header with the page number, two supplications with headings, a footnote, the page number. */
    private fun bookPages(): java.io.File {
        val w = 1200; val h = 1700
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(0xFFF6F0E1.toInt())
        val font = androidx.core.content.res.ResourcesCompat.getFont(app, R.font.amiri_regular)
        val bold = androidx.core.content.res.ResourcesCompat.getFont(app, R.font.amiri_bold)
        val p = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2418.toInt(); typeface = font; textSize = 50f }
        fun block(text: String, y: Float, paint: android.text.TextPaint, center: Boolean = false): Float {
            val l = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, w - 160)
                .setAlignment(if (center) android.text.Layout.Alignment.ALIGN_CENTER else android.text.Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
            c.save(); c.translate(80f, y); l.draw(c); c.restore(); return y + l.height + 36
        }
        val small = android.text.TextPaint(p).apply { textSize = 34f; color = 0xFF6B5B3E.toInt() }
        val head = android.text.TextPaint(p).apply { typeface = bold; textSize = 58f; color = 0xFF7A1F1F.toInt() }
        var y = 60f
        y = block("$BOOK   ١٢", y, small, center = true)
        c.drawLine(80f, y - 14, w - 80f, y - 14, android.graphics.Paint().apply { color = 0xFFBFAE88.toInt(); strokeWidth = 2f })
        y += 30
        y = block("سيد الاستغفار", y, head)
        y = block("«$ISTIGHFAR (١)»", y, p)
        y = block("مرة واحدة", y, small)
        y += 20
        y = block("آية الكرسي", y, head)
        y = block("﴿$KURSI﴾", y, p)
        c.drawLine(80f, 1460f, 500f, 1460f, android.graphics.Paint().apply { color = 0xFF6B5B3E.toInt(); strokeWidth = 2f })
        block("(١) رواه البخاري.", 1480f, small)
        block("١٢", 1600f, small, center = true)
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val d = java.io.File(Sources.dir(app), "shot115").apply { mkdirs() }
        (1..20).forEach { java.io.File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", it)).writeBytes(bytes) }
        return d
    }

    private fun seed() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        System.setProperty("http.keepAlive", "false")
        fake = FakeWerdServer().start()
        WerdApi.retryBaseMs = 10
        app.settings.update { it.copy(onboarded = true, language = "ar", aiKeyGemini = "test-key") }
        app.cloud.store.update { it.copy(serverOverride = fake.url, displayName = "أبو حمزة") }
        app.cloud.ensureAccount("أبو حمزة")
        app.repo.seedIfNeeded(true); StoreDemo.replaceRatib(app)
        val dao = app.db.dao()
        val sid = dao.insertSource(Source(title = BOOK, kind = Sources.KIND_IMAGES, path = bookPages().path, pageCount = 20, pagesDone = 20,
            status = "DONE", provider = Sources.OFFLINE, scanMode = TwoPass.TWO, checkReader = "server:qari4"))
        // the reading with tashkeel misread «عهدك» as «عهذك» and «القيوم» as «الفيوم»; the server's model read them right
        val a = SourceItem(sourceId = sid, position = 0, page = 1, title = "سيد الاستغفار", text = ISTIGHFAR.replace("عَهْدِكَ", "عَهْذِكَ"),
            count = 1, textOnly = true, section = "أذكار الصباح", footnote = "(١) رواه البخاري.", region = "0.10-0.42")
        val b = SourceItem(sourceId = sid, position = 1, page = 1, title = "آية الكرسي", text = KURSI.replace("الْقَيُّومُ", "الْفَيُّومُ"),
            count = 1, textOnly = true, section = "أذكار الصباح", region = "0.42-0.80")
        val page = "سيد الاستغفار\nاللهم انت ربي لا اله الا انت خلقتني وانا عبدك وانا على عهدك ووعدك ما استطعت\nمرة واحدة\nآية الكرسي\n" +
            "الله لا اله الا هو الحي القيوم لا تاخذه سنة ولا نوم له ما في السماوات وما في الارض"
        val ra = Proof.addReading(a, "server:qari4", page)
        val rb = Proof.addHints(Proof.addReading(b, "server:qari4", page), com.werd.app.sources.Helpers.Quran.suggest(app, b.text), "quran")
        dao.insertSourceItems(listOf(ra, rb))
        sid
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun tour() {
        seed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ar"))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            wait(StoreDemo.EVENING_TITLE)
            tab(R.string.tab_sources); wait(BOOK)
            compose.onAllNodesWithText(BOOK)[0].performClick()
            wait(s(R.string.proof_title))
            compose.onAllNodesWithText(s(R.string.proof_title))[0].performClick()
            wait(s(R.string.proof_original))
            shot("1_proofread")
            // the arrows (or a tap on a marked word) open what the other readers have for it
            compose.onAllNodesWithContentDescription(s(R.string.proof_next_word))[0].performClick()
            wait(s(R.string.proof_keep_mine))
            shot("2_word_popup")
            compose.onAllNodesWithText("عهدك", substring = true).filterToOne(hasClickAction()).performClick()
            compose.waitForIdle()
            shot("3_word_replaced")
            back(); wait(BOOK)

            compose.onAllNodesWithContentDescription(s(R.string.more_options))[0].performClick()
            compose.onAllNodesWithText(s(R.string.pw_title))[0].performClick()
            wait(s(R.string.pw_start))
            shot("4_proofread_with")
            compose.onAllNodesWithText(s(R.string.cancel)).filterToOne(hasClickAction()).performClick()
            back()

            tab(R.string.tab_settings); compose.waitForIdle()
            compose.onAllNodesWithText(s(R.string.acct_add_password))[0].performScrollTo()
            shot("5_account")

            // the reader with the slim counter, then with it hidden (tap the text)
            runBlocking { app.settings.update { it.copy(focusMode = true, counterSize = 2) } }
            tab(R.string.tab_home); wait(StoreDemo.EVENING_TITLE)
            compose.onAllNodesWithText(StoreDemo.EVENING_TITLE)[0].performClick()
            compose.waitForIdle()
            shot("7_counter_slim")
            runBlocking { app.settings.update { it.copy(counterSize = 3) } }
            shot("8_counter_hidden")
            runBlocking { app.settings.update { it.copy(counterSize = 1) } }
            shot("9_counter_medium")
        }
    }
}
