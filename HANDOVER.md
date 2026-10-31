# Werd (ورد) — handover

State at handover: **app 1.15 (versionCode 18) · server 1.8.0** (30 Oct 2026). Everything in this repo builds; build
outputs, APK/EXE files, the signing key and its passwords are deliberately left out (see *Secrets*).

## What it does

Werd turns supplication and dhikr text (awrad, ahzab, ratibs) into a tap-through tally counter for Arabic-speaking
Muslims, with reminders, statistics and read-aloud. It also reads whole printed books of adhkar (photos or PDFs) into
separate supplications, with OCR and AI, and has a proofreading workflow. An optional self-hosted **family server** adds a
shared library of books and awrad, family groups (parents and children, progress sharing, sending a werd to a child), AI
page reading on the server or on a helper PC's graphics card, and server-side voices.

Main features:
- **Awrad and the tally:** paste text, and lines and repeat counts are detected (`WerdParser`). The tally has a list view
  and a focus view with a misbaha counter (ring or pull, in four sizes: large, medium, slim or hidden with tap-the-text).
  Some items are "texts to read" with no counter at all.
- **Reminders:** by time, prayer time (calculated offline), date, every N days, place, Wi-Fi or Bluetooth. There are also
  goals, streaks, statistics and achievements, and three home-screen widgets.
- **Read-aloud:** a moving word marker and auto-count. Voices are the system TTS, on-phone Piper voices (sherpa-onnx), or
  the server's voices.
- **Sources (books):** a PDF, photos or text is turned into supplications.
  - **Readers:** the user's own Gemini or Claude key, the built-in Tesseract (Arabic), a vision model on the phone
    (llama.cpp), Gemini Nano, or the family server's models.
  - **Scanning:** two-pass scans with a second reader of the user's choice, and re-reads of a page range.
  - **Page layout:** headers, footers and footnotes are detected, and titles come from each item's own heading.
  - **Index:** the book's index (فهرس) is read and linked to its items.
  - **Proofreading:** "Proofread with…" runs other readers on a finished book, with an optional higher-resolution re-read.
    Helpers suggest corrections from the Quran text (offline), a look-alike-letter spelling check, and a small AI model.
    The proofreader shows the original page and an editing box, with alternatives offered for each questioned word.
- **Book files:** `.werdbook` files can be exported, shared and imported.
- **Library and family:** connects to the family server (`docs/server-api.md`).
  - **Library:** shared books and single awrad, public or family-only.
  - **Families:** roles, a 30-day history, and sending a werd with its settings.
  - **Accounts:** a name and password, so the same person can sign in again on a new phone.
- **Family server:** one Go program for Windows (exe or service) or Docker on a NAS.
  - **Pages:** a web dashboard (`/admin`), a home page and a PWA web app (`/app`).
  - **AI page reading:** llama.cpp vision models, with the owner's own models addable and a CUDA engine downloaded on demand.
  - **Voices:** Piper, the Chatterbox GPU voice, Gemini, Azure and ElevenLabs.
  - **Helper PC:** a Windows PC can help a NAS, woken by Wake-on-LAN.
  - **Accounts:** passwords, one-time sign-in codes and merging duplicate accounts.
  - **Dashboard:** GPU monitoring, cancellable tasks and an activity feed.

## Platform and type

| Part | What it is | Folder |
|---|---|---|
| **Android app** | Kotlin + Jetpack Compose, minSdk 26, compile/target SDK 36. One APK per ABI (arm64-v8a, armeabi-v7a). | `app/` |
| **Family server: Windows** | `werd-server.exe` is a console program (double-click to run). `werd-server.exe install` makes it a **Windows background service** (also `uninstall`, `start`, `stop`). No window or tray. | `server/` |
| **Family server: NAS** | The same Go program for Linux in **Docker** (amd64, arm64, armv7), run with `docker compose up -d` (Synology Container Manager → Project). | `server/` |
| Web pages | Dashboard, home page and PWA, embedded in the server binary. | `server/web/` |
| Python voice engine | `engine.py` (Chatterbox) is embedded in the server, which installs and runs it in its own Python. Optional, graphics-card PCs only. | `server/voiceengine/` |

