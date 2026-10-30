package com.werd.app

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.data.Seed
import com.werd.app.widget.NextWerdWidget
import com.werd.app.widget.StreakWidget
import com.werd.app.widget.TodayWidget
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders each home-screen widget through Glance → RemoteViews → real Views and saves a screenshot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetScreenshotTest {
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private var prefix = ""

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun render(name: String, w: GlanceAppWidget, size: DpSize) {
        val rv = runBlocking { w.compose(app, size = size) }
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { act ->
                val d = act.resources.displayMetrics.density
                val host = FrameLayout(act).apply { setBackgroundColor(0xFF7A8F86.toInt()); setPadding(24, 24, 24, 24) }
                val v = rv.apply(act, host)
                host.addView(v, FrameLayout.LayoutParams((size.width.value * d).toInt(), (size.height.value * d).toInt()))
                act.setContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            Thread.sleep(300)
            sc.onActivity { act -> act.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).captureRoboImage("screenshots/widget_$prefix$name.png") }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun widgets() {
        runBlocking {
            app.settings.update { it.copy(onboarded = true) }
            app.repo.seedIfNeeded(true); StoreDemo.replaceRatib(app)
            val dao = app.db.dao()
            val ratib = dao.werds().first { it.title == StoreDemo.EVENING_TITLE }
            val items = dao.items(ratib.id)
            items.take(6).forEach { app.repo.increment(ratib.id, it.id, it.target) }
            app.repo.increment(ratib.id, items[6].id, 4)
        }
        render("next", NextWerdWidget(), DpSize(320.dp, 170.dp))
        render("today", TodayWidget(), DpSize(320.dp, 240.dp))
        render("streak", StreakWidget(), DpSize(160.dp, 160.dp))
    }

    /** English labels, for the store listing. */
    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun widgetsEnglish() {
        prefix = "en_"
        runBlocking {
            app.settings.update { it.copy(onboarded = true, language = "en") }
            app.repo.seedIfNeeded(false); StoreDemo.replaceRatib(app)
            val dao = app.db.dao()
            val ratib = dao.werds().first { it.title == StoreDemo.EVENING_TITLE }
            val items = dao.items(ratib.id)
            items.take(6).forEach { app.repo.increment(ratib.id, it.id, it.target) }
            app.repo.increment(ratib.id, items[6].id, 4)
        }
        render("next", NextWerdWidget(), DpSize(320.dp, 170.dp))
    }
}
