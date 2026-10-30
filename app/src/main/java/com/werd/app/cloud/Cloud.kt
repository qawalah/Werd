package com.werd.app.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.werd.app.BuildConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What the phone remembers about its server account and family. */
data class CloudState(
    /** Overrides the built-in server address (for testing a server); "" = use [BuildConfig.SERVER_URL]. */
    val serverOverride: String = "",
    val userId: String = "",
    val token: String = "",
    val displayName: String = "",
    val familyId: String = "",
    val familyName: String = "",
    val inviteCode: String = "",
    /** The user agreed to show today's progress to their family. */
    val shareProgress: Boolean = true,
    /** Inbox ids already added or dismissed here (kept until the server forgets them). */
    val handledInbox: Set<String> = emptySet(),
    /** Other addresses of the same server — typed by the user (Tailscale…) or learned from the server — comma separated. */
    val altServers: String = "",
    /** The address that answered last; tried first. */
    val activeServer: String = "",
    /** The account has a password, so it can be signed in to from another phone (server 1.8+). */
    val hasPassword: Boolean = false,
) {
    /** The home address. "192.168.1.20:8080" is accepted as http://192.168.1.20:8080. */
    val serverUrl: String get() = normalizeUrl(serverOverride.ifBlank { BuildConfig.SERVER_URL })
    val alternates: List<String> get() = altServers.split(',', ' ', '\n').map { normalizeUrl(it) }.filter { it.startsWith("http") && it != serverUrl }.distinct()
    /** Every address to try, the one that worked last first. */
    val candidates: List<String> get() = (listOf(normalizeUrl(activeServer), serverUrl) + alternates).filter { it.startsWith("http") }.distinct()
    /** The address in use (the last one that answered, else the home one). */
    val currentUrl: String get() = candidates.firstOrNull().orEmpty()
    /** Plain HTTP: fine on a home network or Tailscale, not over the open internet. */
    val insecure: Boolean get() = currentUrl.startsWith("http://") && !isPrivate(currentUrl)
    val configured: Boolean get() = serverUrl.startsWith("http")
    val signedIn: Boolean get() = token.isNotBlank()
    val inFamily: Boolean get() = familyId.isNotBlank()
}

fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/').let {
    if (it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://")) "http://$it" else it
}

/** Home-network and Tailscale addresses (traffic there doesn't cross the open internet; Tailscale encrypts it anyway). */
fun isPrivate(url: String): Boolean {
    val host = runCatching { URL(url).host }.getOrDefault("")
    val p = host.split('.').mapNotNull { it.toIntOrNull() }
    return host.endsWith(".ts.net") || host.endsWith(".local") || host == "localhost" || (p.size == 4 && (
        p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)))
}

fun isTailscale(url: String): Boolean {
    val host = runCatching { URL(url).host }.getOrDefault("")
    val p = host.split('.').mapNotNull { it.toIntOrNull() }
    return host.endsWith(".ts.net") || (p.size == 4 && p[0] == 100 && p[1] in 64..127)
}

class CloudStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("werd_cloud", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<CloudState> = _flow.asStateFlow()
    val value: CloudState get() = _flow.value

    private fun read() = CloudState(
        serverOverride = prefs.getString("server", "") ?: "",
        userId = prefs.getString("userId", "") ?: "",
        token = prefs.getString("token", "") ?: "",
        displayName = prefs.getString("displayName", "") ?: "",
        familyId = prefs.getString("familyId", "") ?: "",
        familyName = prefs.getString("familyName", "") ?: "",
        inviteCode = prefs.getString("inviteCode", "") ?: "",
        shareProgress = prefs.getBoolean("shareProgress", true),
        handledInbox = prefs.getStringSet("handledInbox", emptySet()) ?: emptySet(),
        altServers = prefs.getString("altServers", "") ?: "",
        activeServer = prefs.getString("activeServer", "") ?: "",
        hasPassword = prefs.getBoolean("hasPassword", false),
    )

    @Synchronized
    fun update(block: (CloudState) -> CloudState) {
        val n = block(_flow.value)
        prefs.edit {
            putString("server", n.serverOverride); putString("userId", n.userId); putString("token", n.token)
            putString("displayName", n.displayName); putString("familyId", n.familyId); putString("familyName", n.familyName)
            putString("inviteCode", n.inviteCode); putBoolean("shareProgress", n.shareProgress)
            putStringSet("handledInbox", n.handledInbox)
            putString("altServers", n.altServers); putString("activeServer", n.activeServer)
            putBoolean("hasPassword", n.hasPassword)
        }
        _flow.value = n
    }
}

