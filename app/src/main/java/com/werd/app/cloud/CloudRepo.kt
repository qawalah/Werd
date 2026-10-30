package com.werd.app.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Dashboard
import com.werd.app.data.InboxWerd
import com.werd.app.data.Werd
import com.werd.app.data.excludes
import com.werd.app.data.WerdItem
import com.werd.app.localized
import com.werd.app.sources.BookFile
import com.werd.app.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Library and family, on top of [WerdApi]. Everything here needs a configured server ([CloudState.configured]). */
class CloudRepo(private val app: WerdApp) {
    val store = CloudStore(app)
    private val dao get() = app.db.dao()

    /** A client that tries every known address of the server (home, Tailscale…) and remembers the one that answered. */
    fun api(token: String = store.value.token) = WerdApi(store.value.candidates, token) { url ->
        if (store.value.activeServer != url) store.update { it.copy(activeServer = url) }
    }

    /** Asks the server for its other addresses (e.g. Tailscale) so the app can switch to them away from home. */
    suspend fun learnAddresses(): JSONObject {
        val api = api()
        val info = api.info()
        val learned = api.addresses(info).map { it.first }
        if (learned.isNotEmpty()) store.update { s ->
            val all = (s.alternates + learned).filter { it != s.serverUrl }.distinct()
            s.copy(altServers = all.joinToString(","))
        }
        return info
    }

    /** Result of checking one address: [url] is the form that answered (the scheme may have been corrected), or [reason] why not. */
    data class Probe(val typed: String, val url: String?, val version: String = "", val reason: String = "")

    /** One address on its own (Settings' per-address check). */
    suspend fun probe(typed: String): Probe {
        var answered: String? = null
        return try {
            val info = WerdApi(listOf(typed)) { answered = it }.info()
            Probe(typed, answered, info.optString("version"))
        } catch (e: CloudException) {
            Probe(typed, null, reason = if (e.kind == CloudException.Kind.NETWORK) e.tried.firstOrNull()?.second ?: "other:" else "not_werd")
        }
    }

    /**
     * Creates the phone's account on first use. With a [password] the name is reserved on the server, so the same person
     * can sign in to it again from another phone (or after reinstalling) instead of becoming a new member.
     */
    suspend fun ensureAccount(displayName: String = store.value.displayName, password: String = ""): WerdApi {
        if (!store.value.configured) throw CloudException(CloudException.Kind.NOT_CONFIGURED)
        if (!store.value.signedIn) {
            val (uid, token) = api("").register(displayName.trim().take(40), password)
            store.update { it.copy(userId = uid, token = token, displayName = displayName.trim().take(40), hasPassword = password.isNotEmpty()) }
        }
        return api()
    }

    // ------------------------------------------------------------------ signing in (server 1.8+)

    /**
     * Signs this phone in to an existing account (name and password, or a one-time code from the server's owner).
     * The phone's current account, if any, is left behind; the family is read again from the server.
     */
    suspend fun signIn(name: String, password: String, code: String = ""): SignedIn {
        if (!store.value.configured) throw CloudException(CloudException.Kind.NOT_CONFIGURED)
        val r = api("").signIn(name.trim(), password, code.trim())
        store.update { it.copy(userId = r.userId, token = r.token, displayName = r.displayName.ifBlank { name.trim() }, hasPassword = !r.canReset || it.hasPassword,
            familyId = "", familyName = "", inviteCode = "") }
        runCatching { refreshAccount() }
        runCatching { refreshFamily() }
        return r
    }

    /** Makes an account with a password (the name must be free on the server). */
    suspend fun createAccount(name: String, password: String) {
        if (store.value.signedIn) store.update { it.copy(userId = "", token = "", familyId = "", familyName = "", inviteCode = "", hasPassword = false) }
        ensureAccount(name, password)
    }

