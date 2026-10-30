package com.werd.app.sources

import android.content.Context
import android.graphics.Bitmap
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ai.AiClient
import com.werd.app.ai.AiImage
import com.werd.app.ai.AiTasks
import com.werd.app.ocr.AiReader
import com.werd.app.ocr.NanoReader
import com.werd.app.ocr.OcrPrefs
import com.werd.app.ocr.OcrStore
import com.werd.app.ocr.PageReader
import com.werd.app.ocr.PhoneEngine
import com.werd.app.ocr.ReaderUnavailable
import com.werd.app.ocr.ServerReader
import com.werd.app.ocr.TesseractReader
import kotlinx.coroutines.runBlocking

/**
 * Every reader that can read a page photo, named by a short spec so a book remembers which ones it was read and
 * proofread with:
 *  - "tesseract": the built-in reader (on the phone, fast, weak with tashkeel)
 *  - "server" / "server:<model>": a model on the family server ("" = its default)
 *  - "phone:<model>": a model running on this phone
 *  - "nano": Gemini Nano (some recent phones)
 *  - "gemini" / "claude": the user's own AI key
 */
object Readers {
    const val BUILTIN = "tesseract"
    const val GEMINI = "gemini"
    const val CLAUDE = "claude"

    fun open(context: Context, spec: String, task: String = "", title: String = ""): PageReader = when {
        spec.isBlank() || spec == BUILTIN -> TesseractReader(context)
        spec == "server" || spec.startsWith("server:") -> ServerReader(context, spec.substringAfter(':', ""), task, title)
        spec.startsWith("phone:") -> PhoneEngine.reader(context, spec.substringAfter(':'))
        spec == "nano" -> NanoReader(context)
        spec == GEMINI || spec == CLAUDE -> CloudAiReader(context, spec)
        else -> TesseractReader(context)
    }

    /** The reader the app reads books with on the phone or server (Settings → reader), as a spec. */
    fun chosen(context: Context): String {
        val p = OcrStore.get(context).value
        return when (p.engine) {
            OcrPrefs.SERVER -> if (p.serverModel.isBlank()) "server" else "server:" + p.serverModel
            OcrPrefs.PHONE -> "phone:" + p.phoneModel
            OcrPrefs.NANO -> "nano"
            else -> BUILTIN
        }
    }

    /** The reader that read a book in the first pass. */
    fun firstOf(context: Context, src: com.werd.app.data.Source): String = when (src.provider) {
        Sources.OFFLINE -> chosen(context)
        AiClient.CLAUDE -> CLAUDE
        else -> GEMINI
    }

    /** A name for people: "Built-in reader", "Server · QARI-OCR 0.4", "Gemini"… */
    fun label(context: Context, spec: String, serverNames: Map<String, String> = emptyMap()): String = when {
        spec.isBlank() || spec == BUILTIN -> context.getString(R.string.rd_builtin)
        spec == "server" -> context.getString(R.string.rd_server_default)
        spec.startsWith("server:") -> context.getString(R.string.rd_server, serverNames[spec.substringAfter(':')] ?: AiReader.model(spec.substringAfter(':'))?.name ?: spec.substringAfter(':'))
        spec.startsWith("phone:") -> context.getString(R.string.rd_phone, AiReader.model(spec.substringAfter(':'))?.name ?: spec.substringAfter(':'))
        spec == "nano" -> "Gemini Nano"
        spec == GEMINI -> "Gemini"
        spec == CLAUDE -> "Claude"
        else -> spec
    }

    /** Readers available on this phone now, as (spec, label); the server's are asked for (skipped when it can't be reached). */
    suspend fun choices(context: Context): List<Pair<String, String>> {
        val app = WerdApp.from(context)
        val out = ArrayList<Pair<String, String>>()
        out += BUILTIN to label(context, BUILTIN)
        if (app.cloud.store.value.configured) runCatching {
            val (engine, _, models) = kotlinx.coroutines.withTimeout(8_000) { app.cloud.ocrModels() }
            if (engine) {
                val names = models.toMap()
                models.forEach { (id, _) -> out += "server:$id" to label(context, "server:$id", names) }
            }
        }
        runCatching {
            if (PhoneEngine.supported(context)) AiReader.MODELS.filter { PhoneEngine.installed(context, it.id) }.forEach { out += "phone:${it.id}" to label(context, "phone:${it.id}") }
        }
        val s = app.settings.value
        if (s.aiKeyGemini.isNotBlank()) out += GEMINI to "Gemini"
        if (s.aiKeyClaude.isNotBlank()) out += CLAUDE to "Claude"
        // asking the phone about Gemini Nano can take long (or never answer): not worth waiting for
        val nano = kotlinx.coroutines.withTimeoutOrNull(2_500) { runCatching { NanoReader.status() }.getOrNull() }
        if (nano == com.google.mlkit.genai.common.FeatureStatus.AVAILABLE) out += "nano" to "Gemini Nano"
        return out
    }
}

/** The user's Gemini or Claude reading one page as plain text (for proofreading against another reader). */
class CloudAiReader(private val context: Context, private val provider: String) : PageReader {
    private val client: AiClient by lazy {
        val s = WerdApp.from(context).settings.value
        val key = if (provider == Readers.CLAUDE) s.aiKeyClaude else s.aiKeyGemini
        if (key.isBlank()) throw ReaderUnavailable(context.getString(R.string.ai_needed))
        AiClient.create(provider, key, if (provider == Readers.CLAUDE) s.aiModelClaude else s.aiModelGemini)
    }

    override fun read(bmp: Bitmap, ignoreHarakat: Boolean): String = runBlocking {
        AiReader.clean(AiTasks.transcribe(client, listOf(AiImage(AiReader.jpeg(bmp)))), ignoreHarakat)
    }
}