class CloudException(val kind: Kind, val status: Int = 0, message: String = "", val body: JSONObject? = null,
                     /** NETWORK: each address tried, with why it failed (see [WerdApi.reason]). */
                     val tried: List<Pair<String, String>> = emptyList()) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, NETWORK, AUTH, FORBIDDEN, NOT_FOUND, CONFLICT, INVALID, TOO_LARGE, RATE_LIMIT, SERVER }
    /** The server's error code ("name_taken", "wrong_password"…), "" when none. */
    val code: String get() = body?.optString("code").orEmpty()
}

/** A sign-in's result. [canReset]: signed in with a one-time code, so a new password can be set without the old one. */
data class SignedIn(val userId: String, val token: String, val displayName: String, val canReset: Boolean = false)

data class AccountInfo(val userId: String, val displayName: String, val hasPassword: Boolean, val canReset: Boolean, val phones: Int)

// ---------------------------------------------------------------- wire types

data class LibraryBook(
    val id: String, val title: String, val author: String, val items: Int, val checked: Int,
    val downloads: Int, val publisher: String, val publishedAt: Long,
    /** "public", or "family" (only this family sees it). */
    val visibility: String = "public",
    /** "" (not stated), general, hanafi, maliki, shafii, hanbali, jafari, other. */
    val madhhab: String = "",
)

val MADHHABS = listOf("general", "hanafi", "maliki", "shafii", "hanbali", "jafari", "other")

data class LibraryPage(val total: Int, val books: List<LibraryBook>)

/** A single werd shared to the server's library (public, or only the family). */
data class SharedWerd(
    val id: String, val title: String, val note: String, val lines: List<Pair<String, Int>>, val lineCount: Int,
    /** The werd's settings as shared (group, days, colour, reminders) — JSON, "" when shared without them. */
    val settings: String, val hasSettings: Boolean, val textOnly: Boolean,
    /** "public" or "family". */
    val visibility: String, val publisher: String, val publishedAt: Long, val downloads: Int, val mine: Boolean,
)

data class SharedWerdPage(val total: Int, val werds: List<SharedWerd>)

data class Family(val familyId: String, val name: String, val inviteCode: String, val members: List<Member> = emptyList(),
                  /** "parent" or "child". */
                  val myRole: String = "") {
    val iAmParent get() = myRole == "parent"
}

data class WerdProgress(val title: String, val done: Int, val total: Int, val completed: Boolean)

data class Progress(
    val day: String, val updatedAt: Long, val streak: Int, val due: Int, val done: Int, val werds: List<WerdProgress>,
)

data class Member(val userId: String, val displayName: String, val isMe: Boolean, val sharing: Boolean, val progress: Progress?,
                  val role: String = "child", val seenAt: Long = 0) {
    val isParent get() = role == "parent"
}

data class SentWerd(val id: String, val fromName: String, val title: String, val note: String,
                    val lines: List<Pair<String, Int>>, val sentAt: Long,
                    /** The werd's settings as JSON ("" when sent without). */
                    val settings: String = "",
                    /** A parent put it straight on this (child's) list. */
                    val autoAdd: Boolean = false)

/**
 * HTTP client for docs/server-api.md.
 * Takes every known address of the server (home, Tailscale…) and uses the first that answers; [onWorking] is told which,
 * so it is tried first next time. An address typed with the wrong scheme (https for a plain-http server, or the reverse)
 * is retried with the other one.
 */
class WerdApi(bases: List<String>, private val token: String = "", private val onWorking: (String) -> Unit = {}) {
    constructor(baseUrl: String, token: String = "") : this(listOf(baseUrl), token)

