package com.werd.app.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which voice reads awrad aloud.
 *  - [SYSTEM]: the phone's text-to-speech (as before)
 *  - [GEMINI], [AZURE], [ELEVEN]: natural cloud voices, with the user's own key
 *  - [PIPER]: a natural voice that runs on the phone, no internet (downloaded once)
 *  - [SERVER]: the family's Werd server speaks (its owner's key) and keeps a shared library of clips
 * Every clip except the system voice is kept on the phone, so a line is only ever made once.
 */
data class VoicePrefs(
    val engine: String = SYSTEM,
    /** "" = use the Gemini key from "Reading books with AI". */
    val geminiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val geminiVoice: String = "Charon",
    /** Optional delivery, e.g. "calm and reverent". */
    val geminiStyle: String = "",
    val azureKey: String = "",
    val azureRegion: String = "uaenorth",
    val azureVoice: String = "ar-SA-HamedNeural",
    val elevenKey: String = "",
    val elevenVoice: String = "",
    val elevenVoiceName: String = "",
    val elevenModel: String = DEFAULT_ELEVEN_MODEL,
    val piperModel: String = PiperVoice.MEDIUM,
    /** "provider:voice" on the server; "" = the server's default. */
    val serverVoice: String = "",
    /** Send the clips this phone makes (own voice or own key) to the family server, so other phones can use that voice. */
    val shareToServer: Boolean = true,
) {
    val isClip: Boolean get() = engine != SYSTEM

    /** Identifies the sound of a clip: changes when the voice, model or style changes, so old clips aren't reused. */
    fun signature(): String = when (engine) {
        GEMINI -> "gemini|$geminiModel|$geminiVoice|$geminiStyle"
        AZURE -> "azure|$azureVoice"
        ELEVEN -> "eleven|$elevenModel|$elevenVoice"
        PIPER -> "piper|$piperModel"
        SERVER -> "server|$serverVoice"
        else -> "system"
    }

    /** The voice's id in the family server's sound library (the same on every phone with the same settings). */
    fun shareId(): String = "shared:" + java.security.MessageDigest.getInstance("SHA-256").digest(signature().toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    /** How the shared voice is named on other phones. */
    fun shareName(): String = when (engine) {
        GEMINI -> "Gemini · $geminiVoice" + if (geminiStyle.isNotBlank()) " · $geminiStyle" else ""
        AZURE -> "Azure · " + azureVoice.removeSuffix("Neural")
        ELEVEN -> "ElevenLabs · " + elevenVoiceName.ifBlank { elevenVoice.take(8) }
        PIPER -> "Piper · Kareem" + if (piperModel == PiperVoice.LOW) " (fast)" else ""
        else -> engine
    }

    companion object {
        const val SYSTEM = "system"
        const val GEMINI = "gemini"
        const val AZURE = "azure"
        const val ELEVEN = "eleven"
        const val PIPER = "piper"
        const val SERVER = "server"
        val ENGINES = listOf(SYSTEM, PIPER, GEMINI, AZURE, ELEVEN, SERVER)
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash-tts"
        const val DEFAULT_ELEVEN_MODEL = "eleven_multilingual_v2"
        val GEMINI_VOICES = listOf("Charon", "Kore", "Orus", "Puck", "Fenrir", "Leda", "Aoede", "Zephyr", "Enceladus", "Iapetus",
            "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
            "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat", "Callirrhoe", "Autonoe")
        /** Azure Arabic voices to start with (the full list is fetched once a key is set). */
        val AZURE_VOICES = listOf("ar-SA-HamedNeural", "ar-SA-ZariyahNeural", "ar-EG-ShakirNeural", "ar-EG-SalmaNeural",
            "ar-AE-HamdanNeural", "ar-AE-FatimaNeural", "ar-KW-FahedNeural", "ar-QA-MoazNeural", "ar-JO-TaimNeural", "ar-LB-RamiNeural")
        val AZURE_REGIONS = listOf("uaenorth", "qatarcentral", "westeurope", "northeurope", "eastus", "westus2", "southeastasia", "centralindia")
    }
}

class VoiceStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("werd_voice", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<VoicePrefs> = _flow.asStateFlow()
    val value: VoicePrefs get() = _flow.value

    private fun s(k: String, d: String) = prefs.getString(k, d) ?: d

    private fun read() = VoicePrefs(
        engine = s("engine", VoicePrefs.SYSTEM),
        geminiKey = s("geminiKey", ""), geminiModel = s("geminiModel", VoicePrefs.DEFAULT_GEMINI_MODEL).ifBlank { VoicePrefs.DEFAULT_GEMINI_MODEL },
        geminiVoice = s("geminiVoice", "Charon"), geminiStyle = s("geminiStyle", ""),
        azureKey = s("azureKey", ""), azureRegion = s("azureRegion", "uaenorth"), azureVoice = s("azureVoice", "ar-SA-HamedNeural"),
        elevenKey = s("elevenKey", ""), elevenVoice = s("elevenVoice", ""), elevenVoiceName = s("elevenVoiceName", ""),
        elevenModel = s("elevenModel", VoicePrefs.DEFAULT_ELEVEN_MODEL).ifBlank { VoicePrefs.DEFAULT_ELEVEN_MODEL },
        piperModel = s("piperModel", PiperVoice.MEDIUM), serverVoice = s("serverVoice", ""),
        shareToServer = prefs.getBoolean("shareToServer", true),
    )

    @Synchronized
    fun update(block: (VoicePrefs) -> VoicePrefs) {
        val n = block(_flow.value)
        prefs.edit {
            putString("engine", n.engine); putString("geminiKey", n.geminiKey); putString("geminiModel", n.geminiModel)
            putString("geminiVoice", n.geminiVoice); putString("geminiStyle", n.geminiStyle)
            putString("azureKey", n.azureKey); putString("azureRegion", n.azureRegion); putString("azureVoice", n.azureVoice)
            putString("elevenKey", n.elevenKey); putString("elevenVoice", n.elevenVoice); putString("elevenVoiceName", n.elevenVoiceName)
            putString("elevenModel", n.elevenModel); putString("piperModel", n.piperModel); putString("serverVoice", n.serverVoice)
            putBoolean("shareToServer", n.shareToServer)
        }
        _flow.value = n
    }
}
