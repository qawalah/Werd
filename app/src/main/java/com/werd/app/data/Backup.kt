package com.werd.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Plain JSON export / import of everything the user owns. */
object Backup {
    suspend fun export(dao: WerdDao): String {
        val o = JSONObject()
        o.put("app", "werd"); o.put("version", 1); o.put("exportedAt", System.currentTimeMillis())
        o.put("groups", JSONArray(dao.groups().map { g ->
            JSONObject().put("id", g.id).put("name", g.name).put("position", g.position).put("colorIndex", g.colorIndex)
        }))
        o.put("werds", JSONArray(dao.werds().map { w ->
            JSONObject().put("id", w.id).put("title", w.title).put("groupId", w.groupId ?: JSONObject.NULL)
                .put("position", w.position).put("colorIndex", w.colorIndex).put("goalDaysMask", w.goalDaysMask)
                .put("note", w.note).put("createdAt", w.createdAt).put("updatedAt", w.updatedAt).put("textOnly", w.textOnly)
        }))
        o.put("items", JSONArray(dao.allItems().map { i ->
            JSONObject().put("id", i.id).put("werdId", i.werdId).put("position", i.position).put("text", i.text).put("target", i.target)
        }))
        o.put("sessions", JSONArray(dao.allSessions().map { s ->
            JSONObject().put("id", s.id).put("werdId", s.werdId).put("day", s.day).put("counts", s.counts)
                .put("completed", s.completed).put("completedAt", s.completedAt ?: JSONObject.NULL).put("updatedAt", s.updatedAt)
        }))
        o.put("reminders", JSONArray(dao.reminders().map { r ->
            JSONObject().put("id", r.id).put("werdId", r.werdId).put("type", r.type.name).put("enabled", r.enabled)
                .put("hour", r.hour).put("minute", r.minute).put("daysMask", r.daysMask)
                .put("dateValue", r.dateValue ?: JSONObject.NULL).put("intervalDays", r.intervalDays)
                .put("lat", r.lat ?: JSONObject.NULL).put("lng", r.lng ?: JSONObject.NULL).put("radius", r.radius.toDouble())
                .put("placeName", r.placeName).put("onExit", r.onExit).put("ssid", r.ssid)
                .put("btAddress", r.btAddress).put("btName", r.btName).put("cooldownHours", r.cooldownHours)
                .put("skipIfDone", r.skipIfDone).put("prayer", r.prayer).put("offsetMin", r.offsetMin)
                .put("groupId", r.groupId ?: JSONObject.NULL).put("excludedWerds", r.excludedWerds)
        }))
        return o.toString(1)
    }

    suspend fun import(dao: WerdDao, json: String) {
        val o = JSONObject(json)
        require(o.optString("app") == "werd") { "Not a Werd backup" }
        fun JSONObject.optLongOrNull(k: String) = if (isNull(k) || !has(k)) null else getLong(k)
        fun JSONObject.optDoubleOrNull(k: String) = if (isNull(k) || !has(k)) null else getDouble(k)
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        fun <T> JSONArray.map(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }

        val groups = arr("groups").map { WerdGroup(it.getLong("id"), it.getString("name"), it.optInt("position"), it.optInt("colorIndex")) }
        val werds = arr("werds").map {
            Werd(it.getLong("id"), it.getString("title"), it.optLongOrNull("groupId"), it.optInt("position"),
                it.optInt("colorIndex"), it.optInt("goalDaysMask", ALL_DAYS), it.optString("note"),
                it.optLong("createdAt", System.currentTimeMillis()), it.optLong("updatedAt", System.currentTimeMillis()), it.optBoolean("textOnly", false))
        }
        val items = arr("items").map {
            WerdItem(it.getLong("id"), it.getLong("werdId"), it.optInt("position"), it.getString("text"), it.optInt("target", 1))
        }
        val sessions = arr("sessions").map {
            DaySession(it.getLong("id"), it.getLong("werdId"), it.getLong("day"), it.optString("counts"),
                it.optBoolean("completed"), it.optLongOrNull("completedAt"), it.optLong("updatedAt"))
        }
        val reminders = arr("reminders").map {
            Reminder(
                id = it.getLong("id"), werdId = it.optLongOrNull("werdId"), groupId = it.optLongOrNull("groupId"),
                excludedWerds = it.optString("excludedWerds"),
                type = runCatching { ReminderType.valueOf(it.getString("type")) }.getOrDefault(ReminderType.DAILY),
                enabled = it.optBoolean("enabled", true), hour = it.optInt("hour"), minute = it.optInt("minute"),
                daysMask = it.optInt("daysMask", ALL_DAYS), dateValue = it.optLongOrNull("dateValue"),
                intervalDays = it.optInt("intervalDays", 2), lat = it.optDoubleOrNull("lat"), lng = it.optDoubleOrNull("lng"),
                radius = it.optDouble("radius", 200.0).toFloat(), placeName = it.optString("placeName"),
                onExit = it.optBoolean("onExit"), ssid = it.optString("ssid"), btAddress = it.optString("btAddress"),
                btName = it.optString("btName"), cooldownHours = it.optInt("cooldownHours", 12),
                skipIfDone = it.optBoolean("skipIfDone", true),
                prayer = it.optInt("prayer", 4), offsetMin = it.optInt("offsetMin", 0),
            )
        }
        dao.replaceAll(groups, werds, items, sessions, reminders)
    }
}
