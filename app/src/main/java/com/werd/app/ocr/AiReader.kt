package com.werd.app.ocr

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.werd.app.R
import com.werd.app.WerdApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Which engine reads page photos "offline" (i.e. without the user's own cloud AI key):
 *  - [TESSERACT]: the classic OCR built into the app (fast, weak with tashkeel)
 *  - [SERVER]: the family's Werd server runs a free AI model (QARI-OCR, Qwen, Gemma) — no internet needed at home
 *  - [PHONE]: the same free models run on this phone (arm64, 6 GB+ RAM; slow but fully offline)
 *  - [NANO]: Gemini Nano, built into some recent phones (Pixel 9+, Galaxy S25…), via Android's AICore
 */
data class OcrPrefs(
    val engine: String = TESSERACT,
    val phoneModel: String = "qari2",
    /** "" = the server's default model. */
    val serverModel: String = "",
) {
    companion object {
        const val TESSERACT = "tesseract"
        const val SERVER = "server"
        const val PHONE = "phone"
        const val NANO = "nano"
        val ENGINES = listOf(TESSERACT, SERVER, PHONE, NANO)
    }
}

class OcrStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ocr", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val value: OcrPrefs get() = state.value
    val flow: StateFlow<OcrPrefs> = state.asStateFlow()

    private fun load() = OcrPrefs(
        engine = prefs.getString("engine", null) ?: OcrPrefs.TESSERACT,
        phoneModel = prefs.getString("phoneModel", null) ?: "qari2",
        serverModel = prefs.getString("serverModel", null).orEmpty(),
    )

    fun update(f: (OcrPrefs) -> OcrPrefs) {
        val n = f(state.value)
        state.value = n
        prefs.edit { putString("engine", n.engine); putString("phoneModel", n.phoneModel); putString("serverModel", n.serverModel) }
    }

    companion object {
        @Volatile private var inst: OcrStore? = null
        fun get(context: Context): OcrStore = inst ?: synchronized(this) { inst ?: OcrStore(context.applicationContext).also { inst = it } }
    }
}

/** Reads one page image at a time. Kept open across the pages of a book. */
interface PageReader : AutoCloseable {
    /** Blocking; call off the main thread. */
    fun read(bmp: Bitmap, ignoreHarakat: Boolean): String
    override fun close() {}
    /** Stops the page being read now (from another thread). Readers that can't be interrupted ignore it. */
    fun cancel() {}
}

/** [PageReader.read] that a cancelled coroutine stops (the server is told, and its engine stops too). */
suspend fun PageReader.readCancellable(bmp: Bitmap, ignoreHarakat: Boolean): String = kotlinx.coroutines.coroutineScope {
    var finished = false
    val watcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        try { kotlinx.coroutines.awaitCancellation() } finally { if (!finished) this@readCancellable.cancel() }
    }
    try { kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { read(bmp, ignoreHarakat) } }
    finally { finished = true; watcher.cancel() }
}

/** Thrown when the chosen AI reader can't be used (no model, no server…); the message is for the user. */
class ReaderUnavailable(message: String) : Exception(message)

object AiReader {
    const val PROMPT_QARI2 = "Below is the image of one page of a document, as well as some raw textual content that was previously extracted for it. " +
        "Just return the plain text representation of this document as if you were reading it naturally. Do not hallucinate."
    const val PROMPT_ARABIC = "Transcribe all the Arabic text in this image exactly as written, letter by letter, keeping every diacritic (tashkeel) " +
        "and the punctuation and brackets. Keep the line and paragraph breaks. Output only the text, with no explanation, translation or formatting."

    data class ModelFile(val url: String, val name: String, val size: Long)
    data class Model(val id: String, val name: String, val detail: String, val files: List<ModelFile>, val prompt: String, val ramGb: Int)

    private const val HF = "https://huggingface.co/"

