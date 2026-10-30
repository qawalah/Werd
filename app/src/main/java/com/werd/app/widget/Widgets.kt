package com.werd.app.widget

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Dashboard
import com.werd.app.localized
import com.werd.app.reminder.Notifier
import com.werd.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- palette

private val Bg = ColorProvider(day = Color(0xFFFBF7EE), night = Color(0xFF16201C))
private val Card = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1F2C27))
private val Ink = ColorProvider(day = Color(0xFF26302B), night = Color(0xFFE9E4D6))
private val Muted = ColorProvider(day = Color(0xFF6E6A5C), night = Color(0xFFADA796))
private val Gold = ColorProvider(day = Color(0xFFA67C24), night = Color(0xFFE0BE72))
private val Emerald = ColorProvider(day = Color(0xFF0E6B4E), night = Color(0xFF6CC9A1))
private val Track = ColorProvider(day = Color(0xFFE8DFC9), night = Color(0xFF2D3B35))
private val White = FixedColor(Color.White)

private fun openIntent(context: Context, werdId: Long?): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = "com.werd.app.WIDGET_${werdId ?: 0}"
        if (werdId != null) putExtra(Notifier.EXTRA_WERD, werdId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

internal abstract class DashboardWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = WerdApp.from(context).repo
        val initial = repo.loadDashboard()
        val lc = context.localized()
        provideContent {
            val d by repo.dashboard.collectAsState(initial)
            Content(lc, d)
        }
    }

    @Composable
    abstract fun Content(c: Context, d: Dashboard)
}

// ---------------------------------------------------------------- next werd (with tally button)

internal class NextWerdWidget : DashboardWidget() {
    @Composable
    override fun Content(c: Context, d: Dashboard) {
        val next = d.next
        Column(
            GlanceModifier.fillMaxSize().background(Bg).cornerRadius(22.dp).padding(14.dp)
                .clickable(actionStartActivity(openIntent(c, next?.werd?.id))),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("✦ " + c.getString(R.string.widget_next_label),
                    style = TextStyle(color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                Text(c.getString(R.string.today_progress, d.doneToday, d.dueToday.size),
                    style = TextStyle(color = Muted, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(6.dp))
            if (next == null) {
                Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", style = TextStyle(color = Emerald, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    Text(c.getString(if (d.werds.isEmpty()) R.string.widget_empty else R.string.widget_all_done),
                        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
                }
                return@Column
            }
            val idx = next.currentIndex
            val item = next.items.getOrNull(idx)
            val count = item?.let { next.counts[it.id] ?: 0 } ?: 0
            Text(next.werd.title, maxLines = 1,
                style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(4.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(item?.text.orEmpty(), maxLines = 3,
                        style = TextStyle(color = Ink, fontSize = 14.sp))
                    Spacer(GlanceModifier.height(4.dp))
                    Text(c.getString(R.string.widget_line_count, count, item?.target ?: 0, idx + 1, next.items.size),
                        style = TextStyle(color = Muted, fontSize = 11.sp))
                }
                Spacer(GlanceModifier.width(10.dp))
                if (item != null) {
                    Box(
                        GlanceModifier.size(56.dp).background(Emerald).cornerRadius(28.dp)
                            .clickable(actionRunCallback<IncrementAction>(actionParametersOf(
                                IncrementAction.WerdKey to next.werd.id, IncrementAction.ItemKey to item.id))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", style = TextStyle(color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(progress = next.progress, modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = Emerald, backgroundColor = Track)
        }
    }
}

class IncrementAction : ActionCallback {
    companion object {
        val WerdKey = ActionParameters.Key<Long>("werd")
        val ItemKey = ActionParameters.Key<Long>("item")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val w = parameters[WerdKey] ?: return
        val i = parameters[ItemKey] ?: return
        val app = WerdApp.from(context)
        val r = app.repo.increment(w, i)
        if (r.changed && app.settings.value.haptics) {
            val v = context.getSystemService(Vibrator::class.java)
            runCatching { v?.vibrate(VibrationEffect.createOneShot(if (r.itemDone) 60 else 20, VibrationEffect.DEFAULT_AMPLITUDE)) }
        }
        WidgetUpdater.updateNow(context)
    }
}

// ---------------------------------------------------------------- today list

internal class TodayWidget : DashboardWidget() {
    @Composable
    override fun Content(c: Context, d: Dashboard) {
        Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(22.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openIntent(c, null))),
                verticalAlignment = Alignment.CenterVertically) {
                Text("✦ " + c.getString(R.string.widget_today_label),
                    style = TextStyle(color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                Text(c.getString(R.string.today_progress, d.doneToday, d.dueToday.size),
                    style = TextStyle(color = Emerald, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(8.dp))
            val list = d.dueToday.sortedWith(compareBy({ it.completedToday }, { it.todayMinutes ?: Int.MAX_VALUE }, { it.werd.position }))
            if (list.isEmpty()) {
                Text(c.getString(R.string.widget_empty), style = TextStyle(color = Muted, fontSize = 13.sp))
            } else Column(GlanceModifier.fillMaxWidth()) {
                list.take(5).forEach { w ->
                    Column {
                        Row(
                            GlanceModifier.fillMaxWidth().background(Card).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 8.dp)
                                .clickable(actionStartActivity(openIntent(c, w.werd.id))),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(GlanceModifier.size(26.dp).background(if (w.completedToday) Emerald else Track).cornerRadius(13.dp),
                                contentAlignment = Alignment.Center) {
                                Text(if (w.completedToday) "✓" else "${(w.progress * 100).toInt()}%",
                                    style = TextStyle(color = if (w.completedToday) White else Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                            }
                            Spacer(GlanceModifier.width(10.dp))
                            Text(w.werd.title, maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                                style = TextStyle(color = if (w.completedToday) Muted else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                            w.todayMinutes?.let {
                                Text(String.format(java.util.Locale.US, "%d:%02d", it / 60, it % 60),
                                    style = TextStyle(color = Muted, fontSize = 11.sp))
                            }
                        }
                        Spacer(GlanceModifier.height(6.dp))
                    }
                }
                if (list.size > 5) Text("+${list.size - 5}", style = TextStyle(color = Muted, fontSize = 12.sp))
            }
        }
    }
}

// ---------------------------------------------------------------- streak

internal class StreakWidget : DashboardWidget() {
    @Composable
    override fun Content(c: Context, d: Dashboard) {
        Column(
            GlanceModifier.fillMaxSize().background(Bg).cornerRadius(22.dp).padding(10.dp)
                .clickable(actionStartActivity(openIntent(c, null))),
            horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔥 ${d.dayStreak}", style = TextStyle(color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Text(c.getString(R.string.streak_days_label), style = TextStyle(color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(6.dp))
            val p = if (d.dueToday.isEmpty()) 0f else d.doneToday.toFloat() / d.dueToday.size
            LinearProgressIndicator(progress = p, modifier = GlanceModifier.fillMaxWidth().height(5.dp), color = Emerald, backgroundColor = Track)
            Spacer(GlanceModifier.height(4.dp))
            Text(c.getString(R.string.today_progress, d.doneToday, d.dueToday.size), style = TextStyle(color = Muted, fontSize = 11.sp))
        }
    }
}

// ---------------------------------------------------------------- receivers & updates

class NextWerdWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWerdWidget()
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Coalesces bursts of tally taps into one widget refresh. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(600)
            updateNow(app)
        }
    }

    suspend fun updateNow(context: Context) {
        runCatching { NextWerdWidget().updateAll(context) }
        runCatching { TodayWidget().updateAll(context) }
        runCatching { StreakWidget().updateAll(context) }
    }
}
