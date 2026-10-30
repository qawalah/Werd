package com.werd.app.voice

import android.content.Context
import com.werd.app.WerdApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Spoken clips kept on the phone: a line is made once per voice and then played from here, also offline. */
class VoiceCache(context: Context) {
    private val dir = File(context.filesDir, "voice-cache").apply { mkdirs() }

    fun key(signature: String, text: String): String =
        MessageDigest.getInstance("SHA-256").digest((signature + "\u0000" + text.trim()).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun find(key: String): File? = listOf("mp3", "wav").map { File(dir, "$key.$it") }.firstOrNull { it.exists() && it.length() > 0 }

    fun put(key: String, bytes: ByteArray, ext: String): File {
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        val f = File(dir, "$key.$ext")
        tmp.renameTo(f)
        return f
    }

    fun size(): Pair<Int, Long> = (dir.listFiles() ?: emptyArray()).let { fs -> fs.size to fs.sumOf { it.length() } }
    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/** Makes (or finds) the clip of a line in the chosen voice. */
class ClipVoice(private val app: WerdApp, val prefs: VoicePrefs) {
    private val cache = VoiceCache(app)
    private val signature = prefs.signature()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun cached(text: String): File? = cache.find(cache.key(signature, text))

    /** The audio file for [text]: from the phone, else made by the voice (and kept). Throws on failure. */
    suspend fun file(text: String): File = withContext(Dispatchers.IO) {
        val key = cache.key(signature, text)
        VoiceLibrary.remember(app, prefs)
        cache.find(key)?.let { return@withContext it }
        locks.getOrPut(key) { Mutex() }.withLock {
            cache.find(key)?.let { return@withLock it }
            val (bytes, ext) = make(text)
            val f = cache.put(key, bytes, ext)
            share(text, bytes, ext)
            f
        }
    }

    /** Adds a clip this phone made to the family server's sound library (in the background; best effort). */
    private fun share(text: String, bytes: ByteArray, ext: String) {
        if (prefs.engine == VoicePrefs.SERVER || !prefs.shareToServer) return
        val c = app.cloud.store.value
        if (!c.configured || !c.signedIn) return
        app.appScope.launch { runCatching { app.cloud.shareClip(prefs.shareId(), prefs.shareName(), text.trim(), bytes, ext) } }
    }

    private suspend fun make(text: String): Pair<ByteArray, String> = when (prefs.engine) {
        VoicePrefs.GEMINI -> CloudVoices.gemini(prefs.geminiKey.ifBlank { app.settings.value.aiKeyGemini }, prefs.geminiModel,
            prefs.geminiVoice, prefs.geminiStyle, text)
        VoicePrefs.AZURE -> CloudVoices.azure(prefs.azureKey, prefs.azureRegion, prefs.azureVoice, text)
        VoicePrefs.ELEVEN -> CloudVoices.eleven(prefs.elevenKey, prefs.elevenModel, prefs.elevenVoice, text)
        VoicePrefs.PIPER -> PiperVoice(app).speak(prefs.piperModel, text) to "wav"
        VoicePrefs.SERVER -> app.cloud.speech(text, prefs.serverVoice)
        else -> throw VoiceException("system voice has no clips")
    }

    companion object {
        /** The clip voice in use, or null for the phone's own text-to-speech. */
        fun current(app: WerdApp): ClipVoice? = app.voice.value.takeIf { it.isClip }?.let { ClipVoice(app, it) }
    }
}

/**
 * Makes the audio of a whole book (or werd) in the background, line by line, so it plays at once and offline.
 * [progress] holds (done, total) per job key ("source:12"); [errors] the last failure per key.
 */
object AudioPrep {
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val errors = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun running(key: String) = jobs[key]?.isActive == true

    fun start(app: WerdApp, key: String, texts: List<String>) {
        val c = ClipVoice.current(app) ?: return
        if (running(key)) return
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        errors.value = errors.value - key
        jobs[key] = app.appScope.launch {
            var fails = 0
            for ((i, t) in lines.withIndex()) {
                progress.value = progress.value + (key to (i to lines.size))
                runCatching { c.file(t) }.onFailure { e ->
                    fails++
                    if (fails >= 3) {   // the key, quota or voice is wrong: stop instead of failing every line
                        errors.value = errors.value + (key to (e.message ?: e.javaClass.simpleName))
                        progress.value = progress.value - key
                        return@launch
                    }
                }.onSuccess { fails = 0 }
            }
            progress.value = progress.value - key
        }
    }

    fun cancel(key: String) { jobs.remove(key)?.cancel(); progress.value = progress.value - key }
}
