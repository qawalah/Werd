package com.werd.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import com.werd.app.parse.WerdParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Offline Arabic text recognition (Tesseract) for photos, images and PDF pages.
 * PDFs are rendered page by page and recognised like images: Arabic text extraction from PDFs is unreliable
 * (reversed words, lost ligatures), while rendering + OCR behaves the same for scanned and digital PDFs.
 */
object Ocr {
    private const val LANG = "ara"
    const val MAX_PDF_PAGES = 40

    /** Copies the bundled model to internal storage once (Tesseract needs a real file path). */
    private fun dataPath(context: Context): String {
        val root = File(context.filesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        val f = File(dir, "$LANG.traineddata")
        val size = context.assets.openFd("tessdata/$LANG.traineddata").use { it.length }
        if (!f.exists() || f.length() != size) {
            context.assets.open("tessdata/$LANG.traineddata").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return root.absolutePath
    }

    private inline fun <T> withTess(context: Context, block: (TessBaseAPI) -> T): T {
        val api = TessBaseAPI()
        check(api.init(dataPath(context), LANG, TessBaseAPI.OEM_LSTM_ONLY)) { "OCR engine failed to start" }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        try { return block(api) } finally { api.recycle() }
    }

    /** Keeps one engine open across many pages (book reading). */
    class Reader(context: Context) : AutoCloseable {
        private val api = TessBaseAPI().also { api ->
            check(api.init(dataPath(context), LANG, TessBaseAPI.OEM_LSTM_ONLY)) { "OCR engine failed to start" }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        }
        fun read(bmp: Bitmap, ignoreHarakat: Boolean): String = tidy(recognise(api, bmp, ignoreHarakat))
        override fun close() = api.recycle()
    }

    /** Grayscale → adaptive threshold → (optionally) tashkeel removal → black/white bitmap. */
    fun prepare(src: Bitmap, ignoreHarakat: Boolean): Bitmap {
        val s = Preprocess.scaleFor(src.width, src.height)
        val bmp = if (s != 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true) else src
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val p = px[i]
            val a = (p ushr 24) and 255
            val g = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
            if (a < 128) 255 else g            // transparent = paper
        }
        val ink = Preprocess.binarize(gray, w, h)
        if (ignoreHarakat) Preprocess.removeMarks(ink, w, h)
        for (i in px.indices) px[i] = if (ink[i]) Color.BLACK else Color.WHITE
        if (bmp !== src) bmp.recycle()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun recognise(api: TessBaseAPI, bmp: Bitmap, ignoreHarakat: Boolean): String {
        val clean = prepare(bmp, ignoreHarakat)
        api.setImage(clean)
        val text = api.utF8Text.orEmpty()
        api.clear()
        clean.recycle()
        return text
    }

    suspend fun fromImage(context: Context, uri: Uri, ignoreHarakat: Boolean): String = withContext(Dispatchers.Default) {
        val bmp = decodeImage(context, uri) ?: return@withContext ""
        try {
            if (OcrStore.get(context).value.engine == OcrPrefs.TESSERACT) tidy(withTess(context) { recognise(it, bmp, ignoreHarakat) })
            else AiReader.open(context).use { it.read(bmp, ignoreHarakat) }
        } finally { bmp.recycle() }
    }

    suspend fun fromPdf(
        context: Context, uri: Uri, ignoreHarakat: Boolean, onProgress: (page: Int, pages: Int) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ""
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                val out = StringBuilder()
                if (OcrStore.get(context).value.engine != OcrPrefs.TESSERACT) AiReader.open(context).use { r ->
                    for (i in 0 until pages) {
                        coroutineContext.ensureActive()
                        onProgress(i + 1, pages)
                        renderer.openPage(i).use { page ->
                            val scale = 1600f / maxOf(page.width, page.height)
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            try { out.append(r.read(bmp, ignoreHarakat)).append("\n\n") } finally { bmp.recycle() }
                        }
                    }
                    return@withContext out.toString().trim()
                }
                withTess(context) { api ->
                    for (i in 0 until pages) {
                        coroutineContext.ensureActive()
                        onProgress(i + 1, pages)
                        renderer.openPage(i).use { page ->
                            val scale = 2000f / page.width
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            out.append(recognise(api, bmp, ignoreHarakat)).append("\n\n")
                            bmp.recycle()
                        }
                    }
                }
                tidy(out.toString())
            }
        }
    }

    // ---------------------------------------------------------------- AI (online, keeps tashkeel)

