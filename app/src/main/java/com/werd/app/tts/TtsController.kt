package com.werd.app.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reads werd lines aloud and reports which line and which word is being spoken,
 * so the reader can show a moving marker. Android TTS has no pause, so pause = stop and remember the line.
 */
class TtsController(context: Context, engine: String = "") {

    /** One installed voice the user can pick. */
    data class VoiceOption(
        val name: String,
        val country: String,
        val offline: Boolean,
        val needsDownload: Boolean,
        /** "m" / "f" when the engine's voice name reveals it, else "". */
        val gender: String,
    )

    data class State(
        val ready: Boolean = false,
        val arabicAvailable: Boolean = true,
        val voices: List<VoiceOption> = emptyList(),
        val engines: List<Pair<String, String>> = emptyList(),
        val engine: String = "",
        val speaking: Boolean = false,
        val index: Int = -1,
        val wordStart: Int = -1,
        val wordEnd: Int = -1,
        /** A natural voice is making the clip of the current line. */
        val preparing: Boolean = false,
        /** Why the natural voice failed (the line was read by the phone's voice instead); "" when fine. */
        val error: String = "",
        /** This pass over the current line: repetition [rep] of [reps] (1-based). */
        val rep: Int = 0,
        val reps: Int = 0,
    )

    companion object {
        /** Screenshot tests only: start in this state (e.g. mid-sentence) instead of idle. */
        @androidx.annotation.VisibleForTesting @Volatile var previewState: State? = null
    }

