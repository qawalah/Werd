package com.werd.app.data

import android.content.Context
import com.werd.app.util.Days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.LocalTime

data class WerdUi(
    val werd: Werd,
    val items: List<WerdItem>,
    val group: WerdGroup?,
    val counts: Map<Long, Int>,
    val done: Int,
    val total: Int,
    val completedToday: Boolean,
    val dueToday: Boolean,
    val streak: Int,
    val bestStreak: Int,
    val completions: Int,
    val reminders: List<Reminder>,
    /** Minutes after midnight of today's first time-reminder, if any. */
    val todayMinutes: Int?,
    val completedDays: Set<Long>,
) {
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
    val currentIndex: Int get() = items.indexOfFirst { (counts[it.id] ?: 0) < it.target }.let { if (it < 0) 0 else it }
}

data class Dashboard(
    val today: Long,
    val werds: List<WerdUi>,
    val groups: List<WerdGroup>,
    val sessions: List<DaySession>,
    val reminders: List<Reminder>,
) {
    val dueToday get() = werds.filter { it.dueToday }
    /** The tallies only: texts to read stay out of the statistics. */
    fun tallyOnly(): Dashboard {
        val texts = werds.filter { it.werd.textOnly }.map { it.werd.id }.toSet()
        return if (texts.isEmpty()) this else copy(werds = werds.filter { it.werd.id !in texts }, sessions = sessions.filter { it.werdId !in texts })
    }
    val doneToday get() = dueToday.count { it.completedToday }
    val next: WerdUi? get() = dueToday.filter { !it.completedToday }
        .sortedWith(compareBy<WerdUi> { it.todayMinutes ?: Int.MAX_VALUE }.thenBy { it.werd.position })
        .firstOrNull()

    /** Consecutive days (ending today/yesterday) with at least one completed werd. */
    val dayStreak: Int get() {
        val texts = werds.filter { it.werd.textOnly }.map { it.werd.id }.toSet()
        val days = sessions.filter { it.completed && it.werdId !in texts }.map { it.day }.toHashSet()
        var d = if (today in days) today else today - 1
        var n = 0
        while (d in days) { n++; d-- }
        return n
    }
}

data class IncResult(val changed: Boolean, val justCompleted: Boolean, val itemDone: Boolean)

class Repository(private val context: Context, val dao: WerdDao, val settings: Settings, scope: CoroutineScope) {
    private val mutex = Mutex()

    fun today(): Long = Days.today(settings.value.dayStartHour)

