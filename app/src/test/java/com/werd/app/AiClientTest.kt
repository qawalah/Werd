package com.werd.app

import com.werd.app.ai.AiException
import com.werd.app.ai.AiImage
import com.werd.app.ai.AiJson
import com.werd.app.ai.AiTasks
import com.werd.app.ai.ClaudeClient
import com.werd.app.ai.GeminiClient
import com.werd.app.ai.Http
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the exact requests sent to Gemini / Claude and how replies are read, against a local mock server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiClientTest {
    private lateinit var server: MockWebServer

    @Before fun up() { server = MockWebServer(); server.start(); Http.retryBaseMs = 5 }
    @After fun down() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).addHeader("content-type", "application/json")

    @Test fun geminiRequestAndReply() = runBlocking {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"pondering","thought":true},{"text":"{\"items\":[]}"}]},"finishReason":"STOP"}]}"""))
        val c = GeminiClient("KEY", "gemini-test", server.url("/v1beta").toString().trimEnd('/'))
        val r = c.generate("PROMPT", listOf(AiImage(byteArrayOf(1, 2, 3))), json = true, maxTokens = 100)
        assertEquals("{\"items\":[]}", r.text)            // thought part skipped
        assertFalse(r.truncated)
        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:generateContent", req.path)
        assertEquals("KEY", req.getHeader("x-goog-api-key"))
        val body = JSONObject(req.body.readUtf8())
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inline_data").getString("mime_type"))
        assertEquals("AQID", parts.getJSONObject(0).getJSONObject("inline_data").getString("data"))
        assertEquals("PROMPT", parts.getJSONObject(1).getString("text"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
        assertEquals(100, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun geminiRetriesRateLimitThenSucceeds() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Resource exhausted"}}""", 429))
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"MAX_TOKENS"}]}"""))
        val r = GeminiClient("K", "m", server.url("/v1beta").toString().trimEnd('/')).generate("hi")
        assertEquals("OK", r.text); assertTrue(r.truncated); assertEquals(2, server.requestCount)
    }

    @Test fun geminiBadKeyIsReported() = runBlocking {
        server.enqueue(json("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}""", 400))
        try { GeminiClient("K", "m", server.url("/v1beta").toString().trimEnd('/')).generate("hi"); fail() }
        catch (e: AiException) { assertEquals(AiException.Kind.BAD_KEY, e.kind); assertTrue(e.message!!.contains("API key not valid")) }
    }

    @Test fun geminiRecitationStopIsBlocked() = runBlocking {
        server.enqueue(json("""{"candidates":[{"finishReason":"RECITATION"}]}"""))
        try { GeminiClient("K", "m", server.url("/v1beta").toString().trimEnd('/')).generate("hi"); fail() }
        catch (e: AiException) { assertEquals(AiException.Kind.BLOCKED, e.kind) }
    }

    @Test fun geminiModelList() = runBlocking {
        server.enqueue(json("""{"models":[
            {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-4-argon","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-9","supportedGenerationMethods":["embedContent"]}]}"""))
        val list = GeminiClient("K", "m", server.url("/v1beta").toString().trimEnd('/')).listModels()
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon"), list)
    }

    @Test fun claudeRequestAndReply() = runBlocking {
        server.enqueue(json("""{"type":"message","content":[{"type":"text","text":"{\"items\":[{\"text\":\"سبحان الله\",\"count\":33}"}],"stop_reason":"max_tokens"}"""))
        val c = ClaudeClient("CK", "claude-sonnet-5-5", server.url("/").toString().trimEnd('/'))
        val r = c.generate("PROMPT", listOf(AiImage(byteArrayOf(9))), json = true, maxTokens = 50)
        assertTrue(r.truncated)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("CK", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals(50, body.getInt("max_tokens"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("base64", content.getJSONObject(0).getJSONObject("source").getString("type"))
        assertEquals("image/jpeg", content.getJSONObject(0).getJSONObject("source").getString("media_type"))
        assertTrue(content.getJSONObject(1).getString("text").startsWith("PROMPT"))
        // the cut-off reply still yields its complete item
        val items = AiTasks.parseItems(r.text, 1, 1)
        assertEquals(1, items.size); assertEquals(33, items[0].count)
    }

    @Test fun jsonTolerance() {
        assertNotNull(AiJson.obj("```json\n{\"items\":[]}\n```"))
        assertNotNull(AiJson.obj("Here you go:\n{\"items\":[]}\nHope it helps"))
        val cut = AiJson.obj("{\"items\":[{\"text\":\"أ ب ت\",\"count\":3},{\"text\":\"ث ج") ?: error("null")
        assertEquals(1, cut.getJSONArray("items").length())
    }

    @Test fun itemsAreNormalised() {
        val items = AiTasks.parseItems("""{"items":[
            {"title":"","text":"أستغفر الله","count":0,"occasion":"Morning","page":99},
            {"text":"سبحان الله وبحمده","count":100,"occasion":"after_prayer","page":3,"occasion_text":"دبر كل صلاة"},
            {"text":"؟","count":1}]}""", 2, 5)
        assertEquals(2, items.size)
        assertEquals(1, items[0].count); assertEquals("morning", items[0].occasion); assertEquals(5, items[0].page)
        assertEquals("أستغفر الله", items[0].title)
        assertEquals("after_prayer", items[1].occasion); assertEquals("دبر كل صلاة", items[1].occasionText)
        assertEquals(AiTasks.dedupeKey("سُبْحَانَ اللهِ"), AiTasks.dedupeKey("سبحان الله"))
    }
}
