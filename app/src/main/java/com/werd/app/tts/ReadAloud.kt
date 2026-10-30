package com.werd.app.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.WerdUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Reading a werd aloud, for the whole app: it keeps going when the screen is off or another app is in front
 * (a foreground service holds it), counts each repetition as the voice says it, and the reader picks up the
 * live session when it is opened again.
 */
object ReadAloud {
    data class Session(val werdId: Long, val title: String, val autoCount: Boolean)

    private var tts: TtsController? = null
    private var engine = ""
    private var app: WerdApp? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val idle = MutableStateFlow(TtsController.State())
    /** The voice's live state (line, word, repetition) or idle. */
    val state: StateFlow<TtsController.State> get() = tts?.state ?: idle

    @Volatile private var werd: WerdUi? = null
    /** The line to resume from when reading starts again. */
    @Volatile var resumeFrom: Int = 0
    private var watching: kotlinx.coroutines.Job? = null
    /** Fired when the werd is completed by the voice (the reader shows its celebration). */
    var onCompleted: ((Long) -> Unit)? = null

    fun controller(app: WerdApp): TtsController {
        this.app = app
        val e = app.settings.value.ttsEngine
        val cur = tts
        if (cur != null && e == engine) return cur
        cur?.shutdown()
        engine = e
        return TtsController(app, e).also { t -> tts = t; wire(t) }.also { _stateChanged.value++ }
    }
    /** Bumped when the controller is replaced (the reader re-collects its state). */
    val _stateChanged = MutableStateFlow(0)

    private fun wire(t: TtsController) {
        t.lineProvider = { i -> werd?.items?.getOrNull(i)?.text }
        t.repeatProvider = { i ->
            val cur = werd
            val it = cur?.items?.getOrNull(i)
            when {
                it == null -> 0
                // reading along: each line as many times as is left of its count
                _session.value?.autoCount == true -> (it.target - (cur.counts[it.id] ?: 0)).coerceAtLeast(0)
                else -> it.target.coerceAtLeast(1)
            }
        }
        t.onSpoken = { i ->
            val s = _session.value; val cur = werd; val a = app
            if (s != null && s.autoCount && cur != null && a != null) cur.items.getOrNull(i)?.let { item ->
                // count now, and keep the local copy in step so the next repeat count is right before the database answers
                werd = cur.copy(counts = cur.counts + (item.id to ((cur.counts[item.id] ?: 0) + 1)))
                scope.launch {
                    val r = a.repo.increment(s.werdId, item.id, 1)
                    if (r.justCompleted) onCompleted?.invoke(s.werdId)
                }
            }
            resumeFrom = i
        }
        t.onFinished = { resumeFrom = 0; finish() }
        scope.launch { t.state.collectLatest { st -> if (st.index >= 0) resumeFrom = st.index; ReadAloudService.refresh(st) } }
    }

    fun isReading(werdId: Long) = _session.value?.werdId == werdId && state.value.speaking

    /** Starts (or restarts) reading [werdId] from line [from]. */
    fun start(app: WerdApp, werdId: Long, from: Int) {
        val t = controller(app)
        val s = app.settings.value
        t.setVoice(s.ttsRate, s.ttsPitch)
        t.preferredVoice = s.ttsVoice
        t.clips = com.werd.app.voice.ClipVoice.current(app)
        _session.value = Session(werdId, werd?.takeIf { it.werd.id == werdId }?.werd?.title.orEmpty(), s.ttsAutoCount)
        resumeFrom = from
        ReadAloudService.start(app)
        watching?.cancel()
        var started = false
        watching = scope.launch {
            // keep the werd (and its counts) current; start speaking once it is known
            app.repo.dashboard.collectLatest { d ->
                d.werds.firstOrNull { it.werd.id == werdId }?.let { w ->
                    werd = w
                    _session.value = _session.value?.copy(title = w.werd.title)
                    if (!started) { started = true; t.start(from) }
                }
            }
        }
    }

    /** Pauses: stops the voice and remembers the line. */
    fun pause() {
        val i = tts?.stop() ?: -1
        if (i >= 0) resumeFrom = i
        ReadAloudService.refresh(state.value)
    }

    fun resume() {
        val a = app ?: return
        val s = _session.value ?: return
        start(a, s.werdId, resumeFrom)
    }

    /** Ends the session (voice off, notification gone). */
    fun finish() {
        tts?.stop()
        watching?.cancel(); watching = null
        _session.value = null
        app?.let { ReadAloudService.stop(it) }
    }

    fun setAutoCount(on: Boolean) { _session.value = _session.value?.copy(autoCount = on) }

    /** Screenshot tests: show [werdId] as being read without starting a voice. */
    @androidx.annotation.VisibleForTesting fun preview(werdId: Long, title: String) { _session.value = Session(werdId, title, true) }
    @androidx.annotation.VisibleForTesting fun resetForTests() { tts?.shutdown(); tts = null; engine = ""; _session.value = null; watching?.cancel() }
}

/** Keeps reading aloud alive in the background, with a notification to pause, resume or stop. */
class ReadAloudService : Service() {
    companion object {
        private const val CHANNEL = "werd_read_aloud"
        private const val ID = 4_100_000
        const val PAUSE = "pause"; const val RESUME = "resume"; const val STOP = "stop"
        @Volatile private var instance: ReadAloudService? = null

        fun start(context: Context) {
            val i = Intent(context, ReadAloudService::class.java)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, i) }
        }
        fun stop(context: Context) { context.stopService(Intent(context, ReadAloudService::class.java)) }
        fun refresh(st: TtsController.State) { instance?.post(st) }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.channel_read_aloud), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false); ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "werd:read-aloud")
            .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> ReadAloud.pause()
            RESUME -> ReadAloud.resume()
            STOP -> { ReadAloud.finish(); return START_NOT_STICKY }
        }
        val n = build(ReadAloud.state.value)
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    fun post(st: TtsController.State) {
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, build(st)) }
    }

    private fun action(act: String, icon: Int, label: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(this, act.hashCode(), Intent(this, ReadAloudService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action(icon, getString(label), pi)
    }

    private fun build(st: TtsController.State): Notification {
        val s = ReadAloud.session.value
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            s?.let { putExtra(com.werd.app.reminder.Notifier.EXTRA_WERD, it.werdId) }
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = open?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val text = when {
            st.speaking && st.reps > 0 -> getString(R.string.ra_line_rep, st.index + 1, st.rep, st.reps)
            st.speaking -> getString(R.string.reading_line_short, st.index + 1)
            else -> getString(R.string.ra_paused)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s?.title ?: getString(R.string.listen))
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(st.speaking)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(if (st.speaking) action(PAUSE, R.drawable.ic_ra_pause, R.string.ra_pause) else action(RESUME, R.drawable.ic_ra_play, R.string.ra_resume))
            .addAction(action(STOP, R.drawable.ic_ra_stop, R.string.ra_stop))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    override fun onDestroy() {
        instance = null
        runCatching { wake?.release() }
        super.onDestroy()
    }
}