    /** What the server says about this account (older servers don't know: nothing changes). */
    suspend fun refreshAccount(): AccountInfo? {
        if (!store.value.signedIn) return null
        val a = try { api().account() } catch (e: CloudException) { if (e.kind == CloudException.Kind.NOT_FOUND) return null else throw e }
        store.update { it.copy(hasPassword = a.hasPassword, displayName = a.displayName.ifBlank { it.displayName }) }
        return a
    }

    suspend fun setPassword(password: String, current: String = "") {
        authed { it.setPassword(password, current) }
        store.update { it.copy(hasPassword = true) }
    }

    suspend fun signOutOthers() {
        val tok = authed { it.signOutAll() }
        store.update { it.copy(token = tok) }
    }

    /** Signs this phone out (only with a password: otherwise the account could never be reached again). */
    suspend fun signOut() {
        authed { it.signOut() }
        store.update { it.copy(userId = "", token = "", familyId = "", familyName = "", inviteCode = "", hasPassword = false) }
    }

    /** Runs [block] signed in. If the server no longer knows this phone (401), signs up again once — the old family link is gone then. */
    private suspend fun <T> authed(block: suspend (WerdApi) -> T): T = try {
        block(ensureAccount())
    } catch (e: CloudException) {
        if (e.kind != CloudException.Kind.AUTH) throw e
        // an account with a password was signed out (from another phone): don't make a new one behind the user's back
        if (store.value.hasPassword) {
            store.update { it.copy(userId = "", token = "", familyId = "", familyName = "", inviteCode = "") }
            throw e
        }
        store.update { it.copy(userId = "", token = "", familyId = "", familyName = "", inviteCode = "") }
        block(ensureAccount())
    }

    suspend fun setDisplayName(name: String) {
        val n = name.trim().take(40)
        if (store.value.signedIn) authed { it.rename(n) }
        store.update { it.copy(displayName = n) }
    }

    // ------------------------------------------------------------------ library

    suspend fun search(q: String, offset: Int = 0, madhhab: String = "") = api().searchBooks(q, offset, madhhab = madhhab)

    /** Downloads a library book into Sources (or returns the copy already here). */
    suspend fun download(bookId: String): Long {
        dao.sourceByLibraryId(bookId)?.let { return it.id }
        val book = BookFile.parse(api().downloadBook(bookId))
        return BookFile.import(dao, book, libraryId = bookId)
    }

    /** Publishes a fully proofread book. Returns its library id. */
    /** [visibility] "public" or "family"; [madhhab] one of [MADHHABS] or "". */
    suspend fun publish(sourceId: Long, author: String, visibility: String = "public", madhhab: String = ""): String {
        val src = dao.source(sourceId) ?: throw CloudException(CloudException.Kind.NOT_FOUND)
        val items = dao.sourceItems(sourceId)
        if (items.isEmpty() || items.any { !it.checked }) throw CloudException(CloudException.Kind.INVALID, 0, "not proofread")
        val id = try {
            authed { it.publishBook(BookFile.toJson(src, items, author.trim()).put("visibility", visibility).put("madhhab", madhhab)) }
        } catch (e: CloudException) {
            // the same book is already there: link to it
            val existing = e.body?.optString("id").orEmpty()
            if (e.kind == CloudException.Kind.CONFLICT && existing.isNotBlank()) existing else throw e
        }
        dao.setLibraryId(sourceId, id, author.trim())
        return id
    }

    suspend fun report(bookId: String, reason: String) = authed { it.reportBook(bookId, reason.trim().take(1000)) }

    // ------------------------------------------------------------------ family

    private fun remember(f: Family) {
        store.update { it.copy(familyId = f.familyId, familyName = f.name, inviteCode = f.inviteCode) }
        CloudSyncWorker.schedule(app)
    }

    suspend fun createFamily(name: String, displayName: String, share: Boolean, password: String = ""): Family {
        store.update { it.copy(shareProgress = share) }
        if (!store.value.signedIn) ensureAccount(displayName, password) else if (displayName.isNotBlank() && displayName != store.value.displayName) setDisplayName(displayName)
        return authed { it.createFamily(name.trim().take(60)) }.also { remember(it); syncProgress() }
    }

