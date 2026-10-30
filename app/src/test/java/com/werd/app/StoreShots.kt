package com.werd.app

import android.Manifest
import android.app.AlarmManager
import android.os.PowerManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.werd.app.data.Counts
import com.werd.app.data.DaySession
import com.werd.app.data.Reminder
import com.werd.app.data.ReminderType
import com.werd.app.data.Seed
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.sources.SourceImport
import com.werd.app.sources.Sources
import com.werd.app.tts.TtsController
import com.werd.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * Clean, realistic screens for the Play Store listing (app/screenshots/store_<lang>_*.png):
 * permissions granted, a few weeks of history, a book being read, read-aloud in progress.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreShots {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()
    private fun s(id: Int) = app.getString(id)
    private lateinit var lang: String

    @After fun tearDown() { TtsController.previewState = null; Sources.clientOverride = null }

    /** Stands in for Gemini/Claude: answers the scan and the line-splitting the way a model would. */
    private class FakeAi : com.werd.app.ai.AiClient {
        override val provider = "gemini"; override val model = "gemini-3.8-flash"
        override suspend fun generate(prompt: String, images: List<com.werd.app.ai.AiImage>, json: Boolean, maxTokens: Int) =
            if (!json) com.werd.app.ai.AiReply("سَيِّدُ الاسْتِغْفَارِ\n$SAYYID\n$HASBI (سَبْعَ مَرَّاتٍ)", false)
            else com.werd.app.ai.AiReply("""{"title":"سَيِّدُ الاسْتِغْفَارِ","lines":[{"text":"$SAYYID","count":1},{"text":"$HASBI","count":7}]}""", false)
        override suspend fun listModels() = listOf("gemini-3.8-flash")
    }

    companion object {
        const val SAYYID = "اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ، أَعُوذُ بِكَ مِنْ شَرِّ مَا صَنَعْتُ، أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ بِذَنْبِي فَاغْفِرْ لِي فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ"
        const val HASBI = "حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ"
    }

    private fun shot(n: String) {
        compose.waitForIdle(); Thread.sleep(500); compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/store_${lang}_$n.png")
    }
    private fun wait(t: String, sub: Boolean = true) =
        compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty() }
    private fun tab(id: Int) = compose.onNode(hasText(s(id)) and hasClickAction()).performClick()
    private fun back() = compose.onAllNodesWithContentDescription(s(R.string.back))[0].performClick()

    private fun grantEverything() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_CONNECT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    private fun seed(arabic: Boolean) = runBlocking {
        app.settings.update { it.copy(onboarded = true, language = if (arabic) "ar" else "en", prayerLat = 21.4225, prayerLng = 39.8262,
            prayerPlace = if (arabic) "مكة المكرمة" else "Makkah", aiKeyGemini = "store-demo", aiScan = true) }
        app.repo.seedIfNeeded(arabic); StoreDemo.replaceRatib(app)
        val dao = app.db.dao()
        val names = app.resources.getStringArray(R.array.occasion_names).let { n -> com.werd.app.ai.AiTasks.OCCASIONS.mapIndexed { i, o -> o to n[i] }.toMap() }

        // a book being read, with what it found so far
        val sid = dao.insertSource(Source(title = if (arabic) "حصن المسلم" else "Fortress of the Muslim", kind = Sources.KIND_IMAGES,
            path = bookPages().path, pageCount = 120, pagesDone = 120, status = "DONE", provider = "gemini", model = "gemini-3.8-flash"))
        val found = listOf(
            SourceItem(sourceId = sid, position = 0, page = 14, title = "دعاء الاستيقاظ من النوم", text = "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا بَعْدَ مَا أَمَاتَنَا وَإِلَيْهِ النُّشُورُ", occasion = "waking", occasionText = "عند الاستيقاظ من النوم", checked = true),
            SourceItem(sourceId = sid, position = 1, page = 31, title = "آية الكرسي", text = "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ۚ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ", occasion = "morning_evening", occasionText = "تقال في الصباح والمساء", note = "من قالها حين يصبح أجير من الجن حتى يمسي", checked = true),
            SourceItem(sourceId = sid, position = 2, page = 33, title = "سيد الاستغفار", text = "اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ، أَعُوذُ بِكَ مِنْ شَرِّ مَا صَنَعْتُ، أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ بِذَنْبِي فَاغْفِرْ لِي فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ", occasion = "morning_evening", note = "من قالها موقناً بها دخل الجنة"),
            SourceItem(sourceId = sid, position = 3, page = 33, title = "حسبي الله", text = "حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ", count = 7, occasion = "morning_evening", checked = true),
            SourceItem(sourceId = sid, position = 4, page = 35, title = "أصبحنا وأصبح الملك لله", text = "أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ", occasion = "morning"),
            SourceItem(sourceId = sid, position = 5, page = 36, title = "بسم الله الذي لا يضر", text = "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ وَهُوَ السَّمِيعُ الْعَلِيمُ", count = 3, occasion = "morning_evening"),
            SourceItem(sourceId = sid, position = 6, page = 52, title = "التسبيح دبر الصلاة", text = "سُبْحَانَ اللَّهِ", count = 33, occasion = "after_prayer", occasionText = "دبر كل صلاة مكتوبة"),
            SourceItem(sourceId = sid, position = 7, page = 60, title = "دعاء النوم", text = "بِاسْمِكَ رَبِّي وَضَعْتُ جَنْبِي، وَبِكَ أَرْفَعُهُ", occasion = "sleep"),
        )
        dao.insertSourceItems(found)
        // two of them already turned into awrad (auto-grouped)
        val items = dao.sourceItems(sid)
        SourceImport.add(app.repo, dao, items.filter { it.occasion == "morning_evening" || it.occasion == "morning" },
            SourceImport.Options(combine = true, title = names["morning"].orEmpty(), autoGroup = true, groupId = null, occasionNames = names))
        SourceImport.add(app.repo, dao, items.filter { it.occasion == "sleep" },
            SourceImport.Options(combine = true, title = names["sleep"].orEmpty(), autoGroup = true, groupId = null, occasionNames = names))

        // history: most days done for the last two months
        val werds = dao.werds()
        val today = app.repo.today()
        werds.forEach { dao.updateWerd(it.copy(createdAt = LocalDate.now().minusDays(70).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())) }
        werds.forEachIndexed { wi, w ->
            val its = dao.items(w.id)
            for (b in 1..65) {
                if ((b * (wi + 3)) % 11 == 0 || (b > 40 && b % 5 == 0)) continue
                val day = today - b
                val at = LocalDate.ofEpochDay(day).atTime(if (wi == 0) 19 else 5, 40).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                dao.upsertSession(DaySession(werdId = w.id, day = day, counts = Counts.encode(its.associate { it.id to it.target }),
                    completed = true, completedAt = at, updatedAt = at))
            }
        }
        val ratib = werds.first { it.title == StoreDemo.EVENING_TITLE }
        val rItems = dao.items(ratib.id)
        rItems.take(6).forEach { app.repo.increment(ratib.id, it.id, it.target) }
        app.repo.increment(ratib.id, rItems[6].id, 4)
        val tasbih = werds.first { it.title == Seed.TASBIH_TITLE }
        dao.items(tasbih.id).forEach { app.repo.increment(tasbih.id, it.id, it.target) }
        val morning = werds.firstOrNull { it.title == names["morning"] }
        morning?.let { m -> dao.items(m.id).forEach { app.repo.increment(m.id, it.id, it.target) } }

        // every kind of reminder
        dao.reminders().forEach { dao.setReminderEnabled(it.id, true) }
        morning?.let { dao.upsertReminder(Reminder(werdId = it.id, type = ReminderType.PRAYER, prayer = 0, offsetMin = 10)) }
        dao.upsertReminder(Reminder(werdId = ratib.id, type = ReminderType.LOCATION, lat = 21.4225, lng = 39.8262,
            placeName = if (arabic) "المسجد" else "The mosque", onExit = true))
        werds.firstOrNull { it.title == names["sleep"] }?.let {
            dao.upsertReminder(Reminder(werdId = it.id, type = ReminderType.WIFI, ssid = if (arabic) "واي فاي البيت" else "Home Wi-Fi"))
        }
        dao.upsertReminder(Reminder(werdId = tasbih.id, type = ReminderType.BLUETOOTH, btName = if (arabic) "السيارة" else "Car", btAddress = "00:11:22:33:44:55"))
    }

    /** A scanned-looking book page for the proofread view. */
    private fun bookPages(): java.io.File {
        val w = 1200; val h = 1700
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(0xFFF6F0E1.toInt())
        val font = androidx.core.content.res.ResourcesCompat.getFont(app, R.font.amiri_regular)
        val bold = androidx.core.content.res.ResourcesCompat.getFont(app, R.font.amiri_bold)
        val p = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2418.toInt(); typeface = font; textSize = 52f }
        fun block(text: String, y: Float, paint: android.text.TextPaint): Float {
            val l = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, w - 160)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
            c.save(); c.translate(80f, y); l.draw(c); c.restore(); return y + l.height + 40
        }
        val head = android.text.TextPaint(p).apply { typeface = bold; textSize = 60f; color = 0xFF7A1F1F.toInt() }
        val small = android.text.TextPaint(p).apply { textSize = 40f; color = 0xFF6B5B3E.toInt() }
        var y = 110f
        y = block("سيد الاستغفار", y, head)
        y = block("«اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ، أَعُوذُ بِكَ مِنْ شَرِّ مَا صَنَعْتُ، أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ بِذَنْبِي فَاغْفِرْ لِي فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ»", y, p)
        y = block("من قالها موقناً بها حين يمسي فمات من ليلته دخل الجنة", y, small)
        c.drawLine(80f, y, w - 80f, y, android.graphics.Paint().apply { color = 0xFFBFAE88.toInt(); strokeWidth = 3f }); y += 50
        y = block("«حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ»", y, p)
        block("سبع مرات", y, small)
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val d = java.io.File(Sources.dir(app), "store_book").apply { mkdirs() }
        (1..120).forEach { java.io.File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", it)).writeBytes(bytes) }
        return d
    }

    private fun tour() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(localeTag(app.settings.value.language)))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            wait(StoreDemo.EVENING_TITLE)
            shot("01_home")

            // read-aloud, mid-line: the marker on "حول"
            val line = runBlocking { app.db.dao().items(app.db.dao().werds().first { it.title == StoreDemo.EVENING_TITLE }.id)[6].text }
            val w0 = line.indexOf(StoreDemo.MARKED_WORD).coerceAtLeast(0)
            TtsController.previewState = TtsController.State(ready = true, speaking = true, index = 6, wordStart = w0, wordEnd = w0 + StoreDemo.MARKED_WORD.length)
            compose.onAllNodesWithText(StoreDemo.EVENING_TITLE)[0].performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(s(R.string.focus_mode)).fetchSemanticsNodes().isNotEmpty() }
            shot("02_reader")
            compose.onAllNodesWithContentDescription(s(R.string.focus_mode))[0].performClick()
            shot("03_focus")
            compose.onAllNodesWithContentDescription(s(R.string.list_mode))[0].performClick()
            TtsController.previewState = null
            back()

            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(s(R.string.new_werd)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription(s(R.string.new_werd))[0].performClick()
            wait(s(R.string.convert))
            compose.onAllNodes(hasSetTextAction())[1].performTextInput(
                "أذكار الصباح\nأصبحنا وأصبح الملك لله والحمد لله (مرة واحدة)\nقل هو الله أحد والمعوذتين ثلاثاً\n" +
                    "حسبي الله لا إله إلا هو عليه توكلت وهو رب العرش العظيم (٧)\nسبحان الله وبحمده ١٠٠ مرة\n" +
                    "اللهم صل وسلم على نبينا محمد ×10\nيا حي يا قيوم برحمتك أستغيث مرتين\nحم حم حم حم حم حم حم\nأستغفر الله وأتوب إليه\n(مائة مرة)")
            compose.onAllNodesWithText(s(R.string.paste))[0].performScrollTo()
            shot("04_paste")
            // convert on the phone for the screenshot (no real AI key here)
            compose.onAllNodesWithText(s(R.string.mode_offline))[0].performClick()
            compose.onNode(hasText(s(R.string.convert)) and hasClickAction()).performScrollTo().performClick()
            wait(s(R.string.auto_detected))
            shot("05_converted")
            compose.onAllNodesWithText(s(R.string.auto_detected), substring = true)[0].performScrollTo()
            shot("05b_converted_lines")
            back()
            compose.onAllNodesWithText(s(R.string.discard))[0].performClick()

            // AI scan of a photographed page (a stand-in model answers), then AI line-splitting
            app.settings.update { it.copy(aiScan = true) }
            Sources.clientOverride = FakeAi()
            com.werd.app.ui.PendingImport.media = android.net.Uri.fromFile(java.io.File(Sources.dir(app), "store_book/0033.jpg")) to "image/jpeg"
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(s(R.string.new_werd)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription(s(R.string.new_werd))[0].performClick()
            wait("الذُّنُوبَ")
            compose.onAllNodesWithText(s(R.string.mode_q))[0].performScrollTo()
            shot("12_ai_scan")
            compose.onNode(hasText(s(R.string.convert)) and hasClickAction()).performScrollTo().performClick()
            wait(s(R.string.auto_detected))
            compose.onAllNodesWithText(s(R.string.auto_detected), substring = true)[0].performScrollTo()
            shot("13_ai_lines")
            back()
            compose.onAllNodesWithText(s(R.string.discard))[0].performClick()
            Sources.clientOverride = null

            tab(R.string.tab_sources); wait(s(R.string.src_sub).take(8))
            shot("06_sources")
            compose.onAllNodesWithText(if (lang == "ar") "حصن المسلم" else "Fortress of the Muslim")[0].performClick()
            wait("سيد الاستغفار")
            compose.onAllNodes(isToggleable())[1].performClick()
            compose.onAllNodes(isToggleable())[2].performClick()
            shot("07_source_detail")
            // the add dialog never reports idle under Robolectric, so it is captured with a frozen clock
            compose.onAllNodesWithText(s(R.string.src_add_to_werds), useUnmergedTree = true)[0].performClick()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(1000)
            compose.onRoot().captureRoboImage("screenshots/store_${lang}_14_add_dialog.png")
            compose.onAllNodesWithText(s(R.string.cancel))[0].performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.mainClock.autoAdvance = true
            compose.onAllNodesWithText(app.getString(R.string.src_page, 33))[0].performClick()
            wait(s(R.string.proof_original))
            shot("08_proofread")
            back(); wait("سيد الاستغفار"); back()

            tab(R.string.tab_reminders); wait(s(R.string.reminders_sub))
            shot("09_reminders")
            compose.onAllNodesWithText(app.resources.getStringArray(R.array.prayer_names)[4], substring = true)[0].performClick()
            wait(s(R.string.remind_when))
            shot("15_rem_prayer")
            back(); wait(s(R.string.reminders_sub))
            run {   // scroll the reminders list (other screens' lists may still be in the tree)
                val target = hasText(if (lang == "ar") "واي فاي البيت" else "Home Wi-Fi", substring = true)
                val n = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
                for (i in 0 until n) if (runCatching { compose.onAllNodes(hasScrollAction())[i].performScrollToNode(target) }.isSuccess) break
            }
            shot("16_reminders_more")
            compose.onAllNodesWithText(if (lang == "ar") "واي فاي البيت" else "Home Wi-Fi", substring = true)[0].performClick()
            wait(s(R.string.remind_when))
            shot("17_rem_wifi")
            back(); wait(s(R.string.reminders_sub))
            tab(R.string.tab_stats); wait(s(R.string.total_recitations))
            shot("10_stats")
            compose.onNode(hasScrollAction()).performScrollToIndex(6)
            shot("18_goals")
            compose.onNode(hasScrollAction()).performScrollToIndex(12)
            shot("11_achievements")
            tab(R.string.tab_settings); wait(s(R.string.ai_title))
            compose.onAllNodesWithText(s(R.string.ai_scan))[0].performScrollTo()
            shot("19_ai_settings")
        }
    }

    @Test @Config(sdk = [35], qualifiers = "ar-w400dp-h880dp-xxhdpi")
    fun arabic() { lang = "ar"; grantEverything(); seed(true); tour() }

    @Test @Config(sdk = [35], qualifiers = "en-w400dp-h880dp-xxhdpi")
    fun english() { lang = "en"; grantEverything(); seed(false); tour() }
}
