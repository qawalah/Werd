package com.werd.app.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A natural Arabic voice that runs on the phone (Piper "Kareem", run by sherpa-onnx). No internet once downloaded
 * (about 65 MB, once). Reads diacritised text best — which awrad usually are.
 */
class PiperVoice(private val context: Context) {
    companion object {
        const val LOW = "low"
        const val MEDIUM = "medium"
        private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        fun folderName(model: String) = "vits-piper-ar_JO-kareem-$model"
        @Volatile private var engine: Pair<String, OfflineTts>? = null
        private val lock = Mutex()
    }

    private fun root() = File(context.filesDir, "piper")
    private fun dir(model: String) = File(root(), folderName(model))
    fun installed(model: String): Boolean = File(dir(model), "ar_JO-kareem-$model.onnx").exists() && File(dir(model), "tokens.txt").exists()

    fun sizeOnDisk(): Long = root().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun remove(model: String) {
        engine?.let { if (it.first == model) { runCatching { it.second.release() }; engine = null } }
        dir(model).deleteRecursively()
    }

    /** Downloads and unpacks the voice. [progress] gets 0..1 (or -1 while unpacking). */
    suspend fun download(model: String, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "piper-$model.tar.bz2")
        val c = (URL(BASE + folderName(model) + ".tar.bz2").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) throw VoiceException("Download failed: HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: 67_000_000L
            var done = 0L
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress((done.toFloat() / total).coerceAtMost(0.99f))
                    }
                }
            }
        } finally { c.disconnect() }
        progress(-1f)
        val target = root().apply { mkdirs() }
        val staging = File(target, ".unpack").apply { deleteRecursively(); mkdirs() }
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(tmp.inputStream(), 256 * 1024))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val f = File(staging, e.name)
                if (!f.canonicalPath.startsWith(staging.canonicalPath)) continue   // no escaping the folder
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { tar.copyTo(it) }
            }
        }
        tmp.delete()
        dir(model).deleteRecursively()
        File(staging, folderName(model)).renameTo(dir(model))
        staging.deleteRecursively()
        if (!installed(model)) throw VoiceException("The voice file looks incomplete; try again")
        progress(1f)
    }

    /** Speaks [text] into WAV bytes. The engine is loaded once (a second or two) and then kept. */
    suspend fun speak(model: String, text: String): ByteArray = withContext(Dispatchers.Default) {
        if (!installed(model)) throw VoiceException("Download the on-phone voice first (Settings → Voice)")
        lock.withLock {
            val tts = engine?.takeIf { it.first == model }?.second ?: run {
                engine?.second?.let { runCatching { it.release() } }
                val d = dir(model)
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(d, "ar_JO-kareem-$model.onnx").path,
                            tokens = File(d, "tokens.txt").path,
                            dataDir = File(d, "espeak-ng-data").path,
                        ),
                        numThreads = 2,
                    ),
                )
                OfflineTts(null, cfg).also { engine = model to it }
            }
            val audio = tts.generate(text, 0, 1.0f)
            Wav.fromFloats(audio.samples, audio.sampleRate)
        }
    }
}
