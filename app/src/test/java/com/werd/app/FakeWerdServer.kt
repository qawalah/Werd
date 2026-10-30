package com.werd.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory implementation of docs/server-api.md, used by the app's tests.
 * It is deliberately complete (validation, status codes, privacy rules) so it can serve as the reference for the real server.
 */
class FakeWerdServer : Dispatcher() {
    val server = MockWebServer().apply { dispatcher = this@FakeWerdServer }
    val url: String get() = server.url("").toString().trimEnd('/')

    private val seq = AtomicInteger(1)
    private fun id(prefix: String) = "${prefix}_${seq.getAndIncrement()}"

    class User(val id: String, var name: String, var familyId: String? = null, var progress: JSONObject? = null, var sharing: Boolean = true,
               var role: String = "child", val days: java.util.TreeMap<String, JSONObject> = java.util.TreeMap())
    class Book(val id: String, val doc: JSONObject, val publisher: String, val publishedAt: Long, var downloads: Int = 0,
               var visibility: String = "public", var familyId: String? = null, var madhhab: String = "")
    class FamilyRec(val id: String, val name: String, val code: String)
    class Sent(val id: String, val to: String, val from: User, val body: JSONObject, val sentAt: Long)

    val users = LinkedHashMap<String, User>()            // token → user
    val books = LinkedHashMap<String, Book>()
    val families = LinkedHashMap<String, FamilyRec>()
    val inbox = ArrayList<Sent>()
    val reports = ArrayList<JSONObject>()
    /** Shared awrad: id → (body as posted, publisher user). */
    val werds = LinkedHashMap<String, Pair<JSONObject, User>>()
    /** Voices offered by /v1/tts/voices (empty = no voice on the server). */
    val voices = ArrayList<Triple<String, String, String>>()
    /** What /v1/info advertises as the server's other addresses: url to kind. */
    val addresses = ArrayList<Pair<String, String>>()

    fun start() = also { server.start() }
    fun stop() = server.shutdown()

    private fun json(code: Int, o: Any = JSONObject()) = MockResponse().setResponseCode(code)
        .setHeader("content-type", "application/json; charset=utf-8").setBody(o.toString())
    private fun err(code: Int, c: String, extra: (JSONObject) -> Unit = {}) =
        json(code, JSONObject().put("error", JSONObject().put("code", c).put("message", c).also(extra)))

    private fun bare(s: String) = s.filter { it !in 'ً'..'ٟ' && it != 'ٰ' && it != 'ـ' }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').lowercase()

    private fun summary(b: Book) = JSONObject().put("id", b.id).put("title", b.doc.optString("title")).put("author", b.doc.optString("author"))
        .put("language", b.doc.optString("language", "ar")).put("items", b.doc.getJSONArray("items").length())
        .put("checked", (0 until b.doc.getJSONArray("items").length()).count { b.doc.getJSONArray("items").getJSONObject(it).optBoolean("checked") })
        .put("downloads", b.downloads).put("publisher", b.publisher).put("publishedAt", b.publishedAt)
        .put("visibility", b.visibility).put("madhhab", b.madhhab)

    private fun familyJson(f: FamilyRec, me: User?) = JSONObject().put("familyId", f.id).put("name", f.name).put("inviteCode", f.code).apply {
        if (me != null) put("members", JSONArray().apply {
            users.values.filter { it.familyId == f.id }.sortedBy { it.role == "child" }.forEach { u ->
                put(JSONObject().put("userId", u.id).put("displayName", u.name).put("isMe", u === me).put("sharing", u.sharing)
                    .put("progress", if (u.sharing) u.progress ?: JSONObject.NULL else JSONObject.NULL).put("role", u.role))
            }
        })
        if (me != null) put("myRole", me.role)
    }

