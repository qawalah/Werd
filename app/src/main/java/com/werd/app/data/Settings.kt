package com.werd.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GroupMode { GROUP, TIME, STATUS, NONE }
enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class AppSettings(
    val language: String = "",          // "" = system, "ar", "en"
    val theme: ThemeMode = ThemeMode.LIGHT,
    val palette: String = "STANDARD",
    /** The focus counter: "ring" (the turning misbaha loop) or "pull" (pull beads down a hanging string). */
    val counterStyle: String = "ring",
    /** Page reading of new books: 0 one pass, 1 two passes, 2 a second pass for doubtful pages (see TwoPass). */
    val scanMode: Int = 0,
    /** The second reader for two-pass scans (see Readers); "" = the built-in one. */
    val checkReader: String = "",
    /** Proofreading: the share of the screen the original page gets (0.2–0.8). */
    val proofSplit: Float = 0.45f,
    /** The reader's counter: 0 large, 1 medium, 2 slim, 3 hidden (tap anywhere on the text). */
    val counterSize: Int = 1,
    /** Show the bead string above the counter. */
    val counterBeads: Boolean = true,
    val groupMode: GroupMode = GroupMode.GROUP,
    val collapsed: Set<String> = emptySet(),
    val fontScale: Float = 1f,
    val haptics: Boolean = true,
    val tapCardToCount: Boolean = true,
    val autoAdvance: Boolean = true,
    val volumeKeys: Boolean = false,
    val keepScreenOn: Boolean = true,
    val dayStartHour: Int = 0,
    val ttsRate: Float = 0.9f,
    val ttsPitch: Float = 1f,
    val ttsAutoCount: Boolean = true,
    val ttsVoice: String = "",
    /** Text recognition drops tashkeel marks (much more accurate on vocalised text). */
    val ocrLettersOnly: Boolean = true,
    // AI (user's own key)
    val aiProvider: String = "gemini",
    val aiKeyGemini: String = "",
    val aiKeyClaude: String = "",
    val aiModelGemini: String = "",
    val aiModelClaude: String = "",
    /** Use AI instead of offline recognition when scanning in the editor. */
    val aiScan: Boolean = true,
    /** When adding from a source, file each werd into the group matching its occasion. */
    val autoGroup: Boolean = true,
    val ttsEngine: String = "",
    val focusMode: Boolean = false,
    val onboarded: Boolean = false,
    val seeded: Boolean = false,
    // prayer-time reminders (default: Makkah, Umm al-Qura)
    val prayerLat: Double = 21.4225,
    val prayerLng: Double = 39.8262,
    val prayerPlace: String = "",
    val prayerMethod: String = "UMM_AL_QURA",
    val asrHanafi: Boolean = false,
) {
    val method: com.werd.app.util.PrayerTimes.Method
        get() = runCatching { com.werd.app.util.PrayerTimes.Method.valueOf(prayerMethod) }
            .getOrDefault(com.werd.app.util.PrayerTimes.Method.UMM_AL_QURA)

    val aiKey: String get() = if (aiProvider == "claude") aiKeyClaude else aiKeyGemini
    val aiReady: Boolean get() = aiKey.isNotBlank()

    fun prayerTimes(date: java.time.LocalDate) =
        com.werd.app.util.PrayerTimes.compute(date, prayerLat, prayerLng, method, asrHanafi)
}

