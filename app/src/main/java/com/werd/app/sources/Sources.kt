package com.werd.app.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.werd.app.WerdApp
import com.werd.app.ai.AiClient
import com.werd.app.ai.AiImage
import com.werd.app.data.Source
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Pages of a stored source, as JPEGs for the AI (or text chunks for text sources). */
class PageSource(private val source: Source) : AutoCloseable {
    private var pfd: ParcelFileDescriptor? = null
    private var pdf: PdfRenderer? = null

    val pageCount: Int get() = source.pageCount

    /** 0-based page → JPEG, long edge ≈ [longEdge] px. */
    fun jpeg(index: Int, longEdge: Int = 1600): ByteArray {
        val bmp = bitmap(index, longEdge)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out); bmp.recycle(); out.toByteArray()
        }
    }

    fun bitmap(index: Int, longEdge: Int = 1600): Bitmap = when (source.kind) {
        Sources.KIND_PDF -> {
            val r = pdf ?: PdfRenderer(ParcelFileDescriptor.open(File(source.path), ParcelFileDescriptor.MODE_READ_ONLY).also { pfd = it }).also { pdf = it }
            r.openPage(index).use { page ->
                val s = longEdge.toFloat() / maxOf(page.width, page.height)
                val bmp = Bitmap.createBitmap((page.width * s).toInt().coerceAtLeast(1), (page.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bmp
            }
        }
        Sources.KIND_IMAGES -> {
            val f = Sources.imageFiles(source)[index]
            val bmp = BitmapFactory.decodeFile(f.path)
            val s = longEdge.toFloat() / maxOf(bmp.width, bmp.height)
            if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true).also { if (it !== bmp) bmp.recycle() } else bmp
        }
        else -> error("this source has no page images")
    }

    fun textChunk(index: Int): String =
        if (source.kind == Sources.KIND_IMPORTED) "" else Sources.textChunks(File(source.path).readText()).getOrElse(index) { "" }

    override fun close() { runCatching { pdf?.close() }; runCatching { pfd?.close() } }
}

object Sources {
    const val KIND_PDF = "pdf"
    const val KIND_IMAGES = "images"
    const val KIND_TEXT = "text"
    /** A book file (.werdbook) or a library download: texts only, no page images. */
    const val KIND_IMPORTED = "imported"
    const val BATCH = 4
    /** Source.provider for books read on the phone (Tesseract + rules) instead of AI. */
    const val OFFLINE = "offline"
    private const val CHUNK = 6_000

    fun dir(context: Context) = File(context.filesDir, "sources").apply { mkdirs() }

    fun imageFiles(source: Source): List<File> =
        File(source.path).listFiles { f -> f.extension == "jpg" }.orEmpty().sortedBy { it.name }

    /** Splits text into parts of ~6k characters at paragraph boundaries. */
    fun textChunks(text: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (para in text.split(Regex("\\n\\s*\\n|\\n"))) {
            if (sb.length + para.length > CHUNK && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            sb.append(para).append('\n')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.replace('_', ' ')?.trim().orEmpty()

    /** Screenshot tests only: stands in for the user's AI provider in the editor. */
    @androidx.annotation.VisibleForTesting @Volatile var clientOverride: AiClient? = null

    fun aiClient(context: Context): AiClient? {
        clientOverride?.let { return it }
        val s = WerdApp.from(context).settings.value
        val key = if (s.aiProvider == AiClient.CLAUDE) s.aiKeyClaude else s.aiKeyGemini
        if (key.isBlank()) return null
        val model = if (s.aiProvider == AiClient.CLAUDE) s.aiModelClaude else s.aiModelGemini
        return AiClient.create(s.aiProvider, key, model)
    }

    /** Copies the picked file(s) into app storage and registers the source. Returns its id. */
    suspend fun add(context: Context, uris: List<Uri>, kindHint: String, title: String, useAi: Boolean = true, scanMode: Int = 0,
                    checkReader: String = ""): Long {
        val app = WerdApp.from(context)
        val cr = context.contentResolver
        val id = UUID.randomUUID().toString()
        val first = uris.first()
        val mime = cr.getType(first).orEmpty()
        val kind = when {
            kindHint == KIND_IMAGES || mime.startsWith("image/") -> KIND_IMAGES
            mime == "application/pdf" || first.toString().endsWith(".pdf", true) || kindHint == KIND_PDF -> KIND_PDF
            else -> KIND_TEXT
        }
        if (kind == KIND_TEXT) {
            // a book file someone shared: add it as it is, nothing to read
            val text = cr.openInputStream(first)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            if (BookFile.looksLikeBook(text)) return BookFile.import(app.db.dao(), BookFile.parse(text))
        }
        val (path, pages) = when (kind) {
            KIND_PDF -> {
                val f = File(dir(context), "$id.pdf")
                cr.openInputStream(first)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                val n = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } }
                f.path to n
            }
            KIND_IMAGES -> {
                val d = File(dir(context), id).apply { mkdirs() }
                uris.forEachIndexed { i, u -> saveImage(context, u, File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", i + 1))) }
                d.path to uris.size
            }
            else -> {
                val f = File(dir(context), "$id.txt")
                val text = cr.openInputStream(first)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val plain = if (text.trimStart().startsWith("<")) text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "").replace(Regex("<[^>]+>"), "\n") else text
                f.writeText(plain)
                f.path to textChunks(plain).size
            }
        }
        val s = app.settings.value
        val sid = app.db.dao().insertSource(Source(
            title = title.ifBlank { displayName(context, first) }.ifBlank { "—" },
            kind = kind, path = path, pageCount = pages, provider = if (useAi) s.aiProvider else OFFLINE,
            model = if (!useAi) "" else if (s.aiProvider == AiClient.CLAUDE) s.aiModelClaude else s.aiModelGemini,
            scanMode = if (kind == KIND_TEXT) 0 else scanMode, checkReader = checkReader,
        ))
        SourceWorker.start(context, sid, online = useAi)
        return sid
    }

    private fun saveImage(context: Context, uri: Uri, out: File) {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        var bmp: Bitmap = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Unreadable image")
        val rot = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
    }

    suspend fun delete(context: Context, source: Source) {
        SourceWorker.stop(context, source.id)
        WerdApp.from(context).db.dao().deleteSource(source.id)
        if (source.path.isNotBlank()) File(source.path).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    }

    /** True when the original pages are kept, so they can be shown and read again. */
    fun hasPages(source: Source) = source.kind == KIND_PDF || source.kind == KIND_IMAGES || source.kind == KIND_TEXT

    fun images(page: List<ByteArray>) = page.map { AiImage(it) }
}