    companion object {
        /** Tests shorten the wait between retries. */
        @Volatile var retryBaseMs = 1_500L
        /** Short, so an unreachable home address (when away) quickly gives way to the Tailscale one. */
        @Volatile var connectTimeoutMs = 6_000
        @Volatile var readTimeoutMs = 60_000
        /** A guessed scheme (https tried on a plain-http server, or the reverse) can stall in the handshake: give up sooner. */
        @Volatile var guessTimeoutMs = 8_000

        /** Short code for why an address could not be reached; Settings shows it in words. */
        fun reason(e: Throwable): String = when {
            e is java.net.SocketTimeoutException -> "timeout"
            e is java.net.UnknownHostException -> "unknown_host"
            e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "refused"
            e is NotWerd -> "not_werd"
            e.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) -> "cleartext_blocked"
            e is javax.net.ssl.SSLException -> "tls"
            else -> "other:" + (e.message ?: e.javaClass.simpleName).take(120)
        }
    }

    /** Something answered, but not a Werd server (a router page, the NAS's own web page on that port…). */
    private class NotWerd(msg: String) : IOException(msg)

    private val primary: List<String> = bases.map { normalizeUrl(it) }.filter { it.startsWith("http") }.distinct()
    private val flipped: List<String> = primary
        .map { if (it.startsWith("https://")) "http://" + it.removePrefix("https://") else "https://" + it.removePrefix("http://") }
        .filter { it !in primary }
    private val tries: List<String> = primary + flipped

    private suspend fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject =
        exchange(method, path, body, auth) { code, bytes, _ ->
            val text = bytes.toString(Charsets.UTF_8)
            runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrElse { throw NotWerd("HTTP $code, not JSON") }
        }

    /** Spoken audio for [text] from the server's voice library (made once, then served from the server's store). */
    suspend fun speech(text: String, voice: String): Pair<ByteArray, String> =
        exchange("POST", "/v1/tts", JSONObject().put("text", text).put("voice", voice), true) { _, bytes, type ->
            if (type?.startsWith("audio/") != true) throw NotWerd("not audio")
            bytes to (if (type.contains("mpeg")) "mp3" else "wav")
        }

    /** Adds a clip made on this phone to the server's sound library under a shared voice. */
    suspend fun shareClip(voice: String, name: String, text: String, audio: ByteArray, ext: String) {
        call("POST", "/v1/tts/clips", JSONObject().put("voice", voice).put("name", name).put("text", text).put("ext", ext)
            .put("audio", android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)))
    }

    /** The voices the server offers: (enabled, default, [id, name, detail]). Shared voices have detail "shared:<clips>". */
    suspend fun voices(): Triple<Boolean, String, List<Triple<String, String, String>>> {
        val o = call("GET", "/v1/tts/voices")
        val a = o.optJSONArray("voices") ?: JSONArray()
        return Triple(o.optBoolean("enabled"), o.optString("default"), (0 until a.length()).map { i ->
            val v = a.getJSONObject(i)
            val prov = v.optString("provider")
            Triple(v.optString("id"), v.optString("name"),
                if (prov == "shared") "shared:" + v.optString("detail") else listOf(prov, v.optString("detail")).filter { it.isNotBlank() }.joinToString(" · "))
        })
    }

    /** The page-reading models downloaded on the server: (engine ready, default id, [id, name]). */
    suspend fun ocrModels(): Triple<Boolean, String, List<Pair<String, String>>> {
        val o = call("GET", "/v1/ocr/models")
        val a = o.optJSONArray("models") ?: JSONArray()
        return Triple(o.optBoolean("engine"), o.optString("default"),
            (0 until a.length()).map { i -> a.getJSONObject(i).let { it.optString("id") to it.optString("name") } })
    }

    /** The text of a page photo (JPEG), read by the server's AI model. The first page can take minutes (the model loads). */
    suspend fun ocr(jpeg: ByteArray, model: String, task: String = "", title: String = ""): String =
        exchange("POST", "/v1/ocr", JSONObject().put("model", model).put("task", task).put("title", title)
            .put("image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)), true, readMs = 20 * 60_000) { code, bytes, _ ->
            runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("text") }.getOrElse { throw NotWerd("HTTP $code, not JSON") }
        }

    /** A text answer from the server's model (no image): the proofreading helper's question. */
    suspend fun complete(prompt: String, model: String = ""): String =
        exchange("POST", "/v1/ai/complete", JSONObject().put("prompt", prompt).put("model", model), true, readMs = 6 * 60_000) { code, bytes, _ ->
            runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("text") }.getOrElse { throw NotWerd("HTTP $code, not JSON") }
        }

    /** Stops this phone's job [task] on the server (every page of it being read or waiting). */
    suspend fun cancelTask(task: String) { call("DELETE", "/v1/tasks/${enc(task)}") }

    private suspend fun <T> exchange(method: String, path: String, body: JSONObject?, auth: Boolean, readMs: Int? = null,
                                     parse: (Int, ByteArray, String?) -> T): T =
        withContext(Dispatchers.IO) {
            if (tries.isEmpty()) throw CloudException(CloudException.Kind.NOT_CONFIGURED)
            val failed = LinkedHashMap<String, String>()
            for (round in 1..2) {
                for (base in if (round == 1) tries else primary) {      // the second round is for a brief network hiccup
                    try {
                        return@withContext request(base, method, path, body, auth, if (base in flipped) guessTimeoutMs else readMs ?: readTimeoutMs, parse)
                            .also { onWorking(base) }
                    } catch (e: CloudException) {
                        onWorking(base)          // the server answered (with an error): this address works
                        throw e
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()   // cancelled: the socket was closed on purpose
                        // a slow job that timed out was received: don't send it again to another address
                        if (readMs != null && e is java.net.SocketTimeoutException && base !in flipped) throw e
                        failed[base] = reason(e)
                    }
                }
                if (round == 1) delay(retryBaseMs)
            }
            throw CloudException(CloudException.Kind.NETWORK, 0, failed.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                tried = failed.toList())
        }

    /** One address; retries 429/502-504 itself. Throws IOException when the address can't be reached. */
    private suspend fun <T> request(base: String, method: String, path: String, body: JSONObject?, auth: Boolean, readMs: Int,
                                    parse: (Int, ByteArray, String?) -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs; readTimeout = readMs
                requestMethod = method
                setRequestProperty("accept", "application/json")
                if (auth && token.isNotBlank()) setRequestProperty("authorization", "Bearer $token")
                if (body != null) { doOutput = true; setRequestProperty("content-type", "application/json; charset=utf-8") }
            }
            try {
                // a cancelled coroutine closes the connection, so the server sees the phone gave up and stops the work
                val (code, raw) = coroutineScope {
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { conn.disconnect() } }
                    try {
                        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        val c = conn.responseCode
                        c to ((if (c in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
                    } finally { watcher.cancel() }
                }
                if (code in 200..299) return parse(code, raw, conn.contentType)
                val text = raw.toString(Charsets.UTF_8)
                val err = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                val msg = err?.optString("message").orEmpty()
                if ((code == 429 || code in 502..504) && attempt < 4) {
                    val wait = conn.getHeaderField("retry-after")?.toLongOrNull()?.times(1000) ?: (retryBaseMs shl (attempt - 1))
                    delay(wait.coerceAtMost(30_000)); continue
                }
                // every Werd error carries {"error": {...}}; anything else came from some other program on that address
                if (err == null) throw NotWerd("HTTP $code")
                throw CloudException(when (code) {
                    401 -> CloudException.Kind.AUTH
                    403 -> CloudException.Kind.FORBIDDEN
                    404 -> CloudException.Kind.NOT_FOUND
                    409 -> CloudException.Kind.CONFLICT
                    400, 422, 424 -> CloudException.Kind.INVALID
                    413 -> CloudException.Kind.TOO_LARGE
                    429 -> CloudException.Kind.RATE_LIMIT
                    else -> CloudException.Kind.SERVER
                }, code, msg, err)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Checks the address really is a Werd server; also lists the server's other addresses (LAN, Tailscale). */
    suspend fun info(): JSONObject = call("GET", "/v1/info", auth = false).also {
        if (it.optString("name") != "werd-server") throw CloudException(CloudException.Kind.NOT_FOUND, 0, "not a Werd server")
    }

    /** The other addresses the server knows it has (`addresses` in /v1/info), e.g. its Tailscale one. */
    fun addresses(info: JSONObject): List<Pair<String, String>> {
        val a = info.optJSONArray("addresses") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { normalizeUrl(it.optString("url")) to it.optString("kind", "lan") }.filter { it.first.startsWith("http") }
    }

    // ---- accounts
    suspend fun register(displayName: String, password: String = ""): Pair<String, String> =
        call("POST", "/v1/accounts", JSONObject().put("displayName", displayName).apply { if (password.isNotEmpty()) put("password", password) }, auth = false)
            .let { it.getString("userId") to it.getString("token") }

    /** Signs in with a name and password, or with a one-time [code] from the server's owner. */
    suspend fun signIn(name: String, password: String, code: String = ""): SignedIn =
        call("POST", "/v1/accounts/signin", if (code.isNotBlank()) JSONObject().put("code", code) else JSONObject().put("name", name).put("password", password), auth = false)
            .let { SignedIn(it.getString("userId"), it.getString("token"), it.optString("displayName"), it.optBoolean("canReset")) }

    suspend fun account(): AccountInfo = call("GET", "/v1/accounts/me").let {
        AccountInfo(it.optString("userId"), it.optString("displayName"), it.optBoolean("hasPassword"), it.optBoolean("canReset"), it.optInt("phones", 1))
    }

    suspend fun setPassword(password: String, current: String = "") {
        call("PUT", "/v1/accounts/me/password", JSONObject().put("password", password).put("current", current))
    }

    /** Signs every phone out of this account; returns this phone's new token. */
    suspend fun signOutAll(): String = call("POST", "/v1/accounts/me/signout-all").getString("token")

    suspend fun signOut() { call("DELETE", "/v1/accounts/me/session") }

    suspend fun rename(displayName: String) { call("PUT", "/v1/accounts/me", JSONObject().put("displayName", displayName)) }

    // ---- shared awrad
    private fun sharedWerd(o: JSONObject): SharedWerd {
        val a = o.optJSONArray("lines") ?: JSONArray()
        return SharedWerd(o.optString("id"), o.optString("title"), o.optString("note"),
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("text") to it.optInt("count", 1).coerceAtLeast(1) },
            o.optInt("lineCount", a.length()), o.optJSONObject("werd")?.toString().orEmpty(), o.optBoolean("hasSettings"),
            o.optBoolean("textOnly"), o.optString("visibility", "public"), o.optString("publisher"), o.optLong("publishedAt"),
            o.optInt("downloads"), o.optBoolean("mine"))
    }

    /** [scope]: "" everything I can see, "public", "family" or "mine". */
    suspend fun searchWerds(q: String, scope: String = "", offset: Int = 0, limit: Int = 30): SharedWerdPage {
        val o = call("GET", "/v1/library/werds?q=${enc(q)}&scope=${enc(scope)}&offset=$offset&limit=$limit")
        val arr = o.optJSONArray("werds") ?: JSONArray()
        return SharedWerdPage(o.optInt("total", arr.length()), (0 until arr.length()).map { sharedWerd(arr.getJSONObject(it)) })
    }

    /** One shared werd with all its lines; [adding] counts it as added. */
    suspend fun sharedWerd(id: String, adding: Boolean = false): SharedWerd =
        sharedWerd(call("GET", "/v1/library/werds/${enc(id)}" + if (adding) "?add=1" else ""))

    suspend fun shareWerd(body: JSONObject): String = call("POST", "/v1/library/werds", body).getString("id")
    suspend fun moveSharedWerd(id: String, visibility: String) { call("PUT", "/v1/library/werds/${enc(id)}", JSONObject().put("visibility", visibility)) }
    suspend fun withdrawSharedWerd(id: String) { call("DELETE", "/v1/library/werds/${enc(id)}") }

    // ---- library
    suspend fun searchBooks(q: String, offset: Int = 0, limit: Int = 30, madhhab: String = ""): LibraryPage {
        // signed in: the family's own books are included
        val o = call("GET", "/v1/library/books?q=${enc(q)}&offset=$offset&limit=$limit&madhhab=${enc(madhhab)}")
        val arr = o.optJSONArray("books") ?: JSONArray()
        return LibraryPage(o.optInt("total", arr.length()), (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            LibraryBook(b.getString("id"), b.optString("title"), b.optString("author"), b.optInt("items"), b.optInt("checked"),
                b.optInt("downloads"), b.optString("publisher"), b.optLong("publishedAt"),
                b.optString("visibility", "public"), b.optString("madhhab"))
        })
    }

    /** The first few items of a book, to look at before downloading (not counted as a download). */
    suspend fun previewBook(id: String): JSONObject = call("GET", "/v1/library/books/${enc(id)}?preview=true")

    /** The full .werdbook document (as text, for [com.werd.app.sources.BookFile.parse]). */
    suspend fun downloadBook(id: String): String = call("GET", "/v1/library/books/${enc(id)}").toString()

    suspend fun publishBook(book: JSONObject): String = call("POST", "/v1/library/books", book).getString("id")

    suspend fun reportBook(id: String, reason: String, item: Int? = null) {
        call("POST", "/v1/library/books/${enc(id)}/reports", JSONObject().put("reason", reason).apply { if (item != null) put("item", item) })
    }

    // ---- family
    private fun family(o: JSONObject) = Family(o.getString("familyId"), o.optString("name"), o.optString("inviteCode"),
        (o.optJSONArray("members") ?: JSONArray()).let { a -> (0 until a.length()).map { member(a.getJSONObject(it)) } },
        o.optString("myRole"))

    private fun progress(p: JSONObject) = Progress(p.optString("day"), p.optLong("updatedAt"), p.optInt("streak"), p.optInt("due"), p.optInt("done"),
        (p.optJSONArray("werds") ?: JSONArray()).let { a -> (0 until a.length()).map { k ->
            val w = a.getJSONObject(k); WerdProgress(w.optString("title"), w.optInt("done"), w.optInt("total"), w.optBoolean("completed"))
        } })

    private fun member(o: JSONObject) = Member(o.getString("userId"), o.optString("displayName"), o.optBoolean("isMe"),
        o.optBoolean("sharing", true), o.optJSONObject("progress")?.let { progress(it) },
        o.optString("role", "child"), o.optLong("seenAt"))

    /** A member's daily summaries, newest first (parents; or one's own). */
    suspend fun history(userId: String, days: Int = 30): List<Progress> {
        val a = call("GET", "/v1/families/me/members/${enc(userId)}/history?days=$days").optJSONArray("days") ?: JSONArray()
        return (0 until a.length()).map { progress(a.getJSONObject(it)) }
    }

    suspend fun setRole(userId: String, role: String) { call("PUT", "/v1/families/me/members/${enc(userId)}", JSONObject().put("role", role)) }
    suspend fun removeMember(userId: String) { call("DELETE", "/v1/families/me/members/${enc(userId)}") }

    suspend fun createFamily(name: String): Family = family(call("POST", "/v1/families", JSONObject().put("name", name)))
    suspend fun joinFamily(code: String): Family = family(call("POST", "/v1/families/join", JSONObject().put("inviteCode", code.trim().uppercase())))
    suspend fun myFamily(): Family = family(call("GET", "/v1/families/me"))
    suspend fun leaveFamily() { call("DELETE", "/v1/families/me/membership") }

    suspend fun putProgress(sharing: Boolean, p: Progress?) {
        val o = JSONObject().put("sharing", sharing)
        if (sharing && p != null) {
            o.put("day", p.day).put("streak", p.streak).put("due", p.due).put("done", p.done)
                .put("werds", JSONArray().apply { p.werds.forEach { w ->
                    put(JSONObject().put("title", w.title).put("done", w.done).put("total", w.total).put("completed", w.completed))
                } })
        }
        call("PUT", "/v1/progress", o)
    }

    suspend fun sendWerd(userId: String, title: String, note: String, lines: List<Pair<String, Int>>,
                         settings: JSONObject? = null, autoAdd: Boolean = false): String =
        call("POST", "/v1/families/me/members/${enc(userId)}/werds", JSONObject().put("title", title).put("note", note)
            .put("lines", JSONArray().apply { lines.forEach { (t, n) -> put(JSONObject().put("text", t).put("count", n)) } })
            .apply { if (settings != null) put("werd", settings) }.put("autoAdd", autoAdd)).getString("id")

    suspend fun inbox(): List<SentWerd> {
        val a = call("GET", "/v1/inbox").optJSONArray("werds") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val w = a.getJSONObject(i)
            val ls = w.optJSONArray("lines") ?: JSONArray()
            SentWerd(w.getString("id"), w.optJSONObject("from")?.optString("displayName").orEmpty(), w.optString("title"),
                w.optString("note"), (0 until ls.length()).map { k -> ls.getJSONObject(k).let { it.optString("text") to it.optInt("count", 1) } },
                w.optLong("sentAt"), w.optJSONObject("werd")?.toString().orEmpty(), w.optBoolean("autoAdd"))
        }
    }

    suspend fun ackInbox(id: String) { call("DELETE", "/v1/inbox/${enc(id)}") }
}