    /** The free models (the same as the Werd server's), as GGUF files for llama.cpp. */
    val MODELS = listOf(
        Model("qari2", "QARI-OCR 0.2 (2B)", "Made for Arabic with tashkeel", listOf(
            ModelFile(HF + "mradermacher/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged-GGUF/resolve/main/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged.Q8_0.gguf", "model.gguf", 1_646_571_616),
            ModelFile(HF + "mradermacher/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged-GGUF/resolve/main/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged.mmproj-Q8_0.gguf", "mmproj.gguf", 709_882_976),
        ), PROMPT_QARI2, 6),
        Model("qari4", "QARI-OCR 0.4 (4B)", "Made for Arabic with tashkeel · best accuracy", listOf(
            ModelFile(HF + "marwan-osama/Qari-OCR-0.4.0-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct.Q4_K_M.gguf", "model.gguf", 2_497_281_632),
            ModelFile(HF + "marwan-osama/Qari-OCR-0.4.0-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct.F16-mmproj.gguf", "mmproj.gguf", 836_180_224),
        ), "Free OCR.", 8),
        Model("qwen25vl3b", "Qwen2.5-VL 3B", "General vision model", listOf(
            ModelFile(HF + "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "model.gguf", 1_929_901_056),
            ModelFile(HF + "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf", "mmproj.gguf", 844_757_728),
        ), PROMPT_ARABIC, 6),
        Model("gemma3-4b", "Gemma 3 4B", "General vision model (Google)", listOf(
            ModelFile(HF + "ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf", "model.gguf", 2_489_757_856),
            ModelFile(HF + "ggml-org/gemma-3-4b-it-GGUF/resolve/main/mmproj-model-f16.gguf", "mmproj.gguf", 851_251_104),
        ), PROMPT_ARABIC, 8),
    )

    fun model(id: String) = MODELS.firstOrNull { it.id == id }

    /** Opens the reader for the chosen engine. Tesseract when the engine isn't usable and [fallback] is true. */
    fun open(context: Context, prefs: OcrPrefs = OcrStore.get(context).value, task: String = "", title: String = ""): PageReader = when (prefs.engine) {
        OcrPrefs.SERVER -> ServerReader(context, prefs.serverModel, task, title)
        OcrPrefs.PHONE -> PhoneEngine.reader(context, prefs.phoneModel)
        OcrPrefs.NANO -> NanoReader(context)
        else -> TesseractReader(context)
    }

    fun jpeg(bmp: Bitmap, longEdge: Int = 1600): ByteArray {
        val s = longEdge.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        return ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, 90, out); if (b !== bmp) b.recycle(); out.toByteArray() }
    }

    /** The models' answer → text for the werd parser (fences and lead-ins removed, tashkeel kept unless asked). */
    fun clean(raw: String, ignoreHarakat: Boolean): String {
        var s = raw.trim().removePrefix("```text").removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
        s = s.lines().filterNot { it.trim().matches(Regex("(?i)^(here is|here's|the text|النص)[^\\p{IsArabic}]*:?$")) }.joinToString("\n")
        if (ignoreHarakat) s = s.replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        return Ocr.tidy(s)
    }
}

class TesseractReader(context: Context) : PageReader {
    private val r = Ocr.Reader(context)
    override fun read(bmp: Bitmap, ignoreHarakat: Boolean) = r.read(bmp, ignoreHarakat)
    override fun close() = r.close()
}

/** The family server's AI reader (POST /v1/ocr). */
class ServerReader(private val context: Context, private val model: String, private val task: String = "", private val title: String = "") : PageReader {
    @Volatile private var call: kotlinx.coroutines.Job? = null

    override fun read(bmp: Bitmap, ignoreHarakat: Boolean): String {
        val cloud = WerdApp.from(context).cloud
        if (!cloud.store.value.configured) throw ReaderUnavailable(context.getString(R.string.reader_no_server))
        val text = runBlocking {
            val d = async { cloud.ocr(AiReader.jpeg(bmp), model, task, title) }
            call = d
            try { d.await() } finally { call = null }
        }
        return AiReader.clean(text, ignoreHarakat)
    }

    /** Closes the request (the server stops reading this page) and tells the server the whole job is cancelled. */
    override fun cancel() {
        call?.cancel()
        if (task.isNotBlank()) {
            val app = WerdApp.from(context)
            app.appScope.launch { runCatching { app.cloud.cancelTask(task) } }
        }
    }
}

/** Gemini Nano on the phone (Android AICore through ML Kit's Prompt API): only some recent phones have it. */
class NanoReader(private val context: Context) : PageReader {
    private val model by lazy { com.google.mlkit.genai.prompt.Generation.getClient() }

