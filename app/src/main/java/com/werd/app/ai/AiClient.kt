package com.werd.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** An image sent to the model (JPEG bytes). */
class AiImage(val jpeg: ByteArray)

class AiException(message: String, val kind: Kind = Kind.OTHER) : IOException(message) {
    enum class Kind { NO_KEY, BAD_KEY, QUOTA, BLOCKED, NETWORK, OTHER }
}

data class AiReply(val text: String, val truncated: Boolean)

/** Minimal client for the two supported providers. The user brings their own API key. */
interface AiClient {
    val provider: String
    val model: String
    suspend fun generate(prompt: String, images: List<AiImage> = emptyList(), json: Boolean = false, maxTokens: Int = 16_000): AiReply
    suspend fun listModels(): List<String>

    companion object {
        const val GEMINI = "gemini"
        const val CLAUDE = "claude"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"

        fun create(provider: String, key: String, model: String): AiClient {
            if (key.isBlank()) throw AiException("No API key", AiException.Kind.NO_KEY)
            return if (provider == CLAUDE) ClaudeClient(key.trim(), model.ifBlank { DEFAULT_CLAUDE_MODEL })
            else GeminiClient(key.trim(), model.ifBlank { DEFAULT_GEMINI_MODEL })
        }
    }
}

// ------------------------------------------------------------------------------------------ HTTP

internal object Http {
    var timeoutMs = 180_000
    /** First retry wait; doubles each attempt (tests shorten it). */
    var retryBaseMs = 4_000L

    /** POST/GET JSON with retries on rate limits and transient server errors. */
    suspend fun call(url: String, headers: Map<String, String>, body: JSONObject?, describe: (Int, String) -> AiException): JSONObject =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                attempt++
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000; readTimeout = timeoutMs
                    requestMethod = if (body == null) "GET" else "POST"
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (body != null) { doOutput = true; setRequestProperty("content-type", "application/json") }
                }
                try {
                    if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    if (code in 200..299) return@withContext JSONObject(text)
                    val retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 529
                    if (retryable && attempt < 5) {
                        val wait = conn.getHeaderField("retry-after")?.toLongOrNull()?.times(1000) ?: (retryBaseMs shl (attempt - 1))
                        delay(wait.coerceAtMost(60_000))
                        continue
                    }
                    throw describe(code, text)
                } catch (e: AiException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt < 3) { delay(retryBaseMs * attempt); continue }
                    throw AiException(e.message ?: "Network error", AiException.Kind.NETWORK)
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    fun errorMessage(text: String): String = runCatching {
        val o = JSONObject(text)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(300)
    }.getOrDefault(text.take(300))

    fun kindFor(code: Int): AiException.Kind = when (code) {
        400, 401, 403 -> AiException.Kind.BAD_KEY
        429 -> AiException.Kind.QUOTA
        else -> AiException.Kind.OTHER
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

// ------------------------------------------------------------------------------------------ Gemini

internal class GeminiClient(
    private val key: String, override val model: String,
    private val base: String = "https://generativelanguage.googleapis.com/v1beta",
) : AiClient {
    override val provider = AiClient.GEMINI

    override suspend fun generate(prompt: String, images: List<AiImage>, json: Boolean, maxTokens: Int): AiReply {
        val parts = JSONArray()
        images.forEach { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Http.b64(it.jpeg)))) }
        parts.put(JSONObject().put("text", prompt))
        val config = JSONObject().put("temperature", 0).put("maxOutputTokens", maxTokens)
        if (json) config.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
        val res = Http.call("$base/models/$model:generateContent", mapOf("x-goog-api-key" to key), body) { code, text ->
            AiException("Gemini ($code): ${Http.errorMessage(text)}", Http.kindFor(code))
        }
        res.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw AiException("Gemini blocked the request ($it)", AiException.Kind.BLOCKED)
        }
        val cand = res.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("Gemini returned no answer", AiException.Kind.OTHER)
        val finish = cand.optString("finishReason")
        val out = StringBuilder()
        cand.optJSONObject("content")?.optJSONArray("parts")?.let { ps ->
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                if (p.optBoolean("thought")) continue
                out.append(p.optString("text"))
            }
        }
        if (out.isEmpty() && (finish == "RECITATION" || finish == "SAFETY" || finish == "PROHIBITED_CONTENT"))
            throw AiException("Gemini stopped ($finish). Try again, or switch to Claude in Settings.", AiException.Kind.BLOCKED)
        return AiReply(out.toString(), finish == "MAX_TOKENS" || finish == "RECITATION")
    }

    override suspend fun listModels(): List<String> {
        val res = Http.call("$base/models?pageSize=200", mapOf("x-goog-api-key" to key), null) { code, text ->
            AiException("Gemini ($code): ${Http.errorMessage(text)}", Http.kindFor(code))
        }
        val arr = res.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } == true }
            .map { it.optString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") && !Regex("image|tts|live|embedding|audio|transcribe|banana|omni").containsMatchIn(it) }
            .sortedWith(compareBy<String>({ !it.contains("flash") }, { it.contains("lite") }).thenByDescending { it })
    }
}

