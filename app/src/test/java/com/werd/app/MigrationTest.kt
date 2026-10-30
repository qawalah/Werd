package com.werd.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.werd.app.data.WerdDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Users upgrading from 1.0–1.3 must keep every werd, count, streak and reminder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WerdDatabase::class.java)

    @Test fun v1ToV2KeepsData() {
        helper.createDatabase("mig.db", 1).apply {
            execSQL("INSERT INTO werd_groups (id, name, position, colorIndex) VALUES (1, 'المساء', 0, 0)")
            execSQL("INSERT INTO werds (id, title, groupId, position, colorIndex, goalDaysMask, note, createdAt, updatedAt) VALUES (7, 'راتب', 1, 0, 0, 127, '', 1, 1)")
            execSQL("INSERT INTO werd_items (id, werdId, position, text, target) VALUES (70, 7, 0, 'لا إله إلا الله', 41)")
            execSQL("INSERT INTO sessions (id, werdId, day, counts, completed, completedAt, updatedAt) VALUES (1, 7, 20000, '70:41', 1, 5, 5)")
            execSQL("""INSERT INTO reminders (id, werdId, type, enabled, hour, minute, daysMask, dateValue, intervalDays, lat, lng, radius,
                placeName, onExit, ssid, btAddress, btName, cooldownHours, skipIfDone, lastFired, prayer, offsetMin)
                VALUES (3, 7, 'PRAYER', 1, 6, 0, 127, NULL, 2, NULL, NULL, 200, '', 0, '', '', '', 12, 1, 0, 4, 20)""")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig.db", 2, true)
        db.query("SELECT werdId, groupId, excludedWerds, type, offsetMin FROM reminders WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0)); assertTrue(c.isNull(1)); assertEquals("", c.getString(2))
            assertEquals("PRAYER", c.getString(3)); assertEquals(20, c.getInt(4))
        }
        db.query("SELECT counts, completed FROM sessions WHERE werdId = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals("70:41", c.getString(0)) }
        db.query("SELECT target FROM werd_items WHERE id = 70").use { c -> assertTrue(c.moveToFirst()); assertEquals(41, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM sources").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        // a group reminder can now be stored
        db.execSQL("INSERT INTO reminders (werdId, groupId, excludedWerds, type, enabled, hour, minute, daysMask, intervalDays, radius, placeName, onExit, ssid, btAddress, btName, cooldownHours, skipIfDone, lastFired, prayer, offsetMin) VALUES (NULL, 1, '7', 'DAILY', 1, 19, 0, 127, 2, 200, '', 0, '', '', '', 12, 1, 0, 4, 0)")
        db.close()
    }

    @Test fun v2ToV3AddsProofreadFlag() {
        helper.createDatabase("mig3.db", 2).apply {
            execSQL("INSERT INTO sources (id, title, kind, path, pageCount, pagesDone, status, error, provider, model, createdAt) VALUES (1, 'كتاب', 'pdf', '/x', 10, 10, 'DONE', '', 'gemini', 'm', 1)")
            execSQL("INSERT INTO source_items (id, sourceId, position, page, title, text, count, occasion, occasionText, note, werdId) VALUES (5, 1, 0, 3, 't', 'سبحان الله', 33, 'after_prayer', '', '', NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig3.db", 3, true)
        db.query("SELECT text, count, checked FROM source_items WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("سبحان الله", c.getString(0)); assertEquals(33, c.getInt(1)); assertEquals(0, c.getInt(2))
        }
        db.close()
    }

    @Test fun v3ToV4AddsLibraryFieldsAndInbox() {
        helper.createDatabase("mig4.db", 3).apply {
            execSQL("INSERT INTO sources (id, title, kind, path, pageCount, pagesDone, status, error, provider, model, createdAt) VALUES (1, 'كتاب', 'pdf', '/x', 10, 10, 'DONE', '', 'gemini', 'm', 1)")
            execSQL("INSERT INTO source_items (id, sourceId, position, page, title, text, count, occasion, occasionText, note, werdId, checked) VALUES (5, 1, 0, 3, 't', 'سبحان الله', 33, 'after_prayer', '', '', NULL, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig4.db", 4, true)
        db.query("SELECT libraryId, author, rescanTo FROM sources WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("", c.getString(0)); assertEquals("", c.getString(1)); assertEquals(0, c.getInt(2))
        }
        db.query("SELECT checked FROM source_items WHERE id = 5").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        db.execSQL("INSERT INTO inbox (serverId, fromName, title, note, lines, sentAt) VALUES ('w1', 'أم محمد', 'ورد', '', '[]', 0)")
        db.close()
    }

    @Test fun v5ToV6AddsTextsIndexAndTwoReadings() {
        helper.createDatabase("mig6.db", 5).apply {
            execSQL("INSERT INTO werds (id, title, groupId, position, colorIndex, goalDaysMask, note, createdAt, updatedAt) VALUES (7, 'راتب', NULL, 0, 0, 127, '', 1, 1)")
            execSQL("INSERT INTO sources (id, title, kind, path, pageCount, pagesDone, status, error, provider, model, createdAt, author, libraryId, rescanFrom, rescanTo, rescanDone) VALUES (1, 'كتاب', 'pdf', '/x', 10, 10, 'DONE', '', 'offline', '', 1, '', '', 0, 0, 0)")
            execSQL("INSERT INTO source_items (id, sourceId, position, page, title, text, count, occasion, occasionText, note, werdId, checked) VALUES (5, 1, 0, 3, 't', 'سبحان الله', 33, 'after_prayer', '', '', NULL, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig6.db", 6, true)
        db.query("SELECT textOnly FROM werds WHERE id = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        db.query("SELECT indexJson, scanMode FROM sources WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals("", c.getString(0)); assertEquals(0, c.getInt(1)) }
        db.query("SELECT checked, textOnly, alt, diffWords FROM source_items WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals(0, c.getInt(1)); assertEquals("", c.getString(2)); assertEquals("", c.getString(3))
        }
        db.close()
    }

    @Test fun v6ToV7AddsSectionsReadingsAndProofreading() {
        helper.createDatabase("mig7.db", 6).apply {
            execSQL("INSERT INTO sources (id, title, kind, path, pageCount, pagesDone, status, error, provider, model, createdAt, author, libraryId, rescanFrom, rescanTo, rescanDone, indexJson, scanMode) VALUES (1, 'كتاب', 'pdf', '/x', 10, 10, 'DONE', '', 'offline', '', 1, '', '', 0, 0, 0, '', 1)")
            execSQL("INSERT INTO source_items (id, sourceId, position, page, title, text, count, occasion, occasionText, note, werdId, checked, textOnly, alt, diffWords) VALUES (5, 1, 0, 3, 't', 'سبحان الله', 33, 'after_prayer', '', '', NULL, 0, 0, 'سبحان الله', '1')")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig7.db", 7, true)
        db.query("SELECT checkReader, proofDone, proofTotal, proofNote, scanMode FROM sources WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("", c.getString(0)); assertEquals(0, c.getInt(1)); assertEquals(0, c.getInt(2)); assertEquals("", c.getString(3)); assertEquals(1, c.getInt(4))
        }
        db.query("SELECT section, footnote, region, reads, hints, alt, diffWords FROM source_items WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst()); (0..4).forEach { assertEquals("", c.getString(it)) }
            assertEquals("سبحان الله", c.getString(5)); assertEquals("1", c.getString(6))
        }
        db.close()
    }
}