    private val appContext: Context? = context.applicationContext
    private val _state = MutableStateFlow(previewState ?: State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Supplies the text of line i, or null when there are no more lines. */
    var lineProvider: (Int) -> String? = { null }
    /** How many times line i should be spoken in this pass (0 = skip). */
    var repeatProvider: (Int) -> Int = { 1 }
    /** Called after each spoken repetition of line i. */
    var onSpoken: (Int) -> Unit = {}
    var onFinished: () -> Unit = {}

    private var tts: TextToSpeech? = null
    private var rate = 0.9f
    private var pitch = 1f
    private var currentIndex = 0
    private var remainingReps = 0
    private var session = 0

    /** Name of the voice to use for Arabic text ("" = the engine's default). */
    @Volatile var preferredVoice: String = ""

    /** A natural voice (cloud, on-phone or the family server). null = the phone's text-to-speech. */
    @Volatile var clips: com.werd.app.voice.ClipVoice? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var player: android.media.MediaPlayer? = null

    init {
        val onInit = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@OnInitListener
                val avail = t.isLanguageAvailable(Locale.forLanguageTag("ar"))
                _state.value = _state.value.copy(
                    ready = true, arabicAvailable = avail >= TextToSpeech.LANG_AVAILABLE,
                    voices = arabicVoices(t),
                    engines = runCatching { t.engines.map { it.name to it.label } }.getOrDefault(emptyList()),
                    engine = runCatching { t.defaultEngine }.getOrNull().orEmpty().let { def -> engine.ifEmpty { def } },
                )
                t.setOnUtteranceProgressListener(listener)
            } else {
                _state.value = _state.value.copy(ready = false, arabicAvailable = false)
            }
        }
        tts = if (engine.isEmpty()) TextToSpeech(context.applicationContext, onInit)
        else TextToSpeech(context.applicationContext, onInit, engine)
    }

    private fun arabicVoices(t: TextToSpeech): List<VoiceOption> = runCatching {
        t.voices.orEmpty().filter { it.locale.language == "ar" }.map { v ->
            val n = v.name.lowercase()
            val gender = when {
                Regex("smtm|[-_]m\\d|male").containsMatchIn(n) && !n.contains("female") -> "m"
                Regex("smtf|[-_]f\\d|female").containsMatchIn(n) -> "f"
                else -> ""
            }
            VoiceOption(
                name = v.name,
                country = v.locale.country.takeIf { it.isNotEmpty() && it != "XA" && it != "001" }
                    ?.let { Locale("", it).getDisplayCountry(Locale.getDefault()) }.orEmpty(),
                offline = !v.isNetworkConnectionRequired,
                needsDownload = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
                gender = gender,
            )
        }.sortedWith(compareBy({ it.needsDownload }, { !it.offline }, { it.country }, { it.name }))
    }.getOrDefault(emptyList())

    /** Re-reads installed voices (e.g. after the user downloads one). */
    fun refreshVoices() {
        val t = tts ?: return
        if (_state.value.ready) _state.value = _state.value.copy(voices = arabicVoices(t))
    }

    /** Picks the preferred voice for Arabic text, falling back to the engine's Arabic default. */
    private fun applyVoice(t: TextToSpeech, text: String, voiceName: String = preferredVoice) {
        val arabic = text.any { it in '؀'..'ۿ' }
        if (arabic && voiceName.isNotEmpty()) {
            val v = runCatching { t.voices?.firstOrNull { it.name == voiceName } }.getOrNull()
            if (v != null) { t.voice = v; return }
        }
        t.language = if (arabic) Locale.forLanguageTag("ar") else Locale.getDefault()
    }

    // ---- fallback marker for engines that never call onRangeStart: walk the words at the estimated pace
    private val handler = Handler(Looper.getMainLooper())
    private var rangeSupported = false
    private var estimating: Runnable? = null

    private fun cancelEstimate() { estimating?.let { handler.removeCallbacks(it) }; estimating = null }

    private fun startEstimate(sessionAtStart: Int, index: Int, text: String, durationMs: Long = 0) {
        cancelEstimate()
        if (rangeSupported && durationMs <= 0) return
        val words = Regex("\\S+").findAll(text).map { it.range }.toList()
        if (words.isEmpty()) return
        // ~13 spoken letters per second at rate 1.0; diacritics take no time
        fun weight(r: IntRange) = text.substring(r.first, r.last + 1).count { it.isLetterOrDigit() && it !in '\u064B'..'\u065F' }.coerceAtLeast(1) + 1
        val lettersPerMs = if (durationMs > 0) words.sumOf { weight(it) }.toDouble() / durationMs else 13.0 * rate / 1000.0
        val t0 = SystemClock.uptimeMillis() + if (durationMs > 0) 0 else 120
        var acc = 0.0
        val schedule = words.map { r -> val at = t0 + (acc / lettersPerMs).toLong(); acc += weight(r); r to at }
        var k = 0
        val tick = object : Runnable {
            override fun run() {
                if (session != sessionAtStart || (rangeSupported && durationMs <= 0) || k >= schedule.size) return
                val (r, _) = schedule[k]
                if (_state.value.index == index) _state.value = _state.value.copy(wordStart = r.first, wordEnd = r.last + 1)
                k++
                if (k < schedule.size) handler.postAtTime(this, schedule[k].second)
            }
        }
        estimating = tick
        handler.postAtTime(tick, schedule[0].second + if (durationMs > 0) 0 else 250) // give real range callbacks a chance first
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (s, i) = parse(utteranceId) ?: return
            if (s != session) return
            _state.value = _state.value.copy(speaking = true, index = i, wordStart = -1, wordEnd = -1)
            val text = lineProvider(i) ?: return
            handler.post { if (s == session) startEstimate(s, i, text) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (s, i) = parse(utteranceId) ?: return
            if (s != session) return
            if (!rangeSupported) { rangeSupported = true; handler.post { cancelEstimate() } }
            _state.value = _state.value.copy(index = i, wordStart = start, wordEnd = end)
        }

        override fun onDone(utteranceId: String?) {
            val (s, i) = parse(utteranceId) ?: return
            if (s != session) return
            handler.post { cancelEstimate() }
            onSpoken(i)
            remainingReps--
            if (remainingReps > 0) speakCurrent() else advance(i + 1)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val (s, i) = parse(utteranceId) ?: return
            if (s != session) return
            advance(i + 1)
        }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        val p = id?.split('_') ?: return null
        if (p.size < 3) return null
        return (p[1].toIntOrNull() ?: return null) to (p[2].toIntOrNull() ?: return null)
    }

    fun setVoice(rate: Float, pitch: Float) {
        this.rate = rate; this.pitch = pitch
        tts?.setSpeechRate(rate); tts?.setPitch(pitch)
    }

    fun start(from: Int) {
        session++
        if (_state.value.error.isNotEmpty()) _state.value = _state.value.copy(error = "")
        tts?.setSpeechRate(rate); tts?.setPitch(pitch)
        advance(from)
    }

    private fun advance(from: Int) {
        var i = from
        while (true) {
            val text = lineProvider(i)
            if (text == null) {
                _state.value = _state.value.copy(speaking = false, index = -1, wordStart = -1, wordEnd = -1, rep = 0, reps = 0)
                onFinished()
                return
            }
            val reps = repeatProvider(i)
            if (reps > 0 && text.isNotBlank()) {
                currentIndex = i; remainingReps = reps; passReps = reps
                speakCurrent()
                return
            }
            i++
        }
    }

    private var passReps = 0

    private fun speakCurrent() {
        _state.value = _state.value.copy(rep = passReps - remainingReps + 1, reps = passReps)
        clips?.let { playClip(it); return }
        speakSystem()
    }

    private fun speakSystem() {
        val text = lineProvider(currentIndex) ?: return
        val t = tts ?: return
        applyVoice(t, text)
        _state.value = _state.value.copy(speaking = true, index = currentIndex, wordStart = -1, wordEnd = -1)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "u_${session}_${currentIndex}_${remainingReps}")
    }

    // ---------------------------------------------------------------- natural voices: play the line's clip

    private fun stopPlayer() { player?.let { runCatching { it.stop() }; it.release() }; player = null }

    private fun playClip(c: com.werd.app.voice.ClipVoice) {
        val idx = currentIndex
        val s = session
        val text = lineProvider(idx) ?: return
        _state.value = _state.value.copy(speaking = true, index = idx, wordStart = -1, wordEnd = -1, preparing = c.cached(text) == null)
        scope.launch {
            val file = runCatching { c.file(text) }
            if (s != session) return@launch
            val f = file.getOrElse { e ->
                // keep reading with the phone's voice, and say why
                _state.value = _state.value.copy(preparing = false, error = e.message ?: e.javaClass.simpleName)
                speakSystem(); return@launch
            }
            _state.value = _state.value.copy(preparing = false)
            // make the next line meanwhile, so there is no gap
            lineProvider(idx + 1)?.takeIf { it.isNotBlank() }?.let { next ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.file(next) } }
            }
            stopPlayer()
            val mp = android.media.MediaPlayer()
            appContext?.let { mp.setWakeMode(it, android.os.PowerManager.PARTIAL_WAKE_LOCK) }
            player = mp
            runCatching {
                mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(f.path)
                mp.prepare()
                // the phone's voice treats 0.9 as normal pace; natural voices are recorded at normal pace
                val speed = (rate / 0.9f).coerceIn(0.5f, 2f)
                mp.playbackParams = mp.playbackParams.setSpeed(speed).setPitch(pitch.coerceIn(0.5f, 2f))
                mp.setOnCompletionListener {
                    if (s != session) return@setOnCompletionListener
                    handler.post { cancelEstimate() }
                    onSpoken(idx)
                    remainingReps--
                    if (remainingReps > 0) speakCurrent() else advance(idx + 1)
                }
                mp.start()
                startEstimate(s, idx, text, (mp.duration / speed).toLong())
            }.onFailure { e ->
                f.delete()   // a broken clip: make it again next time
                _state.value = _state.value.copy(error = e.message ?: "audio")
                stopPlayer(); speakSystem()
            }
        }
    }

    /** Plays [text] once in the natural voice (Settings' test button). */
    fun previewClip(c: com.werd.app.voice.ClipVoice, text: String) {
        session++
        val s = session
        _state.value = _state.value.copy(preparing = true, error = "")
        scope.launch {
            val f = runCatching { c.file(text) }.getOrElse { e ->
                if (s == session) _state.value = _state.value.copy(preparing = false, error = e.message ?: e.javaClass.simpleName)
                return@launch
            }
            if (s != session) return@launch
            _state.value = _state.value.copy(preparing = false)
            stopPlayer()
            player = android.media.MediaPlayer().apply {
                runCatching { setDataSource(f.path); prepare(); start() }
                    .onFailure { f.delete(); _state.value = _state.value.copy(error = it.message ?: "audio") }
            }
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = "") }

    /** Stops speaking; returns the line that was being read so it can be resumed. */
    fun stop(): Int {
        session++
        handler.post { cancelEstimate() }
        stopPlayer()
        tts?.stop()
        val idx = _state.value.index
        _state.value = _state.value.copy(speaking = false, wordStart = -1, wordEnd = -1, preparing = false)
        return idx
    }

    fun preview(text: String, voiceName: String = preferredVoice) {
        session++
        val t = tts ?: return
        t.setSpeechRate(rate); t.setPitch(pitch)
        applyVoice(t, text, voiceName)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
    }

    fun shutdown() {
        session++
        handler.post { cancelEstimate() }
        stopPlayer()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        tts?.stop(); tts?.shutdown(); tts = null
    }
}