    suspend fun joinFamily(code: String, displayName: String, share: Boolean, password: String = ""): Family {
        store.update { it.copy(shareProgress = share) }
        if (!store.value.signedIn) ensureAccount(displayName, password) else if (displayName.isNotBlank() && displayName != store.value.displayName) setDisplayName(displayName)
        return authed { it.joinFamily(code) }.also { remember(it); syncProgress() }
    }

    /** Latest family view; forgets the family locally if the server says we are no longer in one. */
    suspend fun refreshFamily(): Family? = try {
        authed { it.myFamily() }.also { remember(it) }
    } catch (e: CloudException) {
        if (e.kind != CloudException.Kind.NOT_FOUND) throw e
        store.update { it.copy(familyId = "", familyName = "", inviteCode = "") }
        CloudSyncWorker.cancel(app)
        null
    }

    suspend fun leaveFamily() {
        authed { it.leaveFamily() }
        store.update { it.copy(familyId = "", familyName = "", inviteCode = "") }
        CloudSyncWorker.cancel(app)
    }

    suspend fun setSharing(share: Boolean) {
        store.update { it.copy(shareProgress = share) }
        if (store.value.inFamily) syncProgress()
    }

    /** Sends a werd with its settings (days, colour, note, time reminders). [autoAdd]: parent → child, added without asking. */
    suspend fun sendWerd(userId: String, werdId: Long, note: String, autoAdd: Boolean = false): String {
        val w = dao.werd(werdId) ?: throw CloudException(CloudException.Kind.NOT_FOUND)
        val lines = dao.items(werdId).map { it.text to it.target }
        return authed { it.sendWerd(userId, w.title, note.trim().take(300), lines.take(500), werdSettings(w), autoAdd) }
    }

    /**
     * The parts of a werd that make sense on another phone: days, colour, note, group, and its reminders (its own and
     * its group's). Place, Wi-Fi and Bluetooth reminders say where the sender is, so they are left out unless
     * [includePlaces]; [withReminders] false leaves every reminder out, [withGroup] false the group.
     */
    suspend fun werdSettings(w: Werd, withReminders: Boolean = true, includePlaces: Boolean = false, withGroup: Boolean = true): JSONObject {
        val group = w.groupId?.let { gid -> dao.groups().firstOrNull { it.id == gid } }
        val reminders = if (!withReminders) emptyList() else dao.reminders().filter { r ->
            (r.werdId == w.id || (r.groupId != null && r.groupId == w.groupId && !r.excludes(w.id))) && (r.type.isTime || includePlaces)
        }
        return JSONObject().put("colorIndex", w.colorIndex).put("goalDaysMask", w.goalDaysMask).put("note", w.note)
            .put("group", if (withGroup) group?.name.orEmpty() else "")
            .put("groupColor", group?.colorIndex ?: 0)
            .put("textOnly", w.textOnly)
            .put("reminders", JSONArray().apply {
                reminders.forEach { r ->
                    put(JSONObject().put("type", r.type.name).put("enabled", r.enabled).put("hour", r.hour).put("minute", r.minute)
                        .put("daysMask", r.daysMask).put("intervalDays", r.intervalDays).put("prayer", r.prayer)
                        .put("offsetMin", r.offsetMin).put("skipIfDone", r.skipIfDone).put("cooldownHours", r.cooldownHours)
                        .apply {
                            r.dateValue?.let { put("dateValue", it) }
                            if (r.type == com.werd.app.data.ReminderType.LOCATION && r.lat != null && r.lng != null)
                                put("lat", r.lat).put("lng", r.lng).put("radius", r.radius.toDouble()).put("placeName", r.placeName).put("onExit", r.onExit)
                            if (r.type == com.werd.app.data.ReminderType.WIFI) put("ssid", r.ssid)
                            if (r.type == com.werd.app.data.ReminderType.BLUETOOTH) put("btAddress", r.btAddress).put("btName", r.btName)
                        })
                }
            })
    }

