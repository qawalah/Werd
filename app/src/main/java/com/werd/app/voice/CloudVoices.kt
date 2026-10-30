package com.werd.app.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A voice service said no; [message] is shown in Settings as is (it usually names the problem: key, quota, voice). */
class VoiceException(message: String) : IOException(message)

/** One voice to choose from. */
data class VoiceChoice(val id: String, val name: String, val detail: String = "")

/** Natural cloud voices, called directly from the phone with the user's own key. Each returns (audio, "wav" | "mp3"). */
object CloudVoices {

    private suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 90_000
                setRequestProperty("content-type", contentType)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (code !in 200..299) throw VoiceException(errorText(code, bytes))
                bytes to c.contentType
            } finally { c.disconnect() }
        }

    private suspend fun get(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw VoiceException(errorText(code, bytes))
            bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun errorText(code: Int, bytes: ByteArray): String {
        val t = bytes.toString(Charsets.UTF_8)
        val msg = runCatching {
            val o = JSONObject(t)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optJSONObject("detail")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull() ?: t.take(200)
        return "HTTP $code${if (msg.isNotBlank()) " — $msg" else ""}"
    }

    // ---------------------------------------------------------------- Gemini

    suspend fun gemini(key: String, model: String, voice: String, style: String, text: String): Pair<ByteArray, String> {
        if (key.isBlank()) throw VoiceException("No Gemini key")
        val part = JSONObject().put("type", "text").put("text", text)
        if (style.isNotBlank()) part.put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))
        val interactions = JSONObject().put("model", model)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(part))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        val headers = mapOf("x-goog-api-key" to key)
        val first = runCatching {
            val (b, _) = post("https://generativelanguage.googleapis.com/v1beta/interactions", headers,
                interactions.toString().toByteArray(), "application/json")
            val steps = JSONObject(b.toString(Charsets.UTF_8)).optJSONArray("steps") ?: JSONArray()
            var data: String? = null
            for (i in 0 until steps.length()) {
                val content = steps.getJSONObject(i).optJSONArray("content") ?: continue
                for (k in 0 until content.length()) content.getJSONObject(k).let { if (it.optString("type") == "audio") data = it.optString("data") }
            }
            data?.takeIf { it.isNotBlank() } ?: throw VoiceException("Gemini: no audio in the answer")
        }
        val b64 = first.getOrElse { e1 ->
            // older models: generateContent with an AUDIO answer
            val gc = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    if (style.isNotBlank()) "$style: $text" else text)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            val r = runCatching {
                post("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent",
                    headers, gc.toString().toByteArray(), "application/json").first
            }.getOrElse { throw (e1 as? VoiceException ?: it) }
            val parts = JSONObject(r.toString(Charsets.UTF_8)).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("inlineData")?.optString("data") }
                .firstOrNull { it.isNotBlank() } ?: throw VoiceException("Gemini: no audio in the answer")
        }
        return Wav.ensure(Base64.decode(b64, Base64.DEFAULT), 24_000) to "wav"
    }

    // ---------------------------------------------------------------- Azure

    suspend fun azure(key: String, region: String, voice: String, text: String): Pair<ByteArray, String> {
        if (key.isBlank()) throw VoiceException("No Azure key")
        val lang = voice.split('-').take(2).joinToString("-").ifBlank { "ar-SA" }
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val ssml = """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="$lang"><voice name="$voice">$esc</voice></speak>"""
        val (b, _) = post("https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
            mapOf("Ocp-Apim-Subscription-Key" to key, "X-Microsoft-OutputFormat" to "audio-24khz-96kbitrate-mono-mp3", "User-Agent" to "werd"),
            ssml.toByteArray(), "application/ssml+xml")
        return b to "mp3"
    }

    suspend fun azureVoices(key: String, region: String): List<VoiceChoice> {
        val a = JSONArray(get("https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list", mapOf("Ocp-Apim-Subscription-Key" to key)))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("Locale").startsWith("ar-") }
            .map { VoiceChoice(it.optString("ShortName"), it.optString("LocalName"), it.optString("LocaleName") + " · " + it.optString("Gender")) }
            .sortedBy { it.id }
    }

    // ---------------------------------------------------------------- ElevenLabs

    suspend fun eleven(key: String, model: String, voice: String, text: String): Pair<ByteArray, String> {
        if (key.isBlank()) throw VoiceException("No ElevenLabs key")
        if (voice.isBlank()) throw VoiceException("Pick an ElevenLabs voice")
        val url = "https://api.elevenlabs.io/v1/text-to-speech/${URLEncoder.encode(voice, "UTF-8")}?output_format=mp3_44100_128"
        val h = mapOf("xi-api-key" to key, "accept" to "audio/mpeg")
        val body = JSONObject().put("text", text).put("model_id", model)
        val b = runCatching { post(url, h, JSONObject(body.toString()).put("language_code", "ar").toString().toByteArray(), "application/json").first }
            .getOrElse { post(url, h, body.toString().toByteArray(), "application/json").first }   // some models don't take language_code
        return b to "mp3"
    }

    suspend fun elevenVoices(key: String): List<VoiceChoice> {
        val a = JSONObject(get("https://api.elevenlabs.io/v1/voices", mapOf("xi-api-key" to key))).optJSONArray("voices") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { v ->
            val l = v.optJSONObject("labels")
            VoiceChoice(v.optString("voice_id"), v.optString("name"),
                listOfNotNull(l?.optString("gender"), l?.optString("accent")).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}

/** WAV helpers: Gemini sends raw 16-bit PCM; Piper gives float samples. */
object Wav {
    fun ensure(b: ByteArray, rate: Int): ByteArray =
        if (b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF") b else wrap(b, rate)

    fun wrap(pcm16: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm16.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm16.size)
        return ByteArrayOutputStream(44 + pcm16.size).apply { write(h.array()); write(pcm16) }.toByteArray()
    }

    fun fromFloats(samples: FloatArray, rate: Int): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        return wrap(bb.array(), rate)
    }
}