## Identity

- **Android:** applicationId **`com.werd.app`**, **versionCode 18**, **versionName "1.15"** (`app/build.gradle.kts`).
  Signing certificate SHA-256: `13:f3:d0:dc:af:0a:0c:30:93:6a:75:76:86:8f:7a:68:b9:9b:c2:16:7e:c1:18:e6:41:69:5e:42:4d:f7:56:0a`.
- **Server:** Go module `github.com/werd/werd-server`, entry `server/main.go`, version **1.8.0**. The version is set at
  build time with `-ldflags "-X main.version=1.8.0"`; without it the server reports `dev`.

## How to build and run

### Android app (JDK 17, Android SDK 36; Gradle 8.14.3 wrapper, AGP 8.13.2, Kotlin 2.2.21)
```
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a}-release.apk
./gradlew :app:testDebugUnitTest   # unit, Robolectric and screenshot tests
```
- **SDK:** set `ANDROID_HOME`, or a `local.properties` with `sdk.dir=…` (not committed).
- **Signing:** release builds are signed only when the signing secrets are present (see *Secrets*); otherwise they come
  out unsigned.
- **Built-in server address (optional):** `-PwerdServerUrl=https://…`. When it's empty, users type the address in
  Settings → Library & family server.
- **Tests:**
  - Robolectric downloads its Android jars the first time. `-Probolectric.dir=/path` runs offline from local jars.
  - Screenshot tests (Roborazzi) write to `app/screenshots/`, which is git-ignored. They are slow (several minutes);
    `V114Shots` and `V115Shots` are the latest tours.
  - `RealServerTest` runs only with `-PwerdTestServer=http://127.0.0.1:8080` against a running server. The server limits
    sign-ups to 20 an hour per address, so start it with a fresh data folder before repeated runs.
