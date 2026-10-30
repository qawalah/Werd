package com.werd.app.sources

import com.werd.app.data.Repository
import com.werd.app.data.SourceItem
import com.werd.app.data.Werd
import com.werd.app.data.WerdDao
import com.werd.app.data.WerdGroup
import com.werd.app.data.WerdItem

/** Turns picked source supplications into werds, optionally filing them into the matching group. */
object SourceImport {

    /** Words that identify a group for each occasion (Arabic + English, matched without diacritics). */
    private val keywords = mapOf(
        "morning" to listOf("صباح", "morning"),
        "evening" to listOf("مساء", "evening"),
        "morning_evening" to listOf("صباح", "مساء", "morning", "evening"),
        "after_prayer" to listOf("صلاة", "الصلاة", "صلوات", "prayer", "salah", "salat"),
        "sleep" to listOf("نوم", "النوم", "sleep", "bed"),
        "waking" to listOf("استيقاظ", "الاستيقاظ", "wak"),
        "friday" to listOf("جمعة", "الجمعة", "friday", "jumu"),
        "travel" to listOf("سفر", "السفر", "travel"),
        "home" to listOf("منزل", "بيت", "home", "house"),
        "food" to listOf("طعام", "أكل", "الطعام", "food", "eat", "meal"),
        "mosque" to listOf("مسجد", "المسجد", "mosque", "masjid"),
        "distress" to listOf("كرب", "هم", "ضيق", "distress", "anxiety"),
        "ramadan" to listOf("رمضان", "صيام", "ramadan", "fast"),
    )

    private fun norm(s: String) = s.lowercase()
        .filter { it !in 'ً'..'ٟ' && it != 'ٰ' }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه')

    /** Best existing group for an occasion, or null. "morning_evening" prefers a group naming both. */
    fun matchGroup(occasion: String, groups: List<WerdGroup>): WerdGroup? {
        val keys = keywords[occasion] ?: return null
        val scored = groups.map { g ->
            val n = norm(g.name)
            g to keys.count { k -> n.contains(norm(k)) }
        }.filter { it.second > 0 }
        return scored.maxByOrNull { it.second }?.first
    }

    /** Most common specific occasion among items ("general" is ignored unless it's all there is). */
    fun dominantOccasion(items: List<SourceItem>): String =
        items.map { it.occasion }.filter { it != "general" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "general"

    data class Options(
        /** true: one werd holding all picked items; false: one werd per item. */
        val combine: Boolean,
        val title: String,
        /** File into the matching group (creating it when missing). */
        val autoGroup: Boolean,
        /** Used when [autoGroup] is off (null = ungrouped). */
        val groupId: Long?,
        /** Localised group names for occasions, used when a group has to be created. */
        val occasionNames: Map<String, String>,
    )

    /** Returns the ids of the werds created. */
    suspend fun add(repo: Repository, dao: WerdDao, picked: List<SourceItem>, o: Options): List<Long> {
        if (picked.isEmpty()) return emptyList()
        val groups = dao.groups().toMutableList()
        suspend fun groupFor(occasion: String): Long? {
            if (!o.autoGroup) return o.groupId
            if (occasion == "general") return o.groupId
            matchGroup(occasion, groups)?.let { return it.id }
            val name = o.occasionNames[occasion] ?: return o.groupId
            val id = repo.addGroup(name, (groups.size + 1) % 8)
            groups += WerdGroup(id = id, name = name)
            return id
        }

        val created = ArrayList<Long>()
        if (o.combine) {
            val gid = groupFor(dominantOccasion(picked))
            val id = repo.saveWerd(
                // all texts → a text to read; any tally among them keeps the counter
                Werd(title = o.title.ifBlank { picked.first().title }, groupId = gid, textOnly = picked.all { it.textOnly }),
                picked.mapIndexed { i, it -> WerdItem(werdId = 0, position = i, text = it.text, target = it.count) },
            )
            created += id
            dao.updateSourceItems(picked.map { it.copy(werdId = id) })
        } else {
            for (it in picked) {
                val id = repo.saveWerd(
                    Werd(title = it.title, groupId = groupFor(it.occasion), textOnly = it.textOnly),
                    listOf(WerdItem(werdId = 0, position = 0, text = it.text, target = it.count)),
                )
                created += id
                dao.updateSourceItem(it.copy(werdId = id))
            }
        }
        return created
    }
}
