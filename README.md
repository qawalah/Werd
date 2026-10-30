# ورد · Werd

Android app (Kotlin + Jetpack Compose, minSdk 26 / Android 8+) that turns pasted supplication text into a tap-through tally.

## Build
Open the folder in Android Studio, or:

```
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # parser, prayer times, reminders, migration, AI client, sources, screenshots
```

Release signing reads the key from outside the repo: `WERD_KEYSTORE_FILE`, `WERD_KEYSTORE_PASSWORD`, `WERD_KEY_ALIAS`, `WERD_KEY_PASSWORD` (environment variables, or `werdKeystoreFile`… in `~/.gradle/gradle.properties`); see `app/build.gradle.kts`. Keep `werd-release.jks`: Android only installs an update over an existing install if it is signed with the same key.

Server: the app reads its address from `-PwerdServerUrl=https://…` at build time (or Settings → Library & family server).
Without one, Library and Family show "coming soon". The API is specified in `docs/server-api.md`; `app/src/test/java/com/werd/app/FakeWerdServer.kt`
is a complete in-memory implementation the tests run against.

Store-listing screens: `./gradlew testDebugUnitTest --tests com.werd.app.StoreShots` → `app/screenshots/store_*.png`.

## Where things live
| Area | File |
|---|---|
| Count detection from text / HTML import | `parse/WerdParser.kt` (tests in `WerdParserTest.kt`) |
| Database (awrad, lines, daily sessions, reminders, groups) | `data/` |
| Tally reader, focus mode, read-aloud marker | `ui/reader/ReaderScreen.kt`, `tts/TtsController.kt` |
| Reminders: time, prayer, date, every N days, place, Wi-Fi, Bluetooth | `reminder/`, `ui/reminders/` |
| Prayer-time maths (offline) | `util/PrayerTimes.kt` |
| Statistics, goals, achievements | `ui/stats/StatsScreen.kt` |
| Home-screen widgets (Next werd with tap counter, Today, Streak) | `widget/Widgets.kt` |
| AI (Gemini / Claude, user's own key) | `ai/AiClient.kt`, prompts in `ai/AiTasks.kt` |
| Sources: books → supplications (background, resumable), with AI or offline (`sources/OfflineExtract.kt`) | `sources/`, `ui/sources/` |
| Proofread mode (original page beside the extracted text) | `ui/sources/ProofreadScreen.kt` |
| Re-read a page range with another model / on the phone | `sources/Rescan.kt`, `SourceWorker.rescan`, `ui/sources/BookActions.kt` |
| Book files (.werdbook): export, share, import | `sources/BookFile.kt` |
| Library & family (phone side) — talks to the server in `docs/server-api.md` | `cloud/`, `ui/sources/LibraryUi.kt`, `ui/family/` |
| Offline text recognition (Tesseract, Arabic) | `ocr/` |
| Database schema history (for safe upgrades) | `app/schemas/` |
| Strings (English / Arabic) | `res/values/strings.xml`, `res/values-ar/strings.xml` |

Fonts: Amiri and Tajawal (SIL Open Font License).