/** SharedPreferences-backed settings, readable synchronously (receivers / widgets) and as a flow (UI). */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("werd_settings", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val value: AppSettings get() = _flow.value

    private fun read() = AppSettings(
        language = prefs.getString("language", "") ?: "",
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "LIGHT")!!) }.getOrDefault(ThemeMode.LIGHT),
        palette = prefs.getString("palette", "STANDARD") ?: "STANDARD",
        counterStyle = prefs.getString("counterStyle", "ring") ?: "ring",
        scanMode = prefs.getInt("scanMode", 0),
        checkReader = prefs.getString("checkReader", "") ?: "",
        proofSplit = prefs.getFloat("proofSplit", 0.45f),
        counterSize = prefs.getInt("counterSize", 1),
        counterBeads = prefs.getBoolean("counterBeads", true),
        groupMode = runCatching { GroupMode.valueOf(prefs.getString("groupMode", "GROUP")!!) }.getOrDefault(GroupMode.GROUP),
        collapsed = prefs.getStringSet("collapsed", emptySet()) ?: emptySet(),
        fontScale = prefs.getFloat("fontScale", 1f),
        haptics = prefs.getBoolean("haptics", true),
        tapCardToCount = prefs.getBoolean("tapCard", true),
        autoAdvance = prefs.getBoolean("autoAdvance", true),
        volumeKeys = prefs.getBoolean("volumeKeys", false),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        dayStartHour = prefs.getInt("dayStartHour", 0),
        ttsRate = prefs.getFloat("ttsRate", 0.9f),
        ttsPitch = prefs.getFloat("ttsPitch", 1f),
        // the count follows the voice by default (older installs saved "off" as the old default)
        ttsAutoCount = prefs.getBoolean("ttsAutoCount2", true),
        ttsVoice = prefs.getString("ttsVoice", "") ?: "",
        ocrLettersOnly = prefs.getBoolean("ocrLettersOnly", true),
        aiProvider = prefs.getString("aiProvider", "gemini") ?: "gemini",
        aiKeyGemini = prefs.getString("aiKeyGemini", "") ?: "",
        aiKeyClaude = prefs.getString("aiKeyClaude", "") ?: "",
        aiModelGemini = prefs.getString("aiModelGemini", "") ?: "",
        aiModelClaude = prefs.getString("aiModelClaude", "") ?: "",
        aiScan = prefs.getBoolean("aiScan", true),
        autoGroup = prefs.getBoolean("autoGroup", true),
        ttsEngine = prefs.getString("ttsEngine", "") ?: "",
        focusMode = prefs.getBoolean("focusMode", false),
        onboarded = prefs.getBoolean("onboarded", false),
        seeded = prefs.getBoolean("seeded", false),
        prayerLat = Double.fromBits(prefs.getLong("prayerLat", 21.4225.toRawBits())),
        prayerLng = Double.fromBits(prefs.getLong("prayerLng", 39.8262.toRawBits())),
        prayerPlace = prefs.getString("prayerPlace", "") ?: "",
        prayerMethod = prefs.getString("prayerMethod", "UMM_AL_QURA") ?: "UMM_AL_QURA",
        asrHanafi = prefs.getBoolean("asrHanafi", false),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val n = block(_flow.value)
        prefs.edit {
            putString("language", n.language)
            putString("theme", n.theme.name)
            putString("palette", n.palette)
            putString("counterStyle", n.counterStyle)
            putInt("scanMode", n.scanMode)
            putString("checkReader", n.checkReader); putFloat("proofSplit", n.proofSplit)
            putInt("counterSize", n.counterSize); putBoolean("counterBeads", n.counterBeads)
            putString("groupMode", n.groupMode.name)
            putStringSet("collapsed", n.collapsed)
            putFloat("fontScale", n.fontScale)
            putBoolean("haptics", n.haptics)
            putBoolean("tapCard", n.tapCardToCount)
            putBoolean("autoAdvance", n.autoAdvance)
            putBoolean("volumeKeys", n.volumeKeys)
            putBoolean("keepScreenOn", n.keepScreenOn)
            putInt("dayStartHour", n.dayStartHour)
            putFloat("ttsRate", n.ttsRate)
            putFloat("ttsPitch", n.ttsPitch)
            putBoolean("ttsAutoCount2", n.ttsAutoCount)
            putString("ttsVoice", n.ttsVoice)
            putBoolean("ocrLettersOnly", n.ocrLettersOnly)
            putString("aiProvider", n.aiProvider)
            putString("aiKeyGemini", n.aiKeyGemini)
            putString("aiKeyClaude", n.aiKeyClaude)
            putString("aiModelGemini", n.aiModelGemini)
            putString("aiModelClaude", n.aiModelClaude)
            putBoolean("aiScan", n.aiScan)
            putBoolean("autoGroup", n.autoGroup)
            putString("ttsEngine", n.ttsEngine)
            putBoolean("focusMode", n.focusMode)
            putBoolean("onboarded", n.onboarded)
            putBoolean("seeded", n.seeded)
            putLong("prayerLat", n.prayerLat.toRawBits())
            putLong("prayerLng", n.prayerLng.toRawBits())
            putString("prayerPlace", n.prayerPlace)
            putString("prayerMethod", n.prayerMethod)
            putBoolean("asrHanafi", n.asrHanafi)
        }
        _flow.value = n
    }

    // Small key/value store used by the connection triggers.
    fun getString(key: String): String = prefs.getString("x_$key", "") ?: ""
    fun putString(key: String, v: String) = prefs.edit { putString("x_$key", v) }
}
