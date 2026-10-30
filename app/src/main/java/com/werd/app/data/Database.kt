package com.werd.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WerdDao {
    // ---- werds
    @Query("SELECT * FROM werds ORDER BY position, id")
    fun werdsFlow(): Flow<List<Werd>>

    @Query("SELECT * FROM werds ORDER BY position, id")
    suspend fun werds(): List<Werd>

    @Query("SELECT * FROM werds WHERE id = :id")
    suspend fun werd(id: Long): Werd?

    @Insert
    suspend fun insertWerd(w: Werd): Long

    @Update
    suspend fun updateWerd(w: Werd)

    @Update
    suspend fun updateWerds(w: List<Werd>)

    @Query("DELETE FROM werds WHERE id = :id")
    suspend fun deleteWerdRow(id: Long)
    @Query("DELETE FROM werd_items WHERE werdId = :id") suspend fun deleteItemsOf(id: Long)
    @Query("DELETE FROM sessions WHERE werdId = :id") suspend fun deleteSessionsOf(id: Long)
    @Query("DELETE FROM reminders WHERE werdId = :id") suspend fun deleteRemindersOf(id: Long)

    @Transaction
    suspend fun deleteWerd(id: Long) {
        deleteItemsOf(id); deleteSessionsOf(id); deleteRemindersOf(id); deleteWerdRow(id)
    }

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM werds")
    suspend fun nextWerdPosition(): Int

    // ---- items
    @Query("SELECT * FROM werd_items ORDER BY werdId, position")
    fun allItemsFlow(): Flow<List<WerdItem>>

    @Query("SELECT * FROM werd_items ORDER BY werdId, position")
    suspend fun allItems(): List<WerdItem>

    @Query("SELECT * FROM werd_items WHERE werdId = :werdId ORDER BY position")
    suspend fun items(werdId: Long): List<WerdItem>

    @Upsert
    suspend fun upsertItems(items: List<WerdItem>): List<Long>

    @Query("DELETE FROM werd_items WHERE werdId = :werdId AND id NOT IN (:keep)")
    suspend fun deleteItemsExcept(werdId: Long, keep: List<Long>)

    // ---- groups
    @Query("SELECT * FROM werd_groups ORDER BY position, id")
    fun groupsFlow(): Flow<List<WerdGroup>>

    @Query("SELECT * FROM werd_groups ORDER BY position, id")
    suspend fun groups(): List<WerdGroup>

    @Insert
    suspend fun insertGroup(g: WerdGroup): Long

    @Update
    suspend fun updateGroups(g: List<WerdGroup>)

    @Query("UPDATE werds SET groupId = NULL WHERE groupId = :id")
    suspend fun ungroup(id: Long)

    @Query("DELETE FROM werd_groups WHERE id = :id")
    suspend fun deleteGroupRow(id: Long)

    @Transaction
    suspend fun deleteGroup(id: Long) {
        ungroup(id); deleteGroupRow(id)
    }

    // ---- sessions
    @Query("SELECT * FROM sessions")
    fun allSessionsFlow(): Flow<List<DaySession>>

    @Query("SELECT * FROM sessions")
    suspend fun allSessions(): List<DaySession>

    @Query("SELECT * FROM sessions WHERE werdId = :werdId AND day = :day")
    suspend fun session(werdId: Long, day: Long): DaySession?

    @Upsert
    suspend fun upsertSession(s: DaySession)

    @Query("DELETE FROM sessions WHERE werdId = :werdId AND day = :day")
    suspend fun deleteSession(werdId: Long, day: Long)

    // ---- reminders
    @Query("SELECT * FROM reminders ORDER BY werdId, id")
    fun remindersFlow(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders")
    suspend fun reminders(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: Long): Reminder?

    @Upsert
    suspend fun upsertReminder(r: Reminder): Long

    @Query("UPDATE reminders SET lastFired = :t WHERE id = :id")
    suspend fun setLastFired(id: Long, t: Long)

    @Query("UPDATE reminders SET enabled = :enabled WHERE id = :id")
    suspend fun setReminderEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("SELECT id FROM reminders WHERE werdId = :werdId")
    suspend fun reminderIdsFor(werdId: Long): List<Long>

    @Query("SELECT id FROM reminders WHERE groupId = :groupId")
    suspend fun reminderIdsForGroup(groupId: Long): List<Long>

    @Query("SELECT * FROM werds WHERE groupId = :groupId ORDER BY position, id")
    suspend fun werdsInGroup(groupId: Long): List<Werd>

    @Query("SELECT * FROM werd_groups WHERE id = :id")
    suspend fun group(id: Long): WerdGroup?

    // ---- sources
    @Query("SELECT * FROM sources ORDER BY createdAt DESC")
    fun sourcesFlow(): Flow<List<Source>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: Long): Source?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun sourceFlow(id: Long): Flow<Source?>

    @Insert suspend fun insertSource(s: Source): Long
    @Update suspend fun updateSource(s: Source)

    @Query("UPDATE sources SET pagesDone = :done, status = :status, error = :error WHERE id = :id")
    suspend fun setSourceProgress(id: Long, done: Int, status: String, error: String = "")

    @Query("UPDATE sources SET status = :status, error = :error WHERE id = :id")
    suspend fun setSourceStatus(id: Long, status: String, error: String = "")

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("SELECT * FROM source_items WHERE sourceId = :sourceId ORDER BY position")
    fun sourceItemsFlow(sourceId: Long): Flow<List<SourceItem>>

    @Query("SELECT * FROM source_items WHERE sourceId = :sourceId ORDER BY position")
    suspend fun sourceItems(sourceId: Long): List<SourceItem>

    @Query("SELECT sourceId AS id, COUNT(*) AS n FROM source_items GROUP BY sourceId")
    fun sourceItemCounts(): Flow<List<IdCount>>

    @Insert suspend fun insertSourceItems(items: List<SourceItem>)
    @Update suspend fun updateSourceItem(item: SourceItem)
    @Update suspend fun updateSourceItems(items: List<SourceItem>)

    @Query("DELETE FROM source_items WHERE id = :id")
    suspend fun deleteSourceItem(id: Long)

    @Query("UPDATE source_items SET checked = :checked WHERE id = :id")
    suspend fun setSourceItemChecked(id: Long, checked: Boolean)

    @Query("UPDATE source_items SET position = position + 1 WHERE sourceId = :sourceId AND position >= :from")
    suspend fun shiftSourceItems(sourceId: Long, from: Int)

    @Insert suspend fun insertSourceItem(item: SourceItem): Long

    @Query("UPDATE sources SET rescanFrom = :from, rescanTo = :to, rescanDone = :done, error = :error WHERE id = :id")
    suspend fun setRescan(id: Long, from: Int, to: Int, done: Int, error: String = "")

    @Query("UPDATE sources SET indexJson = :json WHERE id = :id")
    suspend fun setSourceIndex(id: Long, json: String)

    @Query("UPDATE sources SET scanMode = :mode WHERE id = :id")
    suspend fun setScanMode(id: Long, mode: Int)

    @Query("UPDATE sources SET checkReader = :reader WHERE id = :id")
    suspend fun setCheckReader(id: Long, reader: String)

    @Query("UPDATE sources SET proofDone = :done, proofTotal = :total, proofNote = :note WHERE id = :id")
    suspend fun setProof(id: Long, done: Int, total: Int, note: String = "")

    @Query("SELECT * FROM source_items WHERE id = :id")
    suspend fun sourceItem(id: Long): SourceItem?

    /** Two-pass scan: the item was checked (with its text as it now is), so its flag goes. */
    @Query("UPDATE source_items SET checked = 1, diffWords = '', alt = '', hints = '' WHERE id = :id")
    suspend fun resolveSourceItem(id: Long)

    @Query("UPDATE source_items SET textOnly = :textOnly WHERE id = :id")
    suspend fun setSourceItemTextOnly(id: Long, textOnly: Boolean)

    @Query("UPDATE sources SET libraryId = :libraryId, author = :author WHERE id = :id")
    suspend fun setLibraryId(id: Long, libraryId: String, author: String)

    @Query("SELECT * FROM sources WHERE libraryId = :libraryId LIMIT 1")
    suspend fun sourceByLibraryId(libraryId: String): Source?

    @Query("DELETE FROM source_items WHERE id IN (:ids)")
    suspend fun deleteSourceItems(ids: List<Long>)

    /** Applies a re-read of some pages in one go (see [com.werd.app.sources.Rescan.merge]). */
    @Transaction
    suspend fun replacePages(removed: List<Long>, updated: List<SourceItem>, inserted: List<SourceItem>) {
        if (removed.isNotEmpty()) deleteSourceItems(removed)
        if (updated.isNotEmpty()) updateSourceItems(updated)
        if (inserted.isNotEmpty()) insertSourceItems(inserted)
    }

    // ---- inbox (awrad sent by family)
    @Query("SELECT * FROM inbox ORDER BY sentAt") fun inboxFlow(): Flow<List<InboxWerd>>
    @Query("SELECT * FROM inbox ORDER BY sentAt") suspend fun inbox(): List<InboxWerd>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInbox(items: List<InboxWerd>): List<Long>
    @Query("DELETE FROM inbox WHERE id = :id") suspend fun deleteInbox(id: Long)

    /** Inserts [item] at its position, pushing later items down (for text the AI missed). */
    @Transaction
    suspend fun insertSourceItemAt(item: SourceItem): Long {
        shiftSourceItems(item.sourceId, item.position)
        return insertSourceItem(item)
    }

    // ---- backup restore
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreGroups(l: List<WerdGroup>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWerds(l: List<Werd>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreItems(l: List<WerdItem>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSessions(l: List<DaySession>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreReminders(l: List<Reminder>)
    @Query("DELETE FROM werds") suspend fun clearWerds()
    @Query("DELETE FROM werd_items") suspend fun clearItems()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Query("DELETE FROM werd_groups") suspend fun clearGroups()

    @Transaction
    suspend fun replaceAll(
        groups: List<WerdGroup>, werds: List<Werd>, items: List<WerdItem>,
        sessions: List<DaySession>, reminders: List<Reminder>,
    ) {
        clearSessions(); clearReminders(); clearItems(); clearWerds()
        clearGroups()
        restoreGroups(groups); restoreWerds(werds); restoreItems(items)
        restoreSessions(sessions); restoreReminders(reminders)
    }
}

data class IdCount(val id: Long, val n: Int)

@Database(
    entities = [WerdGroup::class, Werd::class, WerdItem::class, DaySession::class, Reminder::class, Source::class, SourceItem::class, InboxWerd::class],
    version = 7,
    exportSchema = true,
    // v1 → v2: reminders gain group targets (table rebuilt by Room), new sources tables. Existing data is kept.
    // v2 → v3: source items gain a proofread flag.
    // v3 → v4: books gain author / library id / rescan progress; inbox of awrad sent by family.
    // v5 → v6: reading-only texts, the book's index (فهرس), two-pass scan readings.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
abstract class WerdDatabase : RoomDatabase() {
    abstract fun dao(): WerdDao

    companion object {
        fun create(context: Context): WerdDatabase =
            Room.databaseBuilder(context, WerdDatabase::class.java, "werd.db")
                .addCallback(object : Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