// ------------------------------------------------------------------------------------------ Claude

internal class ClaudeClient(
    private val key: String, override val model: String,
    private val base: String = "https://api.anthropic.com",
) : AiClient {
    override val provider = AiClient.CLAUDE
    private val headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

    override suspend fun generate(prompt: String, images: List<AiImage>, json: Boolean, maxTokens: Int): AiReply {
        val content = JSONArray()
        images.forEach {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", Http.b64(it.jpeg))))
        }
        content.put(JSONObject().put("type", "text").put("text",
            if (json) "$prompt\n\nReply with the JSON object only — no code fences, no commentary." else prompt))
        val body = JSONObject()
            .put("model", model).put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val res = Http.call("$base/v1/messages", headers, body) { code, text ->
            AiException("Claude ($code): ${Http.errorMessage(text)}", Http.kindFor(code))
        }
        val out = StringBuilder()
        res.optJSONArray("content")?.let { c ->
            for (i in 0 until c.length()) c.getJSONObject(i).takeIf { it.optString("type") == "text" }?.let { out.append(it.optString("text")) }
        }
        val stop = res.optString("stop_reason")
        if (out.isEmpty() && stop == "refusal") throw AiException("Claude declined this page", AiException.Kind.BLOCKED)
        return AiReply(out.toString(), stop == "max_tokens")
    }

    override suspend fun listModels(): List<String> {
        val res = Http.call("$base/v1/models?limit=100", headers, null) { code, text ->
            AiException("Claude ($code): ${Http.errorMessage(text)}", Http.kindFor(code))
        }
        val arr = res.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.startsWith("claude") }
    }
}

// ------------------------------------------------------------------------------------------ JSON helper

object AiJson {
    /** Pulls the first JSON object out of a model reply (tolerates code fences and chatter). */
    fun obj(text: String): JSONObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(t) }
        val s = t.indexOf('{'); val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return runCatching { JSONObject(t.substring(s, e + 1)) }.getOrNull() ?: repairTruncated(t.substring(s))
    }

    /** A reply cut off by the token limit: keep the complete items before the cut. */
    fun repairTruncated(t: String): JSONObject? {
        val itemsAt = t.indexOf("\"items\"")
        if (itemsAt < 0) return null
        var depth = 0; var inStr = false; var esc = false; var lastComplete = -1
        val arrStart = t.indexOf('[', itemsAt).takeIf { it >= 0 } ?: return null
        for (i in arrStart until t.length) {
            val c = t[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) lastComplete = i }
            }
        }
        if (lastComplete < 0) return null
        return runCatching { JSONObject("{\"items\":" + t.substring(arrStart, lastComplete + 1) + "]}") }.getOrNull()
    }
}
