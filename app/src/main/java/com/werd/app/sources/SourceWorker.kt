package com.werd.app.sources

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ai.AiClient
import com.werd.app.ai.AiException
import com.werd.app.ai.AiImage
import com.werd.app.ai.AiTasks
import com.werd.app.data.Source
import com.werd.app.localized
import com.werd.app.ocr.readCancellable
import com.werd.app.ui.MainActivity

/** Reads a source batch by batch with AI and stores the supplications it finds. Resumable. */
class SourceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** Lets tests (and future providers) swap the AI. */
    companion object {
        const val CHANNEL = "werd_sources"
        const val EXTRA_SOURCE = "open_source"
        @Volatile var clientOverride: AiClient? = null

        private fun name(id: Long) = "source_$id"

        /** The server's name for this book's scan, so cancelling here stops it there too. */
        fun taskTag(id: Long) = "book:$id"

        fun start(context: Context, id: Long, online: Boolean = true) {
            val req = OneTimeWorkRequestBuilder<SourceWorker>()
                .setInputData(workDataOf("id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (online) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, req)
        }

        fun stop(context: Context, id: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(name(id))
            NotificationManagerCompat.from(context).cancel(2_000_000 + id.toInt())
        }

        private fun rescanName(id: Long) = "rescan_$id"

        /** Re-reads pages [from]..[to] with [provider] ("gemini" | "claude" | [Sources.OFFLINE]) and [model]. */
        suspend fun rescan(context: Context, id: Long, from: Int, to: Int, provider: String, model: String) {
            WerdApp.from(context).db.dao().setRescan(id, from, to, 0)
            val req = OneTimeWorkRequestBuilder<SourceWorker>()
                .setInputData(workDataOf("id" to id, "from" to from, "to" to to, "provider" to provider, "model" to model))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(
                    if (provider == Sources.OFFLINE) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(rescanName(id), ExistingWorkPolicy.REPLACE, req)
        }

        suspend fun cancelRescan(context: Context, id: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(rescanName(id))
            WerdApp.from(context).db.dao().setRescan(id, 0, 0, 0)
            NotificationManagerCompat.from(context).cancel(2_000_000 + id.toInt())
        }

        private fun proofName(id: Long) = "proof_$id"

        /**
         * Proofreads a finished book: [readers] (see [Readers]) each read the pages of the items in [scope]
         * ("all" | "unchecked" | "flagged" | "pages" with [from]..[to]); [hiRes] reads each item's part of the page again at a
         * higher resolution; [quran], [spelling], [model] run the helpers (see [Helpers]). Only suggestions are added.
         */
        suspend fun proofread(context: Context, id: Long, readers: List<String>, scope: String, from: Int, to: Int, hiRes: Boolean,
                              quran: Boolean, spelling: Boolean, model: String) {
            WerdApp.from(context).db.dao().setProof(id, 0, 1)
            val online = readers.any { it != Readers.BUILTIN && !it.startsWith("phone:") && it != "nano" } || model.isNotBlank()
            val req = OneTimeWorkRequestBuilder<SourceWorker>()
                .setInputData(workDataOf("id" to id, "proof" to true, "readers" to readers.joinToString(","), "scope" to scope, "from" to from,
                    "to" to to, "hiRes" to hiRes, "quran" to quran, "spelling" to spelling, "model" to model))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (online) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(proofName(id), ExistingWorkPolicy.REPLACE, req)
        }

        suspend fun cancelProof(context: Context, id: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(proofName(id))
            WerdApp.from(context).db.dao().setProof(id, 0, 0)
            NotificationManagerCompat.from(context).cancel(2_000_000 + id.toInt())
        }

        suspend fun pause(context: Context, id: Long) {
            WerdApp.from(context).db.dao().setSourceStatus(id, "PAUSED")
            stop(context, id)
        }

        suspend fun resume(context: Context, id: Long) {
            val dao = WerdApp.from(context).db.dao()
            dao.setSourceStatus(id, "QUEUED")
            start(context, id, online = dao.source(id)?.provider != Sources.OFFLINE)
        }

        fun createChannel(context: Context) {
            val c = context.localized()
            val ch = NotificationChannel(CHANNEL, c.getString(R.string.channel_sources), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun openIntent(context: Context, id: Long): PendingIntent =
            PendingIntent.getActivity(context, 3_000_000 + id.toInt(),
                Intent(context, MainActivity::class.java).setAction("com.werd.app.SOURCE_$id").putExtra(EXTRA_SOURCE, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private val ctx get() = applicationContext

    private fun progressNotification(s: Source, done: Int, found: Int) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title)
            .setContentText(ctx.localized().getString(R.string.source_progress_notif, done, s.pageCount, found))
            .setProgress(s.pageCount, done, false)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openIntent(ctx, s.id))
            .build()

    private suspend fun showProgress(s: Source, done: Int, found: Int) {
        val n = progressNotification(s, done, found)
        val info = if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(2_000_000 + s.id.toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(2_000_000 + s.id.toInt(), n)
        // Not allowed when started from the background on newer Android; the work still runs, just without the ongoing notice.
        runCatching { setForeground(info) }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        val dao = WerdApp.from(ctx).db.dao()
        val src = dao.source(id) ?: return Result.success()
        if (inputData.getBoolean("proof", false)) return proof(src)
        val rescanFrom = inputData.getInt("from", 0)
        if (rescanFrom > 0) return rescan(src, rescanFrom, inputData.getInt("to", rescanFrom),
            inputData.getString("provider").orEmpty(), inputData.getString("model").orEmpty())
        if (src.status == "DONE" || src.status == "PAUSED") return Result.success()
        if (src.provider == Sources.OFFLINE) return offline(src)
        val ai = clientOverride ?: runCatching { Sources.aiClient(ctx) }.getOrNull()
        if (ai == null) {
            dao.setSourceStatus(id, "ERROR", ctx.localized().getString(R.string.ai_needed))
            return Result.success()
        }
        dao.setSourceStatus(id, "RUNNING")
        val existing = dao.sourceItems(id)
        val keys = existing.map { AiTasks.dedupeKey(it.text) }.toHashSet()
        var pos = existing.size
        var done = src.pagesDone
        showProgress(src, done, pos)

        var index = com.werd.app.sources.BookIndex.decode(src.indexJson)
        var checker: com.werd.app.ocr.PageReader? = null
        try { PageSource(src).use { ps ->
            suspend fun run(from: Int, to: Int): AiTasks.Extracted =
                if (src.kind == Sources.KIND_TEXT) AiTasks.extractFromText(ai, ps.textChunk(from), from + 1)
                else {
                    val look = to < src.pageCount
                    val idx = (from until to).toList() + (if (look) listOf(to) else emptyList())
                    AiTasks.extractFromPages(ai, idx.map { AiImage(ps.jpeg(it)) }, from + 1, to, look)
                }

            while (done < src.pageCount) {
                if (isStopped) return Result.success()
                val now = dao.source(id) ?: return Result.success()
                if (now.status == "PAUSED") return Result.success()
                val step = if (src.kind == Sources.KIND_TEXT) 1 else Sources.BATCH
                val to = minOf(done + step, src.pageCount)
                val found = try {
                    val r = run(done, to)
                    if (r.index.isNotEmpty()) {
                        index = BookIndex.merge(index, r.index)
                        dao.setSourceIndex(id, BookIndex.encode(index))
                    }
                    if (r.truncated && to - done > 1) (done until to).flatMap { p -> run(p, p + 1).items } else r.items
                } catch (e: AiException) {
                    val paused = e.kind == AiException.Kind.QUOTA || e.kind == AiException.Kind.NETWORK
                    dao.setSourceProgress(id, done, if (paused) "PAUSED" else "ERROR", e.message.orEmpty())
                    return Result.success()
                } catch (e: Exception) {
                    dao.setSourceProgress(id, done, "ERROR", e.message ?: e.javaClass.simpleName)
                    return Result.success()
                }
                var fresh = found.filter { keys.add(AiTasks.dedupeKey(it.text)) }
                    .mapIndexed { i, it -> it.copy(sourceId = id, position = pos + i) }
                // a second reading of each page by the reader chosen for proofreading
                if (src.scanMode != TwoPass.SINGLE && src.kind != Sources.KIND_TEXT && fresh.isNotEmpty()) fresh = try {
                    fresh.groupBy { it.page }.flatMap { (page, items) ->
                        if (src.scanMode == TwoPass.DOUBTFUL && !TwoPass.doubtful(items.joinToString("\n") { it.text })) items
                        else {
                            val r = checker ?: Readers.open(ctx, src.checkReader, taskTag(id), src.title).also { checker = it }
                            val bmp = ps.bitmap(page - 1, 2000)
                            val other = try { r.readCancellable(bmp, true) } finally { bmp.recycle() }
                            items.map { Proof.addReading(it, src.checkReader.ifBlank { Readers.BUILTIN }, other) }
                        }
                    }.sortedBy { it.position }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    fresh   // the second reader failed: keep the first reading
                }
                if (fresh.isNotEmpty()) dao.insertSourceItems(fresh)
                pos += fresh.size
                done = to
                dao.setSourceProgress(id, done, "RUNNING")
                showProgress(src, done, pos)
            }
        } } finally { checker?.close() }
        finish(src, done, pos)
        return Result.success()
    }

    /** Reads the book on the phone: text recognition page by page, then rule-based extraction. */
    private suspend fun offline(src: Source): Result {
        val id = src.id
        val dao = WerdApp.from(ctx).db.dao()
        dao.setSourceStatus(id, "RUNNING")
        val existing = dao.sourceItems(id)
        val keys = existing.map { AiTasks.dedupeKey(it.text) }.toHashSet()
        var pos = existing.size
        var done = src.pagesDone
        val letters = WerdApp.from(ctx).settings.value.ocrLettersOnly
        val state = OfflineExtract.State()
        showProgress(src, done, pos)
        var reader: com.werd.app.ocr.PageReader? = null
        var checker: com.werd.app.ocr.PageReader? = null
        var index = BookIndex.decode(src.indexJson)
        val running = PageLayout.Running(src.title)
        var last = existing.maxByOrNull { it.position }
        try {
            PageSource(src).use { ps ->
                while (done < src.pageCount) {
                    if (isStopped) return Result.success()
                    val now = dao.source(id) ?: return Result.success()
                    if (now.status == "PAUSED") return Result.success()
                    val found = if (src.kind == Sources.KIND_TEXT) OfflineExtract.extract(ps.textChunk(done), done + 1, state) else {
                        val r = reader ?: com.werd.app.ocr.AiReader.open(ctx, task = taskTag(id), title = src.title).also { reader = it }
                        val page = readPage(ps, done, r, { checker ?: Readers.open(ctx, now.checkReader, taskTag(id), src.title).also { checker = it } },
                            now.checkReader.ifBlank { Readers.BUILTIN }, letters, now.scanMode, state, running, last)
                        BookIndex.fromPage(page.text).takeIf { it.isNotEmpty() }?.let { more ->
                            index = BookIndex.merge(index, more)
                            dao.setSourceIndex(id, BookIndex.encode(index))
                        }
                        // the last supplication of the page before carries on here
                        page.continued?.let { dao.updateSourceItem(it); last = it }
                        page.items
                    }
                    val fresh = found.filter { keys.add(AiTasks.dedupeKey(it.text)) }
                        .mapIndexed { i, it -> it.copy(sourceId = id, position = pos + i) }
                    if (fresh.isNotEmpty()) { dao.insertSourceItems(fresh); last = dao.sourceItems(id).maxByOrNull { it.position } }
                    pos += fresh.size
                    done++
                    dao.setSourceProgress(id, done, "RUNNING")
                    showProgress(src, done, pos)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // cancelled on the server's dashboard: paused here, so it can be resumed
            val cancelled = e is com.werd.app.cloud.CloudException && e.status == 409 && e.body?.optString("code") == "cancelled"
            dao.setSourceProgress(id, done, if (cancelled) "PAUSED" else "ERROR", e.message ?: e.javaClass.simpleName)
            NotificationManagerCompat.from(ctx).cancel(2_000_000 + id.toInt())
            return Result.success()
        } finally {
            reader?.close(); checker?.close()
        }
        finish(src, done, pos)
        return Result.success()
    }

    private class Page(val text: String, val items: List<com.werd.app.data.SourceItem>, val continued: com.werd.app.data.SourceItem? = null)

    /**
     * Reads one page with [reader] and finds its supplications: the header, footer and footnotes are taken out first
     * (see [PageLayout]), and text that carries on the [previous] page's last supplication is added to it. With a
     * two-pass scan ([mode] 1, or 2 when the reading looks doubtful) the page is also read by [checker] (named [checkerBy])
     * and the readings are compared.
     */
    private suspend fun readPage(ps: PageSource, index: Int, reader: com.werd.app.ocr.PageReader,
                                 checker: () -> com.werd.app.ocr.PageReader, checkerBy: String, letters: Boolean, mode: Int,
                                 state: OfflineExtract.State, running: PageLayout.Running = PageLayout.Running(),
                                 previous: com.werd.app.data.SourceItem? = null): Page {
        val bmp = ps.bitmap(index, 2000)
        try {
            val main = reader.readCancellable(bmp, letters)
            val layout = PageLayout.split(main, running)
            var body = layout.body
            var continued: com.werd.app.data.SourceItem? = null
            if (previous != null && previous.page == index && !previous.checked) {
                val (more, rest) = OfflineExtract.continuation(previous.text, body)
                if (more.isNotBlank()) {
                    val d = com.werd.app.parse.WerdParser.detect(more)
                    continued = previous.copy(text = (previous.text + " " + (if (d.count != null) d.rest else more)).replace(Regex("\\s+"), " ").trim(),
                        count = d.count ?: previous.count, textOnly = previous.textOnly && d.count == null)
                    body = rest
                }
            }
            val items = PageLayout.attachFootnotes(
                OfflineExtract.extract(body, index + 1, state, layout.top, layout.bottom, running.section), layout.footnotes)
            val second = mode == TwoPass.TWO || (mode == TwoPass.DOUBTFUL && TwoPass.doubtful(main))
            if (!second || items.isEmpty()) return Page(main, items, continued)
            val other = checker().readCancellable(bmp, true)
            return Page(main, items.map { Proof.addReading(it, checkerBy, PageLayout.split(other, PageLayout.Running()).body) }, continued)
        } finally { bmp.recycle() }
    }

    /** Re-reads a page range and merges the result (see [Rescan.merge]); nothing changes unless it completes. */
    private suspend fun rescan(src: Source, from: Int, to: Int, provider: String, model: String): Result {
        val id = src.id
        val dao = WerdApp.from(ctx).db.dao()
        val s = WerdApp.from(ctx).settings.value
        val offline = provider == Sources.OFFLINE
        val ai = if (offline) null else clientOverride ?: run {
            val key = if (provider == AiClient.CLAUDE) s.aiKeyClaude else s.aiKeyGemini
            if (key.isBlank()) null else AiClient.create(provider, key, model)
        }
        if (!offline && ai == null) {
            dao.setRescan(id, 0, 0, 0, ctx.localized().getString(R.string.ai_needed)); return Result.success()
        }
        val pages = (from..to).toList()
        val found = ArrayList<com.werd.app.data.SourceItem>()
        var reader: com.werd.app.ocr.PageReader? = null
        var checker: com.werd.app.ocr.PageReader? = null
        try {
            PageSource(src).use { ps ->
                if (offline) {
                    val letters = s.ocrLettersOnly
                    val state = OfflineExtract.State()
                    val running = PageLayout.Running(src.title)
                    pages.forEachIndexed { k, p ->
                        if (isStopped) return Result.success()
                        found += if (src.kind == Sources.KIND_TEXT) OfflineExtract.extract(ps.textChunk(p - 1), p, state) else {
                            val r = reader ?: com.werd.app.ocr.AiReader.open(ctx, task = taskTag(id), title = src.title).also { reader = it }
                            readPage(ps, p - 1, r, { checker ?: Readers.open(ctx, src.checkReader, taskTag(id), src.title).also { checker = it } },
                                src.checkReader.ifBlank { Readers.BUILTIN }, letters, src.scanMode, state, running).items
                        }
                        dao.setRescan(id, from, to, k + 1)
                        showRescan(src, from, to, k + 1)
                    }
                } else {
                    val step = if (src.kind == Sources.KIND_TEXT) 1 else Sources.BATCH
                    var k = 0
                    while (k < pages.size) {
                        if (isStopped) return Result.success()
                        val a = pages[k]; val b = minOf(a + step, to + 1)       // [a, b)
                        suspend fun read(x: Int, y: Int): AiTasks.Extracted =
                            if (src.kind == Sources.KIND_TEXT) AiTasks.extractFromText(ai!!, ps.textChunk(x - 1), x)
                            else {
                                val look = y <= src.pageCount && y <= to
                                val idx = (x until y).map { it - 1 } + (if (look) listOf(y - 1) else emptyList())
                                AiTasks.extractFromPages(ai!!, idx.map { AiImage(ps.jpeg(it)) }, x, y - 1, look)
                            }
                        val r = read(a, b)
                        found += if (r.truncated && b - a > 1) (a until b).flatMap { p -> read(p, p + 1).items } else r.items
                        k += b - a
                        dao.setRescan(id, from, to, k)
                        showRescan(src, from, to, k)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dao.setRescan(id, 0, 0, 0, e.message ?: e.javaClass.simpleName)
            NotificationManagerCompat.from(ctx).cancel(2_000_000 + id.toInt())
            return Result.success()
        } finally {
            reader?.close(); checker?.close()
        }
        val plan = Rescan.merge(dao.sourceItems(id), from, to, found, id)
        dao.replacePages(plan.removed, plan.updated, plan.inserted)
        dao.setRescan(id, 0, 0, 0)
        fixTitles(src)
        relinkIndex(id)
        NotificationManagerCompat.from(ctx).cancel(2_000_000 + id.toInt())
        return Result.success()
    }

    private suspend fun showRescan(s: Source, from: Int, to: Int, done: Int) {
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title)
            .setContentText(ctx.localized().getString(R.string.rescan_progress, from, to))
            .setProgress(to - from + 1, done, false)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openIntent(ctx, s.id))
            .build()
        val info = if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(2_000_000 + s.id.toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(2_000_000 + s.id.toInt(), n)
        runCatching { setForeground(info) }
    }

    /** Links the book's index entries to its supplications (after a scan or re-read changed them). */
    private suspend fun relinkIndex(id: Long) {
        val dao = WerdApp.from(ctx).db.dao()
        val s = dao.source(id) ?: return
        val entries = BookIndex.decode(s.indexJson)
        if (entries.isNotEmpty()) dao.setSourceIndex(id, BookIndex.encode(BookIndex.link(entries, dao.sourceItems(id))))
    }

    /** "Proofread with…" on a finished book (see [proofread]). */
    private suspend fun proof(src: Source): Result {
        val id = src.id
        val dao = WerdApp.from(ctx).db.dao()
        val readers = inputData.getString("readers").orEmpty().split(',').filter { it.isNotBlank() }
        val scope = inputData.getString("scope") ?: "unchecked"
        val from = inputData.getInt("from", 1); val to = inputData.getInt("to", src.pageCount)
        val hiRes = inputData.getBoolean("hiRes", false)
        val model = inputData.getString("model").orEmpty()
        fun inScope(it: com.werd.app.data.SourceItem) = when (scope) {
            "all" -> true
            "flagged" -> it.diffWords.isNotBlank()
            "pages" -> it.page in from..to
            else -> !it.checked
        }
        val chosen = dao.sourceItems(id).filter(::inScope)
        val pages = if (Sources.hasPages(src) && src.kind != Sources.KIND_TEXT) chosen.map { it.page }.distinct().sorted() else emptyList()
        val withModel = if (model.isNotBlank()) 1 else 0
        val total = (pages.size * readers.size + 1 + withModel).coerceAtLeast(1)
        var step = 0
        suspend fun tick() { step++; dao.setProof(id, step.coerceAtMost(total - 1), total); showRescan(src, 1, total, step) }
        val open = HashMap<String, com.werd.app.ocr.PageReader>()
        try {
            if (pages.isNotEmpty() && readers.isNotEmpty()) PageSource(src).use { ps ->
                for (p in pages) {
                    for (r in readers) {
                        if (isStopped) return Result.success()
                        val reader = open.getOrPut(r) { Readers.open(ctx, r, taskTag(id), src.title) }
                        val items = dao.sourceItems(id).filter { it.page == p && inScope(it) }
                        if (items.isEmpty()) { tick(); continue }
                        val updated = if (hiRes) {
                            // each item's part of the page, from a sharper rendering of it
                            val bmp = ps.bitmap(p - 1, 3000)
                            try {
                                items.map { item ->
                                    val band = item.region.split('-').mapNotNull { it.toFloatOrNull() }
                                    val (a, b) = if (band.size == 2) band[0] to band[1] else 0f to 1f
                                    val y0 = ((a - 0.04f).coerceAtLeast(0f) * bmp.height).toInt()
                                    val y1 = ((b + 0.04f).coerceAtMost(1f) * bmp.height).toInt().coerceAtLeast(y0 + 16).coerceAtMost(bmp.height)
                                    val crop = android.graphics.Bitmap.createBitmap(bmp, 0, y0, bmp.width, y1 - y0)
                                    val text = try { reader.readCancellable(crop, false) } finally { if (crop !== bmp) crop.recycle() }
                                    Proof.addReading(item, "$r@hd", text)
                                }
                            } finally { bmp.recycle() }
                        } else {
                            val bmp = ps.bitmap(p - 1, 2000)
                            val text = try { reader.readCancellable(bmp, false) } finally { bmp.recycle() }
                            val body = PageLayout.split(text, PageLayout.Running(src.title)).body
                            items.map { Proof.addReading(it, r, body) }
                        }
                        dao.updateSourceItems(updated)
                        tick()
                    }
                }
            }
            // the helpers that need no reader
            val quran = inputData.getBoolean("quran", false); val spelling = inputData.getBoolean("spelling", false)
            if (quran || spelling) {
                val all = dao.sourceItems(id)
                val book = Helpers.Spelling.bookWords(all)
                val changed = all.filter(::inScope).filter { !it.checked }.mapNotNull { it ->
                    var n = it
                    if (quran) n = Proof.addHints(n, Helpers.Quran.suggest(ctx, n.text), "quran")
                    if (spelling) n = Proof.addHints(n, Helpers.Spelling.suggest(ctx, n.text, book).filter { h -> Proof.hints(n).none { q -> q.word == h.word } }, "spelling")
                    n.takeIf { x -> x != it }
                }
                if (changed.isNotEmpty()) dao.updateSourceItems(changed)
            }
            tick()
            if (model.isNotBlank()) {
                val flagged = dao.sourceItems(id).filter { inScope(it) && it.diffWords.isNotBlank() }
                for (it in flagged) {
                    if (isStopped) return Result.success()
                    val more = Helpers.ContextModel.suggest(ctx, model, it)
                    if (more.isNotEmpty()) dao.updateSourceItem(Proof.addHints(dao.sourceItem(it.id) ?: it, more, "model"))
                }
                tick()
            }
            dao.setProof(id, 0, 0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dao.setProof(id, 0, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            open.values.forEach { runCatching { it.close() } }
            NotificationManagerCompat.from(ctx).cancel(2_000_000 + id.toInt())
        }
        return Result.success()
    }

    /** Titles that tell the items apart (see [Titles]). */
    private suspend fun fixTitles(src: Source) {
        val dao = WerdApp.from(ctx).db.dao()
        val changed = Titles.fix(dao.sourceItems(src.id), src.title)
        if (changed.isNotEmpty()) dao.updateSourceItems(changed)
        // the offline helpers (the Quran, spelling) add their suggestions to what wasn't proofread
        runCatching { Helpers.offline(ctx, dao.sourceItems(src.id)).takeIf { it.isNotEmpty() }?.let { dao.updateSourceItems(it) } }
    }

    private suspend fun finish(src: Source, done: Int, pos: Int) {
        val id = src.id
        val dao = WerdApp.from(ctx).db.dao()
        fixTitles(src)
        relinkIndex(id)
        dao.setSourceProgress(id, done, "DONE")
        NotificationManagerCompat.from(ctx).cancel(2_000_000 + id.toInt())
        val allowed = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching {
            NotificationManagerCompat.from(ctx).notify(2_500_000 + id.toInt(),
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(src.title)
                    .setContentText(ctx.localized().getString(R.string.source_done_notif, pos))
                    .setContentIntent(openIntent(ctx, id)).setAutoCancel(true).build())
        }
    }
}