- **Prebuilt binaries in the repo:**
  - `app/libs/sherpa-onnx-1.13.8.aar` (50 MB; on-phone Piper voices; it isn't on Maven Central).
  - `app/src/main/jniLibs/arm64-v8a/libllamasrv.so` and `libllamasrv_dot.so` (llama.cpp's `llama-server` cross-compiled
    for Android, plain and dot-product builds; Android only runs it because it's packaged as a "library"). The exact
    llama.cpp commit wasn't recorded.
  - `app/src/main/assets/tessdata/ara.traineddata` (Tesseract Arabic).
  - `app/src/main/assets/quran.txt` (Quran text, see *Decisions*).
  - Consider Git LFS for the `.aar` and the `.so` files.
- **Strings:** Arabic and English only, in `app/src/main/res/values/strings.xml` and `values-ar/strings.xml`. Add every
  new string to both.
- **Database:** Room 2.8 at **version 7**, with exported schemas in `app/schemas/` (auto-migrations 1→7, tested in
  `MigrationTest`). Add an `AutoMigration` and a test for every schema change; never use destructive migration.

### Server (Go 1.26)
```
cd server
go test .                          # about 25 s
./build.sh                         # tests, then werd-server.exe (+arm64) and linux amd64/arm64/armv7 into dist/
```
- **Running:** run `go run .` or the exe. It listens on `:8080`. Data goes in `./data` next to the program, in
  `%ProgramData%\Werd Server` on Windows, or wherever `-data` or `WERD_DATA` points. On first start it prints the admin
  password and saves it to `data/admin-token.txt`.
- **Options:** `-addr` / `WERD_ADDR`; `-domain` / `WERD_DOMAIN` (automatic HTTPS from Let's Encrypt on 80/443);
  `-urls` / `WERD_URLS` (extra addresses phones may use, e.g. Tailscale); `-tls-cert`/`-tls-key`; `-trust-proxy`;
  `-admin-token` / `WERD_ADMIN_TOKEN`.
- **Windows icon and version info:** these come from `rsrc_windows_{386,amd64,arm64}.syso` (committed). Regenerate them
  whenever the version changes:
  `go install github.com/tc-hib/go-winres@latest`, then
  `go-winres make --in winres/winres.json --arch amd64,arm64,386`.
- **Version bump:** change `version` in `devstore.json`, `build.sh` (`VERSION`) and `winres/winres.json` (then
  regenerate the `.syso` files). The `Dockerfile` and `docker-compose.yml` carry no version: DevStore labels the image.
- **Release packages:** `server/dist/` is git-ignored and holds third-party engines that aren't in the repo.
  - **Windows zip:** `werd-server.exe` + `README.md` + a `llama/` folder (the llama.cpp **CPU** build for Windows:
    `llama-server.exe` and its DLLs, added by `scripts/prepare-llama.ps1`).
  - **Docker image:** built on GitHub by DevStore's `docker-release.yml` from `server/Dockerfile`: the server from
    source, plus the llama.cpp **b11800** Linux CPU build (`version: 0.7.0-dev (build 11800, commit d454a204e)`),
    fetched with a SHA-256 check and run once at build time. It's published privately to
    `ghcr.io/qawalah/werd-server` (`:<version>` and `:latest`, tag `docker-v<version>`), Intel/AMD only. The NAS
    zip (`Werd Server for NAS/`) and the ARM images are no longer made.
  - **CUDA engine:** not bundled. The server downloads llama.cpp **b11800** `cuda-12.4` on demand (`gpu.go`).

### Manual steps that remain
- **Signing key:** get `werd-release.jks` and its passwords into CI secrets (see below).
- **Engines:** none by hand any more. Windows gets its engine from `scripts/prepare-llama.ps1` and Docker from
  `server/Dockerfile`; both must match `llamaBuild` in `gpu.go`.
- **Release testing:** done by hand so far. The owner installs the APK on his phone and runs the server on Windows (the
  main server now) and in Docker on a Synology NAS.

## Where data lives and what it talks to

**App (on the phone):**
- **Database:** Room `werd.db` (awrad, lines, daily sessions, reminders, groups, sources and their items, inbox).
- **SharedPreferences:**
  - `werd_settings`: settings, including the user's **Gemini and Claude API keys**.
  - `werd_cloud`: server addresses, user id, **account token**, family.
  - `ocr`: the chosen reader.
  - `werd_voice` and `voice_profiles`: voice choices.
- **Files:**
  - `filesDir/sources/`: copies of imported books (PDFs, page images, text).
  - `getExternalFilesDir()/models/`: phone AI models (GGUF, 1–3 GB each).
  - Downloaded Piper voices.
- **Backups:** export and import through the system file picker (`data/Backup.kt`).
- **External services:**
  - Gemini API (`generativelanguage.googleapis.com`) and Anthropic API, using the user's key.
  - Gemini Nano through Android AICore / ML Kit, on supported phones only.
  - Hugging Face (phone model downloads) and Piper voice downloads.
  - OpenStreetMap tiles (osmdroid map for place reminders) and Google Play services location.
  - The family server.

**Server (in its data folder):**
- **Database:** `werd.db` (SQLite: users and sessions, families, books, shared awrad, inbox, progress, reports, activity,
  settings, TTS clips and voices).
- **Admin password:** `admin-token.txt`.
- **HTTPS:** `certs/` (Let's Encrypt).
- **Models:** `models/<id>/` (vision models) and `models/custom.json` (models the owner added).
- **CUDA engine:** `engine-cuda/` (llama.cpp CUDA build, downloaded).
- **Voices:** `voices/` (Piper, plus `voices/gpu/` for the Chatterbox Python environment, about 6 GB).
- **Logs:** `llama-server.log` (the AI engine's last start-up log, which is read to check that it really used the GPU).
- **Ports:** HTTP **8080** (default), or **80/443** with a domain. llama-server runs on a random `127.0.0.1` port.
- **Helper PC:** connects out to the main server (long-poll) with a key. The main server wakes it with a Wake-on-LAN
  magic packet.
- **External services:**
  - Downloads: Hugging Face (models), GitHub releases (llama.cpp CUDA, Piper voices), the PyTorch wheel index and PyPI
    (via uv, Chatterbox only).
  - Optional TTS providers: Gemini, Azure Speech, ElevenLabs.
  - Let's Encrypt.
  - `nvidia-smi` on the local machine for GPU figures.

## Secrets (names only)

| Secret | Used for | How it's provided |
|---|---|---|
| `WERD_KEYSTORE_FILE` | Path to `werd-release.jks` (the Android release key; alias `werd`) | **New in this handover:** an environment variable, or `werdKeystoreFile` in `~/.gradle/gradle.properties`. The default is `app/werd-release.jks`, which is git-ignored. For CI, store the file as a base64 secret (e.g. `WERD_KEYSTORE_BASE64`) and decode it to a file before building. |
| `WERD_KEYSTORE_PASSWORD` | Keystore password | Environment variable or `werdKeystorePassword`. *Before this handover it was written in `app/build.gradle.kts`; that is removed. Treat the old value as known to anyone with the old code.* |
| `WERD_KEY_ALIAS` | Key alias (default `werd`) | Environment variable or `werdKeyAlias` |
| `WERD_KEY_PASSWORD` | Key password (defaults to the keystore password) | Environment variable or `werdKeyPassword` |
| Gemini / Claude API keys | The app's AI features | Typed by each user in the app's Settings, and stored on the phone. Never built in. |
| `WERD_ADMIN_TOKEN` | Server dashboard password | Optional environment variable or flag. Otherwise generated on first start into `data/admin-token.txt`. |
| Server TTS keys (Gemini, Azure, ElevenLabs) | Server voices | Typed in the dashboard and stored in the server's database. |
| Helper-PC key | The PC helping the NAS | Generated on the dashboard and pasted into the PC's dashboard. |

**Never make a new signing key.** Android only installs an update over the existing app when it is signed with the same
key, so a new key means every user has to uninstall and lose their data. Keep `werd-release.jks` backed up outside
GitHub.

## Known bugs and unfinished work

- **Tests that don't pass, not diagnosed:**
  - `CloudTest`: the https-guessed-on-a-plain-http-server tests hang in the TLS handshake.
  - `CloudTest.forgottenAccount…` and `addressWithoutSchemeWorks` fail. They look related to `BuildConfig.SERVER_URL`
    being empty in tests.
  - `VoicePickerTest.voices` times out.
  - None of these was confirmed as old or new. They predate 1.15 and sit in code it didn't change.
- **Screenshot tour:** while the "Add a password" dialog is open, `V115Shots` never goes idle (Compose doesn't settle).
  The same dialog settles fine on its own (`AccountDialogTest`), so it was dropped from the tour. It may hide a recomposition
  loop on a real phone; worth checking.
- **Server test flake:** one full `go test` run failed once and then passed five times; the failing test wasn't
  identified.
- **Never run on real hardware:**
  - the GPU-fallback warning and the GPU tile with a real card
  - adding a custom model
  - "Proofread with…" with the higher-resolution re-read and the small-model helper
  - Wake-on-LAN
  - the Chatterbox GPU voice
  - cancelling a page read on a real PC or NAS
  - reading a real فهرس by OCR
- **Proofreading gaps:**
  - When a word is taken from a reader that reads without tashkeel, it comes in without tashkeel.
  - A single line of a page can't be marked as header or footer by hand. Each item's section and footnote are editable
    instead.
  - The known-text helper only has the Quran. Hadith and adhkar collections (Hisn al-Muslim etc.) aren't included.
- **Quran text credit:** it comes from The Noble Qur'an Encyclopedia (quranenc.com) via the npm package `quran-json` 3.1.2,
  under **CC BY 4.0**. The credit is in the header of `app/src/main/assets/quran.txt` but is **not yet shown in the app**
  (add it to an About or licences screen).
- **Ba 'Alawi library:** importing it (from the BaAlwi APK: texts, sections and recordings) was deferred. Its licence
  hasn't been checked.
- **Accounts:** accounts made before server 1.8 have no password until the user adds one. Duplicates are merged by hand on
  the dashboard (Families → "Same name, more than one account").
- **Next ideas:** everything open is in **`ROADMAP.md`**. Its status line at the top says what 1.15 / 1.8.0 completed.

## Decisions to keep (and why)

- **One signing key, forever** (see *Secrets*). **One APK per ABI** (arm64 + armv7), because the native engines are large
  and a phone only needs its own. No minify, which keeps reflection-based libraries (sherpa-onnx, ML Kit) safe.
- **Arabic-first, two languages.** Numbers are formatted with `%s` so counters always show Western digits, which is why
  lint's `StringFormatMatches` is disabled.
- **Room auto-migrations with exported schemas.** The user's tallies, history and books must survive every update.
- **Nothing the AI or a helper produces silently changes text.**
  - Readings and helper suggestions only mark words; the user chooses.
  - A later reading never unticks an item the user ticked.
  - Uthmani spellings that differ only by the rasm (الصلوة/الصلاة) aren't suggested.
  - Footnote marks become ⁽١⁾ so that "(٣)" stays a repeat count.
  - A heading is only cut from the start of a text when it looks like a heading (دعاء/باب/ذكر…, or ends with ":"), never
    the first words of a supplication.
- **Texts with no tally** (`textOnly`) stay out of statistics, goals and streaks.
- **Privacy:**
  - A werd shared to the library carries place, Wi-Fi and Bluetooth reminders only when the sender ticks them.
  - Progress is never shared publicly.
  - Server passwords are PBKDF2-hashed, and each phone has its own session token.
- **Accounts stay backward-compatible.** Old apps and accounts without a password keep working. Names are unique only
  among password accounts, and are compared without case, tashkeel or alef forms (`loginKey`). Signing out is refused
  for an account without a password, because it could never be reached again.
- **Cancelling reaches the server.** A phone cancel stops the server's (or helper PC's) work. A cancelled page answers
  `409 cancelled`, and the book goes to PAUSED, not ERROR.
- **One server program for every setup.** The Windows exe or service and the Docker image on the NAS are the same code.
  - **Docker:** the NAS uses a single, default-named `docker-compose.yml` that pulls the private image
    `ghcr.io/qawalah/werd-server:latest`, built on GitHub by DevStore. Watchtower on the NAS installs new versions; the
    NAS setup is DevStore's `docs/nas-setup.md`.
  - **Database:** SQLite with one connection, which avoids "database is locked" and is plenty for a family.
- **Addresses:** plain HTTP is allowed for home and Tailscale addresses. The app tries every known address of the server
  and corrects https/http itself.
- **Helper PC:** the NAS is the main server and a Windows PC with an RTX 3060 Ti helps, preferred whenever it's awake and
  woken by Wake-on-LAN.
  - **CUDA engine:** downloaded on demand rather than bundled (660 MB). It needs NVIDIA driver 551 or newer; with an older
    driver it falls back to the CPU, which the dashboard now detects and explains.
  - **Shared card:** the voice engine and the reader take turns on the card.
- **Dashboard order:** library and people first, then resources, then work, then activity, as the owner asked.

## Repository map

- `app/`: the Android app. Read `README.md` for a file-by-file map, `DESIGN.md` for visual and interaction rules, and
  `PRODUCT.md` for who it's for.
- `server/`: the family server (Go), its web pages (`web/`), the Windows resources (`winres/`) and its own `README.md`
  (install and use for the owner), `DESIGN.md` and `PRODUCT.md`.
- `docs/server-api.md`: the HTTP API between app and server. `app/src/test/.../FakeWerdServer.kt` is an in-memory version
  of it for tests.
- `ROADMAP.md`: done and open work.