    val dayFlow: StateFlow<Long> = flow {
        while (true) { emit(today()); delay(20_000) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, today())

    val dashboard: Flow<Dashboard> = combine(
        dao.werdsFlow(), dao.allItemsFlow(), dao.groupsFlow(), dao.allSessionsFlow(),
        combine(dao.remindersFlow(), dayFlow) { r, d -> r to d },
    ) { werds, items, groups, sessions, (reminders, today) ->
        build(werds, items, groups, sessions, reminders, today)
    }

    suspend fun loadDashboard(): Dashboard =
        build(dao.werds(), dao.allItems(), dao.groups(), dao.allSessions(), dao.reminders(), today())

    private fun build(
        werds: List<Werd>, items: List<WerdItem>, groups: List<WerdGroup>,
        sessions: List<DaySession>, reminders: List<Reminder>, today: Long,
    ): Dashboard {
        val itemsBy = items.groupBy { it.werdId }
        val sessBy = sessions.groupBy { it.werdId }
        val remBy = reminders.filter { it.werdId != null }.groupBy { it.werdId }
        val groupRemBy = reminders.filter { it.groupId != null }.groupBy { it.groupId }
        val groupBy = groups.associateBy { it.id }
        val ui = werds.map { w ->
            val its = itemsBy[w.id].orEmpty()
            val ss = sessBy[w.id].orEmpty()
            val todaySession = ss.firstOrNull { it.day == today }
            val counts = todaySession?.let { Counts.parse(it.counts) } ?: emptyMap()
            val completedDays = ss.filter { it.completed }.map { it.day }.toHashSet()
            val createdDay = java.time.Instant.ofEpochMilli(w.createdAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            // own reminders + the group's reminders this werd hasn't opted out of
            val rems = remBy[w.id].orEmpty() + w.groupId?.let { g -> groupRemBy[g].orEmpty().filter { !it.excludes(w.id) } }.orEmpty()
            WerdUi(
                werd = w, items = its, group = w.groupId?.let { groupBy[it] }, counts = counts,
                done = its.sumOf { minOf(counts[it.id] ?: 0, it.target) },
                total = its.sumOf { it.target },
                completedToday = todaySession?.completed == true,
                dueToday = !w.textOnly && Days.isInMask(w.goalDaysMask, today),
                streak = if (w.textOnly) 0 else Stats.streak(w.goalDaysMask, completedDays, today),
                bestStreak = if (w.textOnly) 0 else Stats.bestStreak(w.goalDaysMask, completedDays, minOf(createdDay, completedDays.minOrNull() ?: createdDay), today),
                completions = completedDays.size,
                reminders = rems,
                todayMinutes = rems.filter { it.enabled }.mapNotNull { todayMinutesOf(it, today) }.minOrNull(),
                completedDays = completedDays,
            )
        }
        return Dashboard(today, ui, groups, sessions, reminders)
    }

    private fun todayMinutesOf(r: Reminder, today: Long): Int? = minutesOn(r, today)

    // ------------------------------------------------------------------ werds

    suspend fun saveWerd(werd: Werd, items: List<WerdItem>): Long = mutex.withLock {
        val id = if (werd.id == 0L) {
            dao.insertWerd(werd.copy(position = dao.nextWerdPosition(), createdAt = System.currentTimeMillis()))
        } else {
            dao.updateWerd(werd.copy(updatedAt = System.currentTimeMillis())); werd.id
        }
        val normalized = items.mapIndexed { i, it -> it.copy(werdId = id, position = i, target = it.target.coerceAtLeast(1)) }
        val ids = dao.upsertItems(normalized)
        val keep = normalized.mapIndexed { i, it -> if (it.id != 0L) it.id else ids[i] }
        dao.deleteItemsExcept(id, keep.ifEmpty { listOf(-1L) })
        recomputeToday(id)
        id
    }.also { WidgetBridge.refresh(context) }

    private suspend fun recomputeToday(werdId: Long) {
        val day = today()
        val s = dao.session(werdId, day) ?: return
        val items = dao.items(werdId)
        val counts = Counts.parse(s.counts).filterKeys { k -> items.any { it.id == k } }
        val done = items.isNotEmpty() && items.all { (counts[it.id] ?: 0) >= it.target }
        dao.upsertSession(s.copy(counts = Counts.encode(counts), completed = done,
            completedAt = if (done) s.completedAt ?: System.currentTimeMillis() else null))
    }

    suspend fun deleteWerd(id: Long) {
        val remIds = dao.reminderIdsFor(id)
        dao.deleteWerd(id)
        com.werd.app.reminder.ReminderScheduler.cancelAlarms(context, remIds)
        com.werd.app.reminder.ReminderScheduler.syncTriggers(context)
        WidgetBridge.refresh(context)
    }

    suspend fun duplicateWerd(id: Long, suffix: String): Long {
        val w = dao.werd(id) ?: return 0
        val items = dao.items(id).map { it.copy(id = 0) }
        return saveWerd(w.copy(id = 0, title = w.title + suffix), items)
    }

    suspend fun moveToGroup(werdId: Long, groupId: Long?) {
        dao.werd(werdId)?.let { dao.updateWerd(it.copy(groupId = groupId)) }
    }

    suspend fun reorderWerds(ordered: List<Werd>) {
        dao.updateWerds(ordered.mapIndexed { i, w -> w.copy(position = i) })
    }

    // ------------------------------------------------------------------ groups

    suspend fun addGroup(name: String, colorIndex: Int = 0): Long =
        dao.insertGroup(WerdGroup(name = name, position = dao.groups().size, colorIndex = colorIndex))

    suspend fun saveGroups(groups: List<WerdGroup>) = dao.updateGroups(groups.mapIndexed { i, g -> g.copy(position = i) })
    suspend fun deleteGroup(id: Long) {
        val remIds = dao.reminderIdsForGroup(id)
        dao.deleteGroup(id)   // group reminders go with it (foreign key cascade)
        com.werd.app.reminder.ReminderScheduler.cancelAlarms(context, remIds)
        com.werd.app.reminder.ReminderScheduler.syncTriggers(context)
    }

    /** Opt a werd in/out of a group reminder. */
    suspend fun setGroupReminderApplies(reminderId: Long, werdId: Long, applies: Boolean) {
        val r = dao.reminder(reminderId) ?: return
        dao.upsertReminder(r.withExcluded(werdId, !applies))
    }

    /** "Adjust for this werd": a werd-only copy of a group reminder; the werd leaves the group version. */
    suspend fun customiseGroupReminder(reminderId: Long, werdId: Long): Long {
        val r = dao.reminder(reminderId) ?: return 0
        dao.upsertReminder(r.withExcluded(werdId, true))
        return saveReminder(r.copy(id = 0, werdId = werdId, groupId = null, excludedWerds = "", lastFired = 0))
    }

    // ------------------------------------------------------------------ tally

    suspend fun increment(werdId: Long, itemId: Long, delta: Int = 1): IncResult = mutex.withLock {
        val day = today()
        val items = dao.items(werdId)
        val item = items.firstOrNull { it.id == itemId } ?: return@withLock IncResult(false, false, false)
        val s = dao.session(werdId, day) ?: DaySession(werdId = werdId, day = day)
        val counts = Counts.parse(s.counts).toMutableMap()
        val cur = counts[itemId] ?: 0
        val nv = (cur + delta).coerceIn(0, item.target)
        if (nv == cur) return@withLock IncResult(false, false, cur >= item.target)
        counts[itemId] = nv
        val done = items.all { (counts[it.id] ?: 0) >= it.target }
        val just = done && !s.completed
        dao.upsertSession(s.copy(
            counts = Counts.encode(counts), completed = done,
            completedAt = if (done) (s.completedAt ?: System.currentTimeMillis()) else null,
            updatedAt = System.currentTimeMillis(),
        ))
        IncResult(true, just, nv >= item.target)
    }.also {
        if (it.changed) WidgetBridge.refresh(context)
        if (it.justCompleted) com.werd.app.cloud.CloudSyncWorker.requestSoon(context)
    }

    suspend fun setAll(werdId: Long, complete: Boolean) = mutex.withLock {
        val day = today()
        val items = dao.items(werdId)
        val s = dao.session(werdId, day) ?: DaySession(werdId = werdId, day = day)
        if (complete) {
            dao.upsertSession(s.copy(counts = Counts.encode(items.associate { it.id to it.target }),
                completed = items.isNotEmpty(), completedAt = s.completedAt ?: System.currentTimeMillis()))
        } else if (s.id != 0L) {
            dao.deleteSession(werdId, day)
        }
    }.also { WidgetBridge.refresh(context); if (complete) com.werd.app.cloud.CloudSyncWorker.requestSoon(context) }

    suspend fun isCompletedToday(werdId: Long): Boolean = dao.session(werdId, today())?.completed == true

    // ------------------------------------------------------------------ reminders

    suspend fun saveReminder(r: Reminder): Long {
        val id = dao.upsertReminder(r).let { if (r.id != 0L) r.id else it }
        com.werd.app.reminder.ReminderScheduler.syncAll(context)
        return id
    }

    suspend fun setReminderEnabled(id: Long, enabled: Boolean) {
        dao.setReminderEnabled(id, enabled)
        com.werd.app.reminder.ReminderScheduler.syncAll(context)
    }

    suspend fun deleteReminder(id: Long) {
        dao.deleteReminder(id)
        com.werd.app.reminder.ReminderScheduler.cancelAlarms(context, listOf(id))
        com.werd.app.reminder.ReminderScheduler.syncTriggers(context)
    }

    // ------------------------------------------------------------------ seed

    suspend fun seedIfNeeded(arabic: Boolean) {
        if (settings.value.seeded) return
        settings.update { it.copy(seeded = true) }
        if (dao.werds().isNotEmpty()) return
        val evening = addGroup(if (arabic) "المساء" else "Evening", 0)
        val prayer = addGroup(if (arabic) "بعد الصلاة" else "After prayer", 1)
        val id = saveWerd(
            Werd(title = Seed.RATIB_TITLE, groupId = evening, colorIndex = 0),
            Seed.ratib.mapIndexed { i, (t, n) -> WerdItem(werdId = 0, position = i, text = t, target = n) },
        )
        saveReminder(Reminder(werdId = id, type = ReminderType.PRAYER, prayer = 4, offsetMin = 20, enabled = false))
        val tasbih = saveWerd(
            Werd(title = Seed.TASBIH_TITLE, groupId = prayer, colorIndex = 1),
            Seed.tasbih.mapIndexed { i, (t, n) -> WerdItem(werdId = 0, position = i, text = t, target = n) },
        )
        saveReminder(Reminder(werdId = tasbih, type = ReminderType.PRAYER, prayer = 0, offsetMin = 10, enabled = false))
    }

    fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
    fun nowDateTime(): LocalDateTime = LocalDateTime.now()
}

/** Minutes after midnight at which a day-based reminder fires on [day], or null. */
fun minutesOn(r: Reminder, day: Long): Int? =
    com.werd.app.reminder.ReminderScheduler.occurrenceOn(r, java.time.LocalDate.ofEpochDay(day))
        ?.takeIf { it.toLocalDate().toEpochDay() == day }?.let { it.hour * 60 + it.minute }

/** Indirection so the data layer can poke widgets without depending on Glance directly. */
object WidgetBridge {
    @Volatile var refresher: ((Context) -> Unit)? = null
    fun refresh(context: Context) { refresher?.invoke(context) }
}
