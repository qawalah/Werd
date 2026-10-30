package com.werd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.ThemeMode
import com.werd.app.sources.Sources
import com.werd.app.ui.sources.PublishDialog
import com.werd.app.ui.sources.RescanDialog
import com.werd.app.ui.theme.WerdTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rescan and publish dialogs on their own (dialogs with text fields never report idle in Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogShots {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { sc ->
            sc.onActivity { act ->
                act.setContent { WerdTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
            }
            compose.mainClock.autoAdvance = false
            repeat(8) { compose.mainClock.advanceTimeBy(300); Thread.sleep(250) }   // lets data loaded off the main thread arrive
            compose.onRoot().captureRoboImage("screenshots/$name.png")
        }
    }

    private fun source() = runBlocking {
        app.settings.update { it.copy(aiKeyGemini = "demo", aiKeyClaude = "demo", aiModelGemini = "gemini-3.8-pro") }
        app.cloud.store.update { it.copy(displayName = "أبو محمد", familyId = "f_1", familyName = "عائلة أبي محمد") }
        val dao = app.db.dao()
        val sid = dao.insertSource(Source(title = "دلائل الخيرات", kind = Sources.KIND_IMAGES, path = "/none", pageCount = 96, pagesDone = 96, status = "DONE",
            provider = "gemini", model = "gemini-3.8-flash", author = "الجزولي"))
        dao.insertSourceItems((0 until 12).map { SourceItem(sourceId = sid, position = it, page = it + 1, title = "t", text = "اللهم صل على محمد $it", checked = it != 4) })
        dao.source(sid)!! to dao.sourceItems(sid)
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun rescanAr() { val (s, _) = source(); capture("f_ar_6_rescan") { RescanDialog(app, s, 30, 34, {}, {}) } }

    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun rescanEn() { val (s, _) = source(); capture("f_en_6_rescan") { RescanDialog(app, s, 30, 34, {}, {}) } }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun publishAr() { val (s, items) = source(); capture("f_ar_7_publish") { PublishDialog(app, s, items.map { it.copy(checked = true) }, {}, {}, {}) } }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun publishNeedsProofAr() { val (s, items) = source(); capture("f_ar_7b_publish_needs_proof") { PublishDialog(app, s, items, {}, {}, {}) } }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun sendAr() { runBlocking { app.repo.seedIfNeeded(true); StoreDemo.replaceRatib(app) }
        capture("f_ar_10_send") { com.werd.app.ui.family.SendWerdDialog(app, com.werd.app.cloud.Member("u_2", "عبدالله", false, true, null, "child"), true, {}, {}) } }

    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun sendEn() { runBlocking { app.repo.seedIfNeeded(false); StoreDemo.replaceRatib(app) }
        capture("f_en_10_send") { com.werd.app.ui.family.SendWerdDialog(app, com.werd.app.cloud.Member("u_2", "Abdullah", false, true, null, "child"), true, {}, {}) } }

    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun publishEn() { val (s, items) = source(); capture("f_en_7_publish") { PublishDialog(app, s, items.map { it.copy(checked = true) }, {}, {}, {}) } }
}