    override fun read(bmp: Bitmap, ignoreHarakat: Boolean): String = runBlocking {
        when (model.checkStatus()) {
            com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> {}
            com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE -> throw ReaderUnavailable(context.getString(R.string.reader_nano_unavailable))
            else -> throw ReaderUnavailable(context.getString(R.string.reader_nano_download))
        }
        val s = 1024f / maxOf(bmp.width, bmp.height)
        val img = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        try {
            val req = com.google.mlkit.genai.prompt.generateContentRequest(
                com.google.mlkit.genai.prompt.ImagePart(img), com.google.mlkit.genai.prompt.TextPart(AiReader.PROMPT_ARABIC)) {
                temperature = 0f; topK = 1; maxOutputTokens = 256 * 4
            }
            AiReader.clean(model.generateContent(req).candidates.firstOrNull()?.text.orEmpty(), ignoreHarakat)
        } finally { if (img !== bmp) img.recycle() }
    }

    override fun close() { runCatching { model.close() } }

    companion object {
        /** FeatureStatus of Gemini Nano on this phone (UNAVAILABLE on most phones). */
        suspend fun status(): Int = runCatching { com.google.mlkit.genai.prompt.Generation.getClient().use { it.checkStatus() } }
            .getOrDefault(com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE)

        private inline fun <T> com.google.mlkit.genai.prompt.GenerativeModel.use(f: (com.google.mlkit.genai.prompt.GenerativeModel) -> T): T =
            try { f(this) } finally { runCatching { close() } }

        /** Asks AICore to download Nano; [progress] gets bytes done / total. */
        suspend fun download(progress: (Long, Long) -> Unit) {
            val m = com.google.mlkit.genai.prompt.Generation.getClient()
            var total = 0L
            try {
                m.download().collect { st ->
                    when (st) {
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadStarted -> { total = st.bytesToDownload; progress(0, total) }
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadProgress -> progress(st.totalBytesDownloaded, total)
                        is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed -> throw st.e
                        else -> {}
                    }
                }
            } finally { runCatching { m.close() } }
        }
    }
}

/**
 * Runs llama.cpp's llama-server (bundled in the app as a native "library" so Android lets it run) with a downloaded
 * vision model, and asks it to read pages over 127.0.0.1 — the same engine and API the Werd server uses.
 * One model stays loaded while pages are read; it's stopped when the reader is closed and nothing else uses it.
 */
object PhoneEngine {
    private var proc: Process? = null
    private var port = 0
    private var loaded = ""
    private var users = 0

    /** The engine built for this phone's CPU (dotprod = most phones since 2019), or null (32-bit phones). */
    fun binary(context: Context): File? {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val dot = File(dir, "libllamasrv_dot.so")
        val base = File(dir, "libllamasrv.so")
        val features = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        val hasDot = Regex("(?m)^Features\\s*:.*\\basimddp\\b").containsMatchIn(features)
        return when {
            hasDot && dot.exists() -> dot
            base.exists() -> base
            else -> null
        }
    }

    fun supported(context: Context) = binary(context) != null