    /** Whether a werd has reminders that tell where its owner is (place, Wi-Fi, Bluetooth). */
    suspend fun hasPlaceReminders(w: Werd): Boolean = dao.reminders().any { r ->
        (r.werdId == w.id || (r.groupId != null && r.groupId == w.groupId && !r.excludes(w.id))) && !r.type.isTime
    }

    // ------------------------------------------------------------------ shared awrad (library)

    // signed in, the family's own awrad and mine are included
    suspend fun searchWerds(q: String, scope: String = "") = if (store.value.signedIn) authed { it.searchWerds(q, scope) } else api().searchWerds(q, scope)
    suspend fun sharedWerd(id: String) = if (store.value.signedIn) authed { it.sharedWerd(id) } else api().sharedWerd(id)

    /** Shares a werd to the library. [keepSettings]: with its group, days and reminders. Returns the library id. */
    suspend fun shareWerd(werdId: Long, visibility: String, keepSettings: Boolean, includePlaces: Boolean): String {
        val w = dao.werd(werdId) ?: throw CloudException(CloudException.Kind.NOT_FOUND)
        val lines = dao.items(werdId)
        val body = JSONObject().put("title", w.title).put("note", w.note).put("visibility", visibility).put("textOnly", w.textOnly)
            .put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("text", it.text).put("count", it.target)) } })
        if (keepSettings) body.put("werd", werdSettings(w, withReminders = true, includePlaces = includePlaces))
        return try { authed { it.shareWerd(body) } } catch (e: CloudException) {
            val existing = e.body?.optString("id").orEmpty()
            if (e.kind == CloudException.Kind.CONFLICT && existing.isNotBlank()) existing else throw e
        }
    }

    suspend fun moveSharedWerd(id: String, visibility: String) = authed { it.moveSharedWerd(id, visibility) }
    suspend fun withdrawSharedWerd(id: String) = authed { it.withdrawSharedWerd(id) }

    /** What the person adding a shared werd keeps of its settings. */
    data class Keep(val group: Boolean = true, val days: Boolean = true, val reminders: Set<Int> = emptySet())

    /** Adds a shared werd to this phone's awrad, with the parts of its settings the user kept. Returns the new werd's id. */
    suspend fun addShared(id: String, keep: Keep): Long {
        val sw = (if (store.value.signedIn) authed { it.sharedWerd(id, adding = true) } else api().sharedWerd(id, adding = true))
        return addWerd(sw.title, sw.note, sw.lines, sw.settings, keep, sw.textOnly)
    }

    /** The reminders a shared werd carries, for the "keep" choices (JSON objects, in order). */
    fun sharedReminders(settings: String): List<JSONObject> = runCatching {
        val a = JSONObject(settings).optJSONArray("reminders") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private suspend fun addWerd(title: String, note: String, lines: List<Pair<String, Int>>, settings: String, keep: Keep, textOnly: Boolean,
                                groupId: Long? = null): Long {
        val set = runCatching { JSONObject(settings) }.getOrNull()
        val group = groupId ?: if (!keep.group) null else set?.optString("group")?.takeIf { it.isNotBlank() }?.let { name ->
            dao.groups().firstOrNull { it.name == name }?.id
                ?: dao.insertGroup(com.werd.app.data.WerdGroup(name = name, position = dao.groups().size, colorIndex = set.optInt("groupColor", 0)))
        }
        val text = textOnly || set?.optBoolean("textOnly") == true
        val base = Werd(title = title.ifBlank { "—" }, groupId = group,
            note = set?.optString("note")?.takeIf { it.isNotBlank() } ?: note,
            colorIndex = set?.optInt("colorIndex", 0) ?: 0, textOnly = text,
            goalDaysMask = if (!keep.days) com.werd.app.data.ALL_DAYS
                else (set?.optInt("goalDaysMask", com.werd.app.data.ALL_DAYS) ?: com.werd.app.data.ALL_DAYS).takeIf { it in 1..127 } ?: com.werd.app.data.ALL_DAYS)
        val id = app.repo.saveWerd(base, lines.mapIndexed { i, (t, n) -> WerdItem(werdId = 0, position = i, text = t, target = if (text) 1 else n) })
        set?.optJSONArray("reminders")?.let { a ->
            for (k in 0 until a.length()) {
                if (k !in keep.reminders) continue
                val r = a.optJSONObject(k) ?: continue
                val type = runCatching { com.werd.app.data.ReminderType.valueOf(r.optString("type")) }.getOrNull() ?: continue
                app.repo.saveReminder(com.werd.app.data.Reminder(werdId = id, type = type, enabled = r.optBoolean("enabled", true),
                    hour = r.optInt("hour", 6).coerceIn(0, 23), minute = r.optInt("minute", 0).coerceIn(0, 59),
                    daysMask = r.optInt("daysMask", com.werd.app.data.ALL_DAYS).takeIf { it in 1..127 } ?: com.werd.app.data.ALL_DAYS,
                    intervalDays = r.optInt("intervalDays", 2).coerceIn(1, 365), prayer = r.optInt("prayer", 4),
                    offsetMin = r.optInt("offsetMin", 0), skipIfDone = r.optBoolean("skipIfDone", true),
                    cooldownHours = r.optInt("cooldownHours", 12).coerceIn(0, 168),
                    dateValue = if (r.has("dateValue")) r.optLong("dateValue") else null,
                    lat = if (r.has("lat")) r.optDouble("lat") else null, lng = if (r.has("lng")) r.optDouble("lng") else null,
                    radius = r.optDouble("radius", 200.0).toFloat(), placeName = r.optString("placeName"), onExit = r.optBoolean("onExit"),
                    ssid = r.optString("ssid"), btAddress = r.optString("btAddress"), btName = r.optString("btName")))
            }
        }
        return id
    }

    /** A line spoken by the server's voice (kept in the server's library). */
    suspend fun speech(text: String, voice: String) = authed { it.speech(text, voice) }
    suspend fun voices() = authed { it.voices() }
    suspend fun ocrModels() = authed { it.ocrModels() }
    suspend fun ocr(jpeg: ByteArray, model: String, task: String = "", title: String = "") = authed { it.ocr(jpeg, model, task, title) }
    suspend fun complete(prompt: String, model: String = "") = authed { it.complete(prompt, model) }
    suspend fun cancelTask(task: String) = authed { it.cancelTask(task) }
    suspend fun shareClip(voice: String, name: String, text: String, audio: ByteArray, ext: String) =
        authed { it.shareClip(voice, name, text, audio, ext) }

    suspend fun history(userId: String, days: Int = 30): List<Progress> = authed { it.history(userId, days) }
    suspend fun setRole(userId: String, parent: Boolean) = authed { it.setRole(userId, if (parent) "parent" else "child") }
    suspend fun removeMember(userId: String) = authed { it.removeMember(userId) }

    /** Today's summary as the family sees it. */
    fun summary(d: Dashboard): Progress = Progress(
        day = LocalDate.ofEpochDay(d.today).toString(), updatedAt = System.currentTimeMillis(), streak = d.dayStreak,
        due = d.dueToday.size, done = d.doneToday,
        werds = d.dueToday.map { WerdProgress(it.werd.title, it.done, it.total, it.completedToday) },
    )

    suspend fun syncProgress() {
        val s = store.value
        if (!s.configured || !s.signedIn || !s.inFamily) return
        authed { it.putProgress(s.shareProgress, if (s.shareProgress) summary(app.repo.loadDashboard()) else null) }
    }

    /** Fetches awrad sent by family into the local inbox. Returns the ones that are new here. */
    suspend fun syncInbox(): List<InboxWerd> {
        val s = store.value
        if (!s.configured || !s.signedIn) return emptyList()
        val remote = authed { it.inbox() }.filter { it.id !in s.handledInbox }
        val have = dao.inbox().map { it.serverId }.toSet()
        val fresh = remote.filter { it.id !in have }.map { w ->
            InboxWerd(serverId = w.id, fromName = w.fromName, title = w.title, note = w.note,
                lines = JSONArray().apply { w.lines.forEach { (t, n) -> put(JSONObject().put("text", t).put("count", n)) } }.toString(),
                sentAt = w.sentAt, settings = w.settings) to w.autoAdd
        }
        if (fresh.isNotEmpty()) dao.insertInbox(fresh.map { it.first })
        // a parent put these straight on this child's list
        val auto = fresh.filter { it.second }.map { it.first.serverId }.toSet()
        if (auto.isNotEmpty()) dao.inbox().filter { it.serverId in auto }.forEach { accept(it, null) }
        // forget handled ids the server no longer lists
        val listed = remote.map { it.id }.toSet()
        if (s.handledInbox.isNotEmpty()) store.update { it.copy(handledInbox = it.handledInbox.filter { id -> id in listed || id in have }.toSet()) }
        return fresh.map { it.first }
    }

    fun lines(w: InboxWerd): List<Pair<String, Int>> = runCatching {
        val a = JSONArray(w.lines)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("text") to o.optInt("count", 1).coerceAtLeast(1) } }
    }.getOrDefault(emptyList()).filter { it.first.isNotBlank() }

    /** Adds a received werd to the user's awrad, with the settings it was sent with (time reminders only). Returns its id. */
    suspend fun accept(w: InboxWerd, groupId: Long?): Long {
        val all = sharedReminders(w.settings).withIndex().filter { (_, r) ->
            runCatching { com.werd.app.data.ReminderType.valueOf(r.optString("type")).isTime }.getOrDefault(false) }.map { it.index }.toSet()
        val id = addWerd(w.title, w.note, lines(w), w.settings, Keep(group = true, days = true, reminders = all), textOnly = false, groupId = groupId)
        handled(w)
        return id
    }

    suspend fun dismiss(w: InboxWerd) = handled(w)

    private suspend fun handled(w: InboxWerd) {
        dao.deleteInbox(w.id)
        store.update { it.copy(handledInbox = it.handledInbox + w.serverId) }
        runCatching { authed { it.ackInbox(w.serverId) } }   // retried implicitly: the id stays in handledInbox until the server drops it
    }
}

