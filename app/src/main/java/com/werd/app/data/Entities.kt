package com.werd.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Bit mask of weekdays: bit 0 = Monday ... bit 6 = Sunday (java.time DayOfWeek.value - 1). */
const val ALL_DAYS = 0b1111111

@Entity(tableName = "werd_groups")
data class WerdGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    val colorIndex: Int = 0,
)

@Entity(tableName = "werds", indices = [Index("groupId")])
data class Werd(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val groupId: Long? = null,
    val position: Int = 0,
    val colorIndex: Int = 0,
    /** Days of the week this werd is a goal on. */
    val goalDaysMask: Int = ALL_DAYS,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** A text to read (a poem, a sermon, a supplication with no count): no tally, no goal, not in the statistics. */
    @ColumnInfo(defaultValue = "0") val textOnly: Boolean = false,
)

@Entity(
    tableName = "werd_items",
    foreignKeys = [ForeignKey(
        entity = Werd::class, parentColumns = ["id"], childColumns = ["werdId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("werdId")],
)
data class WerdItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val werdId: Long,
    val position: Int,
    val text: String,
    val target: Int,
)

/** Progress of one werd on one (logical) day. counts = "itemId:count,itemId:count". */
@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = Werd::class, parentColumns = ["id"], childColumns = ["werdId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["werdId", "day"], unique = true), Index("day")],
)
data class DaySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val werdId: Long,
    val day: Long,
    val counts: String = "",
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class ReminderType { DAILY, PRAYER, ONCE, INTERVAL, LOCATION, WIFI, BLUETOOTH;
    val isTime get() = this == DAILY || this == ONCE || this == INTERVAL || this == PRAYER
}

/** A reminder belongs to one werd, or to a group (then it covers every werd in it, minus [excludedWerds]). */
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(entity = Werd::class, parentColumns = ["id"], childColumns = ["werdId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WerdGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("werdId"), Index("groupId")],
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val werdId: Long? = null,
    val groupId: Long? = null,
    /** Group reminders: werd ids (comma separated) that opted out or have their own customised copy. */
    @ColumnInfo(defaultValue = "") val excludedWerds: String = "",
    val type: ReminderType = ReminderType.DAILY,
    val enabled: Boolean = true,
    val hour: Int = 6,
    val minute: Int = 0,
    val daysMask: Int = ALL_DAYS,
    /** ONCE: epoch millis of the reminder. INTERVAL: epoch day of the start date. */
    val dateValue: Long? = null,
    val intervalDays: Int = 2,
    val lat: Double? = null,
    val lng: Double? = null,
    val radius: Float = 200f,
    val placeName: String = "",
    val onExit: Boolean = false,
    val ssid: String = "",
    val btAddress: String = "",
    val btName: String = "",
    val cooldownHours: Int = 12,
    val skipIfDone: Boolean = true,
    val lastFired: Long = 0,
    /** PRAYER: ordinal of [com.werd.app.util.PrayerTimes.Prayer]. */
    val prayer: Int = 4,
    /** PRAYER: minutes after (negative = before) the prayer time. */
    val offsetMin: Int = 0,
)

fun Reminder.excludes(werdId: Long): Boolean =
    excludedWerds.split(',').any { it.trim().toLongOrNull() == werdId }

fun Reminder.withExcluded(werdId: Long, excluded: Boolean): Reminder {
    val ids = excludedWerds.split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
    if (excluded) ids += werdId else ids -= werdId
    return copy(excludedWerds = ids.sorted().joinToString(","))
}

/** A book / document the user added; supplications are extracted from it by AI. */
@Entity(tableName = "sources")
data class Source(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** pdf | images | text | imported (a shared book file: text only, no pages) */
    val kind: String,
    /** Copy of the original inside app storage (a file, or a folder of page images). */
    val path: String,
    val pageCount: Int = 0,
    val pagesDone: Int = 0,
    /** QUEUED | RUNNING | PAUSED | DONE | ERROR */
    val status: String = "QUEUED",
    val error: String = "",
    val provider: String = "",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** The book's author, if known (shown in the library). */
    @ColumnInfo(defaultValue = "") val author: String = "",
    /** Id in the shared library: set when downloaded from it or published to it. */
    @ColumnInfo(defaultValue = "") val libraryId: String = "",
    /** A re-read of pages [rescanFrom]..[rescanTo] is in progress; [rescanDone] pages finished. 0 = none. */
    @ColumnInfo(defaultValue = "0") val rescanFrom: Int = 0,
    @ColumnInfo(defaultValue = "0") val rescanTo: Int = 0,
    @ColumnInfo(defaultValue = "0") val rescanDone: Int = 0,
    /** The book's own table of contents (فهرس): JSON array of {"title", "page", "item"} — see [com.werd.app.sources.BookIndex]. */
    @ColumnInfo(defaultValue = "") val indexJson: String = "",
    /** Page reading: 0 = one pass, 1 = two passes (without, then with tashkeel), 2 = a second pass only for doubtful pages. */
    @ColumnInfo(defaultValue = "0") val scanMode: Int = 0,
    /** The second reader for a two-pass scan (see [com.werd.app.sources.Readers]); "" = the built-in reader. */
    @ColumnInfo(defaultValue = "") val checkReader: String = "",
    /** A proofreading run on the finished book ("Proofread with…"): [proofDone] of [proofTotal] steps; 0 = none. */
    @ColumnInfo(defaultValue = "0") val proofDone: Int = 0,
    @ColumnInfo(defaultValue = "0") val proofTotal: Int = 0,
    /** Why the last proofreading run stopped, or "". */
    @ColumnInfo(defaultValue = "") val proofNote: String = "",
)

/** A werd a family member sent, waiting to be added or dismissed. */
@Entity(tableName = "inbox", indices = [Index(value = ["serverId"], unique = true)])
data class InboxWerd(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val fromName: String,
    val title: String,
    val note: String = "",
    /** JSON array of {"text","count"}. */
    val lines: String,
    val sentAt: Long = 0,
    /** The werd's settings as sent (days, colour, reminders) — see CloudRepo.werdSettings. */
    @ColumnInfo(defaultValue = "") val settings: String = "",
)

/** One supplication found in a source. */
@Entity(
    tableName = "source_items",
    foreignKeys = [ForeignKey(entity = Source::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class SourceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val position: Int,
    val page: Int,
    val title: String,
    val text: String,
    val count: Int = 1,
    /** morning | evening | morning_evening | after_prayer | sleep | waking | friday | travel | home | food | mosque | distress | ramadan | general */
    val occasion: String = "general",
    /** The book's own words on when to say it. */
    val occasionText: String = "",
    /** Virtue / reference, if the book states one. */
    val note: String = "",
    /** The werd it was added to (no foreign key: the werd may be deleted later). */
    val werdId: Long? = null,
    /** Proofread against the page. */
    @ColumnInfo(defaultValue = "0") val checked: Boolean = false,
    /** No repetition count in the book: a text to read, not a tally. */
    @ColumnInfo(defaultValue = "0") val textOnly: Boolean = false,
    /** Two-pass scan: the other reading of this text (without tashkeel), when the two disagree. */
    @ColumnInfo(defaultValue = "") val alt: String = "",
    /** Two-pass scan: indexes of the words of [text] the other reading disagrees with (comma separated). */
    @ColumnInfo(defaultValue = "") val diffWords: String = "",
    /** The chapter or section it sits in (the page's running header, or a heading shared by many items). */
    @ColumnInfo(defaultValue = "") val section: String = "",
    /** The page's footnotes that belong to it (reference, explanation): shown folded, never read aloud. */
    @ColumnInfo(defaultValue = "") val footnote: String = "",
    /** Where it is on its page, as fractions of the page's height: "0.31-0.52" ("" = unknown). */
    @ColumnInfo(defaultValue = "") val region: String = "",
    /** The other readers' readings of it: JSON array of {"by", "text"} (see [com.werd.app.sources.Proof]). */
    @ColumnInfo(defaultValue = "") val reads: String = "",
    /** Suggestions from the proofreading helpers: JSON array of {"i" (word index), "s" (suggestion), "by"}. */
    @ColumnInfo(defaultValue = "") val hints: String = "",
)

val SourceItem.flagged: Boolean get() = diffWords.isNotBlank()
val SourceItem.diffSet: Set<Int> get() = diffWords.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

object Counts {
    fun parse(s: String): Map<Long, Int> {
        if (s.isBlank()) return emptyMap()
        val m = HashMap<Long, Int>()
        for (part in s.split(',')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val k = part.substring(0, i).toLongOrNull() ?: continue
            val v = part.substring(i + 1).toIntOrNull() ?: continue
            m[k] = v
        }
        return m
    }

    fun encode(m: Map<Long, Int>): String =
        m.entries.filter { it.value > 0 }.joinToString(",") { "${it.key}:${it.value}" }
}