    fun modelsDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, id: String, name: String) = File(File(modelsDir(context), id), name)
    fun installed(context: Context, id: String): Boolean =
        AiReader.model(id)?.files?.all { file(context, id, it.name).let { f -> f.exists() && f.length() == it.size } } == true
    fun sizeOnDisk(context: Context): Long = modelsDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun remove(context: Context, id: String) { synchronized(this) { if (loaded == id) stop() }; File(modelsDir(context), id).deleteRecursively() }
    /** Total RAM in GB (rounded), to warn before downloading a model the phone can't hold. */
    fun ramGb(context: Context): Int {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Math.round(mi.totalMem / 1e9).toInt()
    }

    fun reader(context: Context, id: String): PageReader {
        val m = AiReader.model(id) ?: throw ReaderUnavailable(context.getString(R.string.reader_no_model))
        if (binary(context) == null) throw ReaderUnavailable(context.getString(R.string.reader_phone_unsupported))
        if (!installed(context, id)) throw ReaderUnavailable(context.getString(R.string.reader_no_model))
        synchronized(this) { users++ }
        return object : PageReader {
            override fun read(bmp: Bitmap, ignoreHarakat: Boolean): String {
                val p = synchronized(this@PhoneEngine) { ensure(context, m) }
                return AiReader.clean(chat(p, AiReader.jpeg(bmp, 1280), m.prompt), ignoreHarakat)
            }
            override fun close() = synchronized(this@PhoneEngine) { users--; if (users <= 0) { users = 0; stop() } }
        }
    }

    private fun stop() {
        proc?.destroy()
        runCatching { proc?.waitFor() }
        proc = null; loaded = ""; port = 0
    }

    private fun ensure(context: Context, m: AiReader.Model): Int {
        proc?.let { if (loaded == m.id && it.isAlive) return port }
        stop()
        val bin = binary(context)!!
        port = ServerSocket(0).use { it.localPort }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it > 4) it - 2 else it }
        val log = File(context.cacheDir, "llama-server.log")
        val cmd = listOf(bin.absolutePath,
            "-m", file(context, m.id, "model.gguf").absolutePath, "--mmproj", file(context, m.id, "mmproj.gguf").absolutePath,
            "--host", "127.0.0.1", "--port", port.toString(), "-c", "6144", "-np", "1", "-t", threads.toString(),
            "--no-warmup", "--image-max-tokens", "1024")
        proc = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start()
        loaded = m.id
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            if (proc?.isAlive != true) {
                val tail = runCatching { log.readLines().takeLast(4).joinToString("\n") }.getOrDefault("")
                stop(); throw IOException(context.getString(R.string.reader_engine_stopped) + "\n" + tail)
            }
            val ok = runCatching {
                (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).run {
                    connectTimeout = 1000; readTimeout = 2000
                    try { responseCode == 200 } finally { disconnect() }
                }
            }.getOrDefault(false)
            if (ok) return port
            Thread.sleep(500)
        }
        stop(); throw IOException(context.getString(R.string.reader_engine_slow))
    }

    private fun chat(port: Int, jpeg: ByteArray, prompt: String): String {
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))))
                .put(JSONObject().put("type", "text").put("text", prompt)))))
            .put("temperature", 0).put("max_tokens", 2500).put("repeat_penalty", 1.05).put("stream", false)
        val c = (URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 30 * 60_000; requestMethod = "POST"; doOutput = true
            setRequestProperty("content-type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val raw = (if (code == 200) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code != 200) throw IOException("AI engine: HTTP $code ${raw.take(200)}")
            return JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- downloads (a foreground worker: the files are GBs)

    data class Download(val id: String, val done: Long, val total: Long, val error: String? = null)
    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    fun startDownload(context: Context, id: String) {
        val m = AiReader.model(id) ?: return
        _download.value = Download(id, 0, m.files.sumOf { it.size })
        WorkManager.getInstance(context).enqueueUniqueWork("model-download", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().setInputData(workDataOf("id" to id)).build())
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("model-download")
        _download.value = null
    }

    internal suspend fun fetch(context: Context, id: String, onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val m = AiReader.model(id) ?: return@withContext
        val total = m.files.sumOf { it.size }
        var base = 0L
        for (f in m.files) {
            val dst = file(context, id, f.name).also { it.parentFile?.mkdirs() }
            if (dst.exists() && dst.length() == f.size) { base += f.size; continue }
            val part = File(dst.path + ".part")
            var have = if (part.exists()) part.length() else 0L
            var tries = 0
            while (have < f.size) {
                try {
                    val c = (URL(f.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                        if (have > 0) setRequestProperty("Range", "bytes=$have-")
                    }
                    val code = c.responseCode
                    if (code == 200) have = 0 else if (code != 206) throw IOException("HTTP $code")
                    java.io.FileOutputStream(part, have > 0).use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(1 shl 16)
                            var last = 0L
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); have += n
                                if (have - last > 4_000_000) { last = have; onProgress(base + have, total); _download.value = Download(id, base + have, total) }
                            }
                        }
                    }
                    c.disconnect()
                    if (have < f.size) throw IOException("cut off")
                } catch (e: IOException) {
                    if (++tries > 8) throw e
                    delay(3000L * tries)
                }
            }
            if (!part.renameTo(dst)) throw IOException("rename")
            base += f.size
        }
        _download.value = null
    }

    internal fun failed(id: String, msg: String) { _download.value = Download(id, 0, 0, msg) }
}

class ModelDownloadWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val m = AiReader.model(id) ?: return Result.failure()
        com.werd.app.sources.SourceWorker.createChannel(ctx)
        fun info(done: Long, total: Long): ForegroundInfo {
            val n = NotificationCompat.Builder(ctx, com.werd.app.sources.SourceWorker.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setOngoing(true).setOnlyAlertOnce(true)
                .setContentTitle(m.name).setContentText("%.1f / %.1f GB".format(done / 1e9, total / 1e9))
                .setProgress(1000, if (total > 0) (1000 * done / total).toInt() else 0, total <= 0).build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(3_100_000, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(3_100_000, n)
        }
        runCatching { setForeground(info(0, m.files.sumOf { it.size })) }
        return try {
            PhoneEngine.fetch(ctx, id) { d, t -> runCatching { setForeground(info(d, t)) } }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PhoneEngine.failed(id, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }
}