    private fun sharedJson(id: String, b: JSONObject, u: User, me: User?, full: Boolean): JSONObject {
        val lines = b.optJSONArray("lines") ?: JSONArray()
        return JSONObject().put("id", id).put("title", b.optString("title")).put("note", b.optString("note"))
            .put("lines", if (full) lines else JSONArray().apply { if (lines.length() > 0) put(lines.get(0)) })
            .put("lineCount", lines.length()).put("hasSettings", b.has("werd")).put("textOnly", b.optBoolean("textOnly"))
            .put("visibility", b.optString("visibility")).put("publisher", u.name).put("publishedAt", 1_700_000_000_000)
            .put("downloads", 0).put("mine", u === me).apply { if (full && b.has("werd")) put("werd", b.getJSONObject("werd")) }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: "/"
        val (p, qs) = path.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val q = qs.split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else runCatching { JSONObject(it) }.getOrElse { return err(400, "bad_json") } }
        val me = request.getHeader("authorization")?.removePrefix("Bearer ")?.let { users[it] }
        fun authed(block: (User) -> MockResponse) = if (me == null) err(401, "unauthorized") else block(me)
        val m = request.method
        val seg = p.trim('/').split("/")

        return when {
            m == "GET" && p == "/v1/info" -> json(200, JSONObject().put("name", "werd-server").put("version", "fake").put("api", 1)
                .put("addresses", JSONArray().apply { addresses.forEach { (u, k) -> put(JSONObject().put("url", u).put("kind", k)) } }))
            m == "POST" && p == "/v1/accounts" -> {
                val token = "tok_" + seq.getAndIncrement()
                val u = User(id("u"), body.optString("displayName").take(40))
                users[token] = u
                json(201, JSONObject().put("userId", u.id).put("token", token))
            }
            m == "PUT" && p == "/v1/accounts/me" -> authed { it.name = body.optString("displayName").take(40); json(200) }
            m == "GET" && p == "/v1/accounts/me" -> authed { json(200, JSONObject().put("userId", it.id).put("displayName", it.name).put("hasPassword", false).put("phones", 1)) }
            m == "GET" && p == "/v1/ocr/models" -> authed { json(200, JSONObject().put("engine", true).put("default", "qari4")
                .put("models", JSONArray().put(JSONObject().put("id", "qari4").put("name", "QARI-OCR 0.4 (4B)")).put(JSONObject().put("id", "my-qari5").put("name", "QARI 0.5")))) }

            // ---- shared awrad
            m == "GET" && p == "/v1/library/werds" -> {
                val term = bare(q["q"].orEmpty().trim())
                val hits = werds.entries.filter { (_, v) -> val (b, u) = v
                    (b.optString("visibility") == "public" || (me != null && b.optString("familyId") == me.familyId) || u === me) &&
                        (term.isEmpty() || bare(b.optString("title")).contains(term)) &&
                        when (q["scope"].orEmpty()) { "public" -> b.optString("visibility") == "public"; "family" -> b.optString("visibility") == "family"
                            "mine" -> u === me; else -> true } }
                json(200, JSONObject().put("total", hits.size).put("werds", JSONArray().apply { hits.forEach { (id, v) -> put(sharedJson(id, v.first, v.second, me, false)) } }))
            }
            m == "GET" && seg.size == 4 && p.startsWith("/v1/library/werds/") -> {
                val (b, u) = werds[seg[3]] ?: return err(404, "not_found")
                if (b.optString("visibility") == "family" && u !== me && (me == null || b.optString("familyId") != me.familyId)) return err(404, "not_found")
                json(200, sharedJson(seg[3], b, u, me, true))
            }
            m == "POST" && p == "/v1/library/werds" -> authed { u ->
                if ((body.optJSONArray("lines")?.length() ?: 0) == 0) return@authed err(422, "lines")
                val vis = if (body.optString("visibility") == "family") "family" else "public"
                if (vis == "family" && u.familyId == null) return@authed err(422, "no_family")
                val id = id("w")
                werds[id] = body.put("visibility", vis).put("familyId", u.familyId ?: "") to u
                json(201, JSONObject().put("id", id))
            }
            m == "PUT" && seg.size == 4 && p.startsWith("/v1/library/werds/") -> authed { u ->
                val (b, owner) = werds[seg[3]] ?: return@authed err(404, "not_found")
                if (owner !== u) return@authed err(404, "not_found")
                b.put("visibility", body.optString("visibility")); json(200)
            }
            m == "DELETE" && seg.size == 4 && p.startsWith("/v1/library/werds/") -> authed { u ->
                val (_, owner) = werds[seg[3]] ?: return@authed err(404, "not_found")
                if (owner !== u) return@authed err(404, "not_found")
                werds.remove(seg[3]); json(200)
            }

            // ---- library
            m == "GET" && p == "/v1/library/books" -> {
                val term = bare(q["q"].orEmpty().trim())
                val mad = q["madhhab"].orEmpty()
                val hits = books.values.filter { term.isEmpty() || bare(it.doc.optString("title")).contains(term) || bare(it.doc.optString("author")).contains(term) }
                    .filter { it.visibility == "public" || (me != null && it.familyId == me.familyId) }
                    .filter { mad.isEmpty() || it.madhhab == mad }
                    .sortedByDescending { it.downloads }
                val off = q["offset"]?.toIntOrNull() ?: 0; val lim = (q["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
                json(200, JSONObject().put("total", hits.size).put("books", JSONArray().apply { hits.drop(off).take(lim).forEach { put(summary(it)) } }))
            }
            m == "GET" && seg.size == 4 && p.startsWith("/v1/library/books/") -> {
                val b = books[seg[3]] ?: return err(404, "not_found")
                if (q["preview"] == "true") {
                    val d = JSONObject(b.doc.toString())
                    val all = d.getJSONArray("items")
                    d.put("items", JSONArray().apply { for (i in 0 until minOf(5, all.length())) put(all.get(i)) })
                    summary(b).keys().forEach { k -> if (k != "items") d.put(k, summary(b).get(k)) }
                    json(200, d.put("id", b.id))
                } else { b.downloads++; json(200, JSONObject(b.doc.toString()).put("id", b.id)) }
            }
            m == "POST" && p == "/v1/library/books" -> authed { u ->
                if (body.optString("format") != "werd-book") return@authed err(422, "not_a_book")
                val items = body.optJSONArray("items") ?: JSONArray()
                if (items.length() == 0 || items.length() > 5000) return@authed err(422, "items")
                if ((0 until items.length()).any { !items.getJSONObject(it).optBoolean("checked") }) return@authed err(422, "not_proofread")
                val key = bare(body.optString("title")) + "|" + (0 until items.length()).map { bare(items.getJSONObject(it).optString("text")) }.sorted().joinToString("|")
                books.values.firstOrNull { b ->
                    val bi = b.doc.getJSONArray("items")
                    bare(b.doc.optString("title")) + "|" + (0 until bi.length()).map { bare(bi.getJSONObject(it).optString("text")) }.sorted().joinToString("|") == key
                }?.let { dup -> return@authed err(409, "duplicate") { it.put("id", dup.id) } }
                val vis = body.optString("visibility", "public").let { if (it == "family") "family" else "public" }
                body.remove("visibility")
                val b = Book(id("b"), body, u.name, System.currentTimeMillis(), visibility = vis,
                    familyId = if (vis == "family") u.familyId else null, madhhab = body.optString("madhhab"))
                books[b.id] = b
                json(201, JSONObject().put("id", b.id))
            }
            m == "POST" && seg.size == 5 && seg[4] == "reports" -> authed {
                if (books[seg[3]] == null) return@authed err(404, "not_found")
                reports += body.put("book", seg[3]); json(201)
            }

            // ---- family
            m == "POST" && p == "/v1/families" -> authed { u ->
                if (u.familyId != null) return@authed err(409, "already_in_family")
                val f = FamilyRec(id("f"), body.optString("name").take(60), "K" + seq.getAndIncrement() + "-" + (1000 + seq.get()))
                families[f.id] = f; u.familyId = f.id; u.role = "parent"
                json(201, familyJson(f, null))
            }
            m == "POST" && p == "/v1/families/join" -> authed { u ->
                val f = families.values.firstOrNull { it.code == body.optString("inviteCode").trim().uppercase() } ?: return@authed err(404, "unknown_code")
                if (u.familyId != null) return@authed err(409, "already_in_family")
                u.familyId = f.id; u.role = "child"
                json(200, familyJson(f, null))
            }
            m == "GET" && p == "/v1/families/me" -> authed { u ->
                val f = u.familyId?.let { families[it] } ?: return@authed err(404, "no_family")
                json(200, familyJson(f, u))
            }
            m == "DELETE" && p == "/v1/families/me/membership" -> authed { u ->
                val fid = u.familyId ?: return@authed err(404, "no_family")
                u.familyId = null
                if (users.values.none { it.familyId == fid }) families.remove(fid)
                json(200)
            }
            m == "PUT" && p == "/v1/progress" -> authed { u ->
                u.sharing = body.optBoolean("sharing", true)
                u.progress = if (u.sharing) JSONObject(body.toString()).apply { remove("sharing"); put("updatedAt", System.currentTimeMillis()) } else null
                u.progress?.let { p -> u.days[p.optString("day")] = p }
                json(200)
            }
            m == "POST" && seg.size == 6 && p.startsWith("/v1/families/me/members/") && seg[5] == "werds" -> authed { u ->
                val to = users.values.firstOrNull { it.id == seg[4] }
                if (to == null || u.familyId == null || to.familyId != u.familyId) return@authed err(404, "not_in_family")
                val lines = body.optJSONArray("lines") ?: JSONArray()
                if (lines.length() == 0 || lines.length() > 500) return@authed err(422, "lines")
                val s = Sent(id("w"), to.id, u, body, System.currentTimeMillis()); inbox += s
                json(201, JSONObject().put("id", s.id))
            }
            m == "GET" && seg.size == 6 && p.startsWith("/v1/families/me/members/") && seg[5] == "history" -> authed { u ->
                val t = users.values.firstOrNull { it.id == seg[4] }
                if (t == null || (t !== u && (u.role != "parent" || t.familyId != u.familyId))) return@authed err(404, "not_in_family")
                json(200, JSONObject().put("days", JSONArray().apply { t.days.descendingMap().values.take(30).forEach { put(it) } }))
            }
            m == "PUT" && seg.size == 5 && p.startsWith("/v1/families/me/members/") -> authed { u ->
                val t = users.values.firstOrNull { it.id == seg[4] && it.familyId == u.familyId } ?: return@authed err(404, "not_in_family")
                if (u.role != "parent") return@authed err(403, "not_parent")
                t.role = body.optString("role"); json(200)
            }
            m == "GET" && p == "/v1/tts/voices" -> authed {
                json(200, JSONObject().put("enabled", voices.isNotEmpty()).put("default", voices.firstOrNull()?.first.orEmpty())
                    .put("voices", JSONArray().apply { voices.forEach { (i, n, d) -> put(JSONObject().put("id", i).put("name", n).put("provider", i.substringBefore(':')).put("detail", d)) } }))
            }
            m == "GET" && p == "/v1/inbox" -> authed { u ->
                json(200, JSONObject().put("werds", JSONArray().apply {
                    inbox.filter { it.to == u.id }.forEach { s ->
                        put(JSONObject().put("id", s.id).put("from", JSONObject().put("userId", s.from.id).put("displayName", s.from.name))
                            .put("title", s.body.optString("title")).put("note", s.body.optString("note"))
                            .put("lines", s.body.optJSONArray("lines")).put("sentAt", s.sentAt)
                            .put("autoAdd", s.body.optBoolean("autoAdd") && s.from.role == "parent" && u.role == "child")
                            .apply { s.body.optJSONObject("werd")?.let { put("werd", it) } })
                    }
                }))
            }
            m == "DELETE" && seg.size == 3 && p.startsWith("/v1/inbox/") -> authed { u ->
                inbox.removeAll { it.id == seg[2] && it.to == u.id }; json(200)
            }
            else -> err(404, "no_route")
        }
    }
}
