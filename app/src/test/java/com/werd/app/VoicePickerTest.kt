package com.werd.app

import android.speech.tts.Voice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoicePickerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun voices() {
        app.settings.update { it.copy(onboarded = true, seeded = true, language = "ar") }
        fun v(n: String, l: Locale, net: Boolean, feat: Set<String> = emptySet()) =
            ShadowTextToSpeech.addVoice(Voice(n, l, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, net, feat))
        v("ar-SA-SMTf00", Locale("ar", "SA"), false)
        v("ar-SA-SMTm00", Locale("ar", "SA"), false)
        v("ar-xa-x-arz-network", Locale("ar", "EG"), true)
        v("ar-xa-x-ard-local", Locale("ar", "AE"), false, setOf("notInstalled"))
        v("en-us-x-sfg-local", Locale.US, false)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle(); Thread.sleep(1500)
            val st = app.getString(R.string.tab_settings)
            compose.waitUntil(30_000) { compose.onAllNodes(hasText(st) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(st) and hasClickAction()).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(app.getString(R.string.s_voice_pick)).fetchSemanticsNodes().isNotEmpty() }
            // Robolectric never calls the TTS init listener by itself
            compose.runOnUiThread {
                val t = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
                org.robolectric.Shadows.shadowOf(t).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(app.getString(R.string.voice_default)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(app.getString(R.string.voice_default))[0].performScrollTo()
            compose.onAllNodesWithText(app.getString(R.string.voice_n, 4))[0].performScrollTo()
            compose.onAllNodesWithText(app.getString(R.string.voice_n, 2))[0].performClick()
            compose.waitForIdle(); Thread.sleep(300)
            compose.onRoot().captureRoboImage("screenshots/voice_picker.png")
            org.junit.Assert.assertEquals("ar-SA-SMTm00", app.settings.value.ttsVoice)
            // the "Test voice" button speaks with the chosen voice
            compose.onAllNodesWithText(app.getString(R.string.s_test_voice))[0].performScrollTo().performClick()
            compose.waitForIdle()
            val t = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
            org.junit.Assert.assertEquals("ar-SA-SMTm00", t.voice?.name)
            org.junit.Assert.assertTrue(org.robolectric.Shadows.shadowOf(t).lastSpokenText.startsWith("سُبْحَانَ"))
        }
    }
}
