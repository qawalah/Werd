package com.werd.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.Seed
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.sources.Sources
import com.werd.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewScreensShot {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)
    private fun shot(n: String) { compose.waitForIdle(); Thread.sleep(400); compose.waitForIdle(); compose.onRoot().captureRoboImage("screenshots/new_$n.png") }
    private fun wait(t: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tab(id: Int) = compose.onNode(hasText(s(id)) and hasClickAction()).performClick()

    /** A scanned-looking book page (the same page stands in for every page). */
    private fun bookPages(): java.io.File {
        val w = 1200; val h = 1700
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(0xFFF6F0E1.toInt())
        val font = androidx.core.content.res.ResourcesCompat.getFont(app, R.font.amiri_regular)
        val bold = androidx.core.content.res.ResourcesCompat.getFont(app, R.font.amiri_bold)
        val p = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2418.toInt(); typeface = font; textSize = 52f }
        fun block(text: String, y: Float, paint: android.text.TextPaint): Float {
            val l = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, w - 160)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
            c.save(); c.translate(80f, y); l.draw(c); c.restore(); return y + l.height + 40
        }
        val head = android.text.TextPaint(p).apply { typeface = bold; textSize = 60f; color = 0xFF7A1F1F.toInt() }
        var y = 110f
        y = block("سيد الاستغفار", y, head)
        y = block("«اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ، أَعُوذُ بِكَ مِنْ شَرِّ مَا صَنَعْتُ، أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ بِذَنْبِي فَاغْفِرْ لِي فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ»", y, p)
        y = block("مرة واحدة", y, android.text.TextPaint(p).apply { textSize = 40f; color = 0xFF6B5B3E.toInt() })
        c.drawLine(80f, y, w - 80f, y, android.graphics.Paint().apply { color = 0xFFBFAE88.toInt(); strokeWidth = 3f }); y += 50
        y = block("«حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ»", y, p)
        block("سبع مرات", y, android.text.TextPaint(p).apply { textSize = 40f; color = 0xFF6B5B3E.toInt() })
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val d = java.io.File(Sources.dir(app), "shot_book").apply { mkdirs() }
        (1..120).forEach { java.io.File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", it)).writeBytes(bytes) }
        return d
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun tour() {
        runBlocking {
            app.settings.update { it.copy(onboarded = true, language = "ar", aiKeyGemini = "test-key") }
            app.repo.seedIfNeeded(true)
            val dao = app.db.dao()
            val evening = dao.groups().first { it.name == "المساء" }
            app.repo.saveReminder(Reminder(groupId = evening.id, type = ReminderType.PRAYER, prayer = 4, offsetMin = 15))
            val sid = dao.insertSource(Source(title = "حصن المسلم", kind = Sources.KIND_IMAGES, path = bookPages().path, pageCount = 120, pagesDone = 72, status = "RUNNING"))
            dao.insertSourceItems(listOf(
                SourceItem(sourceId = sid, position = 0, page = 14, title = "دعاء الاستيقاظ من النوم", text = "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا بَعْدَ مَا أَمَاتَنَا وَإِلَيْهِ النُّشُورُ", count = 1, occasion = "waking", occasionText = "عند الاستيقاظ من النوم"),
                SourceItem(sourceId = sid, position = 1, page = 31, title = "آية الكرسي", text = "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ۚ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ", count = 1, occasion = "morning_evening", occasionText = "تقال في الصباح والمساء", note = "من قالها حين يصبح أجير من الجن حتى يمسي"),
                SourceItem(sourceId = sid, position = 2, page = 33, title = "سيد الاستغفار", text = "اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ", count = 1, occasion = "morning_evening"),
                SourceItem(sourceId = sid, position = 3, page = 33, title = "حسبي الله", text = "حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ", count = 7, occasion = "morning_evening", checked = true),
                SourceItem(sourceId = sid, position = 4, page = 52, title = "التسبيح دبر الصلاة", text = "سُبْحَانَ اللَّهِ", count = 33, occasion = "after_prayer", occasionText = "دبر كل صلاة مكتوبة"),
                SourceItem(sourceId = sid, position = 5, page = 60, title = "دعاء النوم", text = "بِاسْمِكَ رَبِّي وَضَعْتُ جَنْبِي، وَبِكَ أَرْفَعُهُ", count = 1, occasion = "sleep", werdId = 1),
            ))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle(); Thread.sleep(1500)
            wait(Seed.RATIB_TITLE)
            tab(R.string.tab_sources); wait("حصن المسلم"); shot("1_sources")
            compose.onAllNodesWithText("حصن المسلم")[0].performClick()
            wait("سيد الاستغفار")
            // proofread mode, opened from the page link of an item
            compose.onAllNodesWithText(app.getString(R.string.src_page, 33))[0].performClick()
            wait(s(R.string.proof_original))
            shot("2b_proofread")
            compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()
            wait("سيد الاستغفار")
            compose.onAllNodes(isToggleable())[2].performClick()
            compose.onAllNodes(isToggleable())[3].performClick()
            shot("2_source_detail")
            // (this dialog never reports idle under Robolectric, so it is captured with a frozen clock)
            compose.onAllNodesWithText(s(R.string.src_add_to_werds), useUnmergedTree = true)[0].performClick()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(1000)
            compose.onRoot().captureRoboImage("screenshots/new_3_add_dialog.png")
            compose.onAllNodesWithText(s(R.string.cancel))[0].performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.mainClock.autoAdvance = true
            compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()
            tab(R.string.tab_reminders); wait(s(R.string.reminders_sub)); shot("4_reminders")
            compose.onAllNodesWithText(app.getString(R.string.applies_to_n, 1).substringBefore("1").trim(), substring = true)[0].performClick()
            wait(s(R.string.remind_when))
            compose.onAllNodesWithText(s(R.string.applies_to))[0].performScrollTo()
            shot("5_group_reminder")
            compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()
            tab(R.string.tab_settings); wait(s(R.string.ai_title))
            compose.onAllNodesWithText(s(R.string.ai_provider))[0].performScrollTo()
            shot("6_ai_settings")
        }
    }
}