    private fun jpeg(bmp: Bitmap, longEdge: Int = 1600): ByteArray {
        val s = longEdge.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        return java.io.ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, 88, out); if (b !== bmp) b.recycle(); out.toByteArray() }
    }

    suspend fun aiFromImage(context: Context, uri: Uri, ai: com.werd.app.ai.AiClient): String = withContext(Dispatchers.IO) {
        val bmp = decodeImage(context, uri) ?: return@withContext ""
        val bytes = jpeg(bmp); bmp.recycle()
        com.werd.app.ai.AiTasks.transcribe(ai, listOf(com.werd.app.ai.AiImage(bytes)))
    }

    suspend fun aiFromPdf(context: Context, uri: Uri, ai: com.werd.app.ai.AiClient, onProgress: (Int, Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ""
            pfd.use {
                PdfRenderer(it).use { r ->
                    val pages = minOf(r.pageCount, MAX_PDF_PAGES)
                    val out = StringBuilder()
                    for (start in 0 until pages step 4) {
                        coroutineContext.ensureActive()
                        val end = minOf(start + 4, pages)
                        onProgress(end, pages)
                        val imgs = (start until end).map { i ->
                            r.openPage(i).use { page ->
                                val s = 1600f / maxOf(page.width, page.height)
                                val bmp = Bitmap.createBitmap((page.width * s).toInt(), (page.height * s).toInt(), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                com.werd.app.ai.AiImage(jpeg(bmp)).also { bmp.recycle() }
                            }
                        }
                        out.append(com.werd.app.ai.AiTasks.transcribe(ai, imgs)).append("\n\n")
                    }
                    out.toString().trim()
                }
            }
        }

    /** Decodes at a sensible size and applies the camera's EXIF rotation. */
    private fun decodeImage(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2600) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return bmp
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    // ---------------------------------------------------------------- OCR-specific corrections

    private val countWords = listOf(
        "ثلاثا" to "ثلاثاً", "ثلاث مرات" to "ثلاث مرات", "مرتين" to "مرتين", "مرة" to "مرة", "مرة واحدة" to "مرة واحدة",
        "اربعا" to "أربعاً", "خمسا" to "خمساً", "سبعا" to "سبعاً", "سبع مرات" to "سبع مرات", "عشرا" to "عشراً",
        "عشر مرات" to "عشر مرات", "مائة مرة" to "مائة مرة", "ثلاثا وثلاثين" to "ثلاثاً وثلاثين",
    )

    private fun bare(s: String) = s.filter { it !in '\u064B'..'\u065F' && it != '\u0670' && it != 'ـ' }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

    private fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** Repairs what Tesseract typically gets wrong in awrad: commas read as » or ء, and garbled count words. */
    fun fixLine(line: String): String {
        var s = line
        // the Arabic comma ، is often read as » (or a lone ء)
        if ('«' !in s) s = s.replace(Regex("\\s*»"), "،")
        s = s.replace(Regex("(?<=^|[\\s)\\]])ء(?=\\s|$)"), "،")
        s = s.replace(Regex("\\s+،"), "،")
        // "(ثلا0)" / "(ثلائاً)" → "(ثلاثاً)": short bracketed text that is nearly a count word
        s = s.replace(Regex("\\(([^()]{2,14})\\)")) { m ->
            val inner = m.groupValues[1].trim()
            if (WerdParser.detect("($inner)").count != null) return@replace m.value
            val b = bare(inner).replace(Regex("\\d"), "؟")
            val best = countWords.minByOrNull { distance(b, bare(it.first)) } ?: return@replace m.value
            val d = distance(b, bare(best.first))
            if (d <= 2 && d * 2 <= bare(best.first).length) "(${best.second})" else m.value
        }
        return s
    }

    /**
     * Turns Tesseract's visual lines back into text the werd parser understands:
     * lines of the same paragraph are joined, but a line that ends with a repetition count or a full stop
     * keeps its break (adhkar booklets usually put one dhikr per line).
     */
    fun tidy(raw: String): String {
        val out = StringBuilder()
        var para = StringBuilder()
        fun endPara() { if (para.isNotBlank()) { if (out.isNotEmpty()) out.append('\n'); out.append(para.trim()) }; para = StringBuilder() }
        for (line0 in raw.lines()) {
            val line = fixLine(line0)
                .replace(Regex("[|_~^`<>{}\\\\]"), " ")
                .replace(Regex("(?<![\\p{IsArabic}\\d])[A-Za-z]{1,2}(?![A-Za-z])"), " ")   // stray Latin specks
                .replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty() || line.none { it.isLetterOrDigit() }) { endPara(); continue }
            if (para.isNotEmpty()) para.append(' ')
            para.append(line)
            val endsDhikr = WerdParser.detect(line.takeLast(28)).count != null ||
                line.trimEnd().lastOrNull()?.let { it in ".؟?!۔" } == true
            if (endsDhikr) endPara()
        }
        endPara()
        return out.toString()
    }
}
