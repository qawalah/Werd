package com.werd.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.data.ThemeMode
import com.werd.app.tts.ReadAloud
import com.werd.app.tts.TtsController
import com.werd.app.ui.MainActivity
import com.werd.app.voice.VoiceCache
import com.werd.app.voice.VoiceLibrary
import com.werd.app.voice.VoicePrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 1.12 look: home, reader with the voice counting, the bead counter, the voice chooser and the colour themes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RevampShots {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)

    @After fun tearDown() { TtsController.previewState = null; ReadAloud.resetForTests() }

    private fun shot(n: String) {
        compose.waitForIdle(); Thread.sleep(700); compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/v112_$n.png")
    }
    private fun wait(t: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun setup(palette: String, theme: ThemeMode) = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.settings.update { it.copy(onboarded = true, language = "ar", palette = palette, theme = theme, ttsAutoCount = true) }
        app.repo.seedIfNeeded(true); StoreDemo.replaceRatib(app)
        val dao = app.db.dao()
        val werds = dao.werds()
        val ev = werds.first { it.title == StoreDemo.EVENING_TITLE }
        val items = dao.items(ev.id)
        items.take(6).forEach { app.repo.increment(ev.id, it.id, it.target) }
        app.repo.increment(ev.id, items[6].id, 3)
        val tasbih = werds.first { it.title == com.werd.app.data.Seed.TASBIH_TITLE }
        dao.items(tasbih.id).take(2).forEach { app.repo.increment(tasbih.id, it.id, it.target) }
        // two natural voices already have the evening werd on the phone
        val cache = VoiceCache(app)
        val gemini = app.voice.value.copy(engine = VoicePrefs.GEMINI, geminiVoice = "Charon")
        val piper = app.voice.value.copy(engine = VoicePrefs.PIPER)
        for (v in listOf(gemini, piper)) {
            VoiceLibrary.remember(app, v)
            items.forEach { cache.put(cache.key(v.signature(), it.text), ByteArray(64) { 1 }, "wav") }
        }
        app.voice.update { gemini }
        ev.id to items[6].text
    }

    private fun tour(prefix: String, full: Boolean) {
        val (ev, line) = setup(if (prefix == "std") "STANDARD" else "MISBAHA", if (prefix == "std") ThemeMode.LIGHT else ThemeMode.DARK)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ar"))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            wait(StoreDemo.EVENING_TITLE)
            shot("${prefix}_1_home")
            val w0 = line.indexOf(StoreDemo.MARKED_WORD).coerceAtLeast(0)
            TtsController.previewState = TtsController.State(ready = true, speaking = true, index = 6, wordStart = w0,
                wordEnd = w0 + StoreDemo.MARKED_WORD.length, rep = 4, reps = 7)
            ReadAloud.resetForTests()
            ReadAloud.preview(ev, StoreDemo.EVENING_TITLE)
            compose.onAllNodesWithText(StoreDemo.EVENING_TITLE)[0].performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(s(R.string.focus_mode)).fetchSemanticsNodes().isNotEmpty() }
            if (full) shot("${prefix}_2_reader")
            compose.onAllNodesWithContentDescription(s(R.string.focus_mode))[0].performClick()
            shot("${prefix}_3_focus")
            repeat(3) { compose.onAllNodesWithContentDescription(s(R.string.tap_to_count))[0].performClick(); compose.waitForIdle() }
            shot("${prefix}_3b_ring_counted")
            compose.onAllNodesWithContentDescription(s(R.string.counter_style))[0].performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(s(R.string.pull_hint)).fetchSemanticsNodes().isNotEmpty() }
            shot("${prefix}_3c_pull")
            repeat(2) {
                compose.onAllNodesWithContentDescription(s(R.string.pull_hint))[0].performTouchInput { swipeDown(startY = centerY * 0.5f, endY = centerY * 1.4f, durationMillis = 300) }
                compose.waitForIdle()
            }
            shot("${prefix}_3d_pull_counted")
            compose.onAllNodesWithContentDescription(s(R.string.counter_style))[0].performClick()
            compose.waitForIdle()
            if (full) {
                compose.onAllNodesWithText(" · ", substring = true).fetchSemanticsNodes().size.let { n -> compose.onAllNodesWithText(" · ", substring = true)[n - 1].performClick() }
                wait(s(R.string.voice_choose))
                shot("${prefix}_4_voices")
            }
        }
        if (full) ActivityScenario.launch(MainActivity::class.java).use {
            wait(StoreDemo.EVENING_TITLE)
            compose.onNode(hasText(s(R.string.tab_settings)) and hasClickAction()).performClick()
            wait(s(R.string.s_colors))
            compose.onAllNodesWithText(s(R.string.s_colors))[0].performScrollTo()
            shot("${prefix}_5_colours")
        }
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun standardLight() = tour("std", true)

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun misbahaDark() = tour("misbaha", false)
}
