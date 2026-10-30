package com.werd.app.voice

import android.content.Context
import androidx.core.content.edit
import com.werd.app.WerdApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Remembers every natural voice this phone has made clips with, so the reader can say which voices
 * already have a werd ready (all its lines on the phone) and switch to one of them.
 */
object VoiceLibrary {
    data class Ready(val signature: String, val name: String, val have: Int, val total: Int, val prefs: VoicePrefs, val current: Boolean) {
        val complete: Boolean get() = total > 0 && have >= total
    }

    private fun sp(c: Context) = c.getSharedPreferences("voice_profiles", Context.MODE_PRIVATE)

    /** Called when a clip was made or found in [p]: remembers the voice. */
    fun remember(c: Context, p: VoicePrefs) {
        if (!p.isClip) return
        val sig = p.signature()
        val prefs = sp(c)
        if (prefs.contains(sig)) return
        val o = JSONObject().put("engine", p.engine).put("name", p.shareName())
            .put("geminiModel", p.geminiModel).put("geminiVoice", p.geminiVoice).put("geminiStyle", p.geminiStyle)
            .put("azureVoice", p.azureVoice).put("elevenVoice", p.elevenVoice).put("elevenVoiceName", p.elevenVoiceName)
            .put("elevenModel", p.elevenModel).put("piperModel", p.piperModel).put("serverVoice", p.serverVoice)
        prefs.edit { putString(sig, o.toString()) }
    }

    /** The voice [o] applied over the user's current settings (keys stay as they are). */
    private fun apply(base: VoicePrefs, o: JSONObject) = base.copy(
        engine = o.optString("engine", base.engine),
        geminiModel = o.optString("geminiModel", base.geminiModel), geminiVoice = o.optString("geminiVoice", base.geminiVoice),
        geminiStyle = o.optString("geminiStyle", base.geminiStyle), azureVoice = o.optString("azureVoice", base.azureVoice),
        elevenVoice = o.optString("elevenVoice", base.elevenVoice), elevenVoiceName = o.optString("elevenVoiceName", base.elevenVoiceName),
        elevenModel = o.optString("elevenModel", base.elevenModel), piperModel = o.optString("piperModel", base.piperModel),
        serverVoice = o.optString("serverVoice", base.serverVoice),
    )

    /** Which voices have [texts] on the phone, the current one first. Voices with nothing ready are left out (except the current). */
    suspend fun ready(app: WerdApp, texts: List<String>): List<Ready> = withContext(Dispatchers.IO) {
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val cache = VoiceCache(app)
        val base = app.voice.value
        val curSig = if (base.isClip) base.signature() else ""
        val seen = HashSet<String>()
        val out = ArrayList<Ready>()
        fun count(sig: String) = lines.count { cache.find(cache.key(sig, it)) != null }
        if (base.isClip) { out += Ready(curSig, base.shareName(), count(curSig), lines.size, base, true); seen += curSig }
        for ((sig, v) in sp(app).all) {
            if (sig in seen) continue
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: continue
            val have = count(sig)
            if (have == 0) continue
            val p = apply(base, o)
            if (p.signature() != sig) continue    // settings that no longer produce this voice
            out += Ready(sig, o.optString("name", p.shareName()), have, lines.size, p, false)
            seen += sig
        }
        out.sortedWith(compareByDescending<Ready> { it.current }.thenByDescending { it.have })
    }

    fun use(app: WerdApp, r: Ready) { app.voice.update { r.prefs } }

    /** The werds (id → its lines) that some voice has completely on the phone. */
    suspend fun readyWerds(app: WerdApp, werds: List<Pair<Long, List<String>>>): Set<Long> = withContext(Dispatchers.IO) {
        val cache = VoiceCache(app)
        val base = app.voice.value
        val sigs = (sp(app).all.keys + listOfNotNull(base.takeIf { it.isClip }?.signature())).distinct()
        if (sigs.isEmpty()) return@withContext emptySet()
        werds.filter { (_, texts) ->
            val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            lines.isNotEmpty() && sigs.any { sig -> lines.all { cache.find(cache.key(sig, it)) != null } }
        }.map { it.first }.toSet()
    }
}