/** Shares today's progress and picks up awrad sent by family. Every few hours, and soon after a werd is completed. */
class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val CHANNEL = "werd_family"
        private const val PERIODIC = "cloud_sync"
        private const val SOON = "cloud_sync_soon"
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun createChannel(context: Context) {
            val c = context.localized()
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, c.getString(R.string.channel_family), NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudSyncWorker>(3, TimeUnit.HOURS).setConstraints(net).build())
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)

        /** After a werd is completed: let the family see it within a minute or so. */
        fun requestSoon(context: Context) {
            val s = WerdApp.from(context).cloud.store.value
            if (!s.configured || !s.inFamily) return
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(net).setInitialDelay(20, TimeUnit.SECONDS).build())
        }
    }

    override suspend fun doWork(): Result {
        val cloud = WerdApp.from(applicationContext).cloud
        if (!cloud.store.value.configured || !cloud.store.value.signedIn) return Result.success()
        return try {
            runCatching { cloud.learnAddresses() }   // picks up a new Tailscale/LAN address of the server
            cloud.syncProgress()
            notify(cloud.syncInbox())
            Result.success()
        } catch (e: CloudException) {
            if (e.kind == CloudException.Kind.NETWORK || e.kind == CloudException.Kind.SERVER || e.kind == CloudException.Kind.RATE_LIMIT)
                Result.retry() else Result.success()
        }
    }

    private fun notify(fresh: List<InboxWerd>) {
        if (fresh.isEmpty()) return
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val c = ctx.localized()
        val first = fresh.first()
        val text = if (fresh.size == 1) c.getString(R.string.inbox_notif_one, first.fromName, first.title)
        else c.getString(R.string.inbox_notif_many, fresh.size)
        val pi = PendingIntent.getActivity(ctx, 4_000_000, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            NotificationManagerCompat.from(ctx).notify(4_000_000, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(R.string.inbox_notif_title))
                .setContentText(text)
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}
