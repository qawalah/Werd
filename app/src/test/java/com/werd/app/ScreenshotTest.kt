package com.werd.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.data.DaySession
import com.werd.app.data.Counts
import com.werd.app.data.Seed
import com.werd.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives the real app (seeded data, navigation, Room) headlessly and saves screenshots to app/screenshots/.
 * Run: ./gradlew testDebugUnitTest --tests com.werd.app.ScreenshotTest
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)
    private lateinit var lang: String

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/${lang}_$name.png")
    }

    private fun waitText(t: String, substring: Boolean = false) = try {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(t, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        compose.onRoot().captureRoboImage("screenshots/${lang}_FAIL.png")
        java.io.File("screenshots/${lang}_FAIL_tree.txt").writeText("waiting for: $t\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 60))
        throw e
    }

    private fun tab(label: Int) = compose.onNode(hasText(s(label)) and hasClickAction()).performClick()
    private fun back() = compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()

    private fun seed(arabic: Boolean) = runBlocking {
        app.settings.update { it.copy(onboarded = true, language = if (arabic) "ar" else "en", prayerLat = 24.7136, prayerLng = 46.6753,
            prayerPlace = if (arabic) "الرياض" else "Riyadh") }
        app.repo.seedIfNeeded(arabic)
        val dao = app.db.dao()
        val werds = dao.werds()
        val today = app.repo.today()
        // pretend the awrad were created two months ago and build some history
        werds.forEach { dao.updateWerd(it.copy(createdAt = LocalDate.now().minusDays(70).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())) }
        werds.forEachIndexed { wi, w ->
            val items = dao.items(w.id)
            for (back in 1..65) {
                val skip = (back * (wi + 3)) % 7 == 0 || (back > 30 && back % 4 == 0)
                if (skip) continue
                val day = today - back
                val at = LocalDate.ofEpochDay(day).atTime(if (wi == 0) 19 else 5, 40).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                dao.upsertSession(DaySession(werdId = w.id, day = day, counts = Counts.encode(items.associate { it.id to it.target }),
                    completed = true, completedAt = at, updatedAt = at))
            }
        }
        // today: ratib part-way through, tasbih complete
        val ratib = werds.first { it.title == Seed.RATIB_TITLE }
        val items = dao.items(ratib.id)
        items.take(6).forEach { app.repo.increment(ratib.id, it.id, it.target) }
        app.repo.increment(ratib.id, items[6].id, 4)
        val tasbih = werds.first { it.title == Seed.TASBIH_TITLE }
        dao.items(tasbih.id).forEach { app.repo.increment(tasbih.id, it.id, it.target) }
        // enable the sample prayer reminders so the reminders tab has content
        dao.reminders().forEach { dao.setReminderEnabled(it.id, true) }
    }

    private fun tour() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(localeTag(app.settings.value.language)))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText(Seed.RATIB_TITLE)
            shot("01_home")
            compose.onNode(hasScrollAction()).performScrollToIndex(5)
            shot("02_home_groups")

            compose.onAllNodesWithText(Seed.RATIB_TITLE)[0].performClick()
            waitText(s(R.string.listen))
            shot("03_reader")
            compose.onAllNodesWithContentDescription(s(R.string.focus_mode))[0].performClick()
            shot("04_focus")
            compose.onAllNodesWithContentDescription(s(R.string.list_mode))[0].performClick()
            back()

            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(s(R.string.new_werd)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription(s(R.string.new_werd))[0].performClick()
            waitText(s(R.string.convert))
            compose.onAllNodes(hasSetTextAction())[1].performTextInput(
                "أذكار المساء\nأمسينا وأمسى الملك لله والحمد لله\nقل هو الله أحد والمعوذتين (ثلاث مرات)\n" +
                    "حسبي الله لا إله إلا هو عليه توكلت وهو رب العرش العظيم سبعاً\nسبحان الله وبحمده ١٠٠ مرة\n" +
                    "اللهم صل وسلم على نبينا محمد ×10\nأستغفر الله وأتوب إليه\n(١٠٠ مرة)")
            shot("05_paste")
            compose.onNode(hasText(s(R.string.convert)) and hasClickAction()).performScrollTo().performClick()
            waitText(s(R.string.auto_detected))
            shot("06_converted")
            back()
            compose.onAllNodesWithText(s(R.string.discard))[0].performClick()

            waitText(s(R.string.tab_stats))
            tab(R.string.tab_stats)
            waitText(s(R.string.total_recitations))
            shot("07_stats")
            compose.onNode(hasScrollAction()).performScrollToIndex(6)
            shot("08_stats_goals")
            compose.onNode(hasScrollAction()).performScrollToIndex(12)
            shot("09_achievements")

            tab(R.string.tab_reminders)
            waitText(s(R.string.reminders_sub))
            shot("10_reminders")
            compose.onAllNodesWithText(app.resources.getStringArray(R.array.prayer_names)[4], substring = true)[0].performClick()
            waitText(s(R.string.remind_when))
            shot("11_reminder_prayer")
            back()

            tab(R.string.tab_settings)
            waitText(s(R.string.s_appearance))
            shot("12_settings")
        }
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun arabic() { lang = "ar"; seed(true); tour() }

    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun english() { lang = "en"; seed(false); tour() }
}
