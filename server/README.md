# Werd Server · خادم ورد

The shared **library** and **family** features of the Werd app run on this server. Everything else in the app works without it.
One program, two ways to run it: **Windows** (`werd-server.exe`) or **Docker** on a NAS.

> **بالعربية باختصار:** شغّل `werd-server.exe` على حاسوب ويندوز، أو ضع ملف `docker-compose.yml` في مجلد على جهاز NAS وأنشئ منه مشروعاً في Container Manager (الصورة جاهزة على ghcr.io، ويثبّت Watchtower الإصدارات الجديدة تلقائياً).
> ثم في التطبيق: الإعدادات ← خادم المكتبة والعائلة ← اكتب «عنوان البيت» (مثل `192.168.1.20:8080`، بدون https) و«عنوان Tailscale» إن وُجد (مثل `100.101.102.103:8080`) واضغط «حفظ واختبار».
> يتنقّل التطبيق بين العنوانين تلقائياً: في البيت يستخدم عنوان البيت، وخارجه يستخدم Tailscale.
> كلمة مرور لوحة الإدارة في ملف `admin-token.txt` داخل مجلد البيانات. انسخ مجلد البيانات احتياطياً.

---

## Windows

1. Put `werd-server.exe` in a folder of your choice and double-click it.
   (On ARM Windows laptops use `werd-server-arm64.exe`.)
2. If Windows asks whether to allow network access, choose **Allow** (Private networks).
3. The window shows the address to use, for example `http://192.168.1.20:8080`, and the admin password.
   The server runs while the window is open.

**Run it in the background and start it with Windows:** open *Command Prompt as administrator* in the folder and run

```
werd-server.exe install
```

This installs the **Werd Server** service (restarts itself if it ever stops) and opens port 8080 in the Windows firewall.
Other commands: `werd-server.exe status | stop | start | uninstall`. Uninstalling keeps your data.

Data lives in `C:\ProgramData\Werd Server` (database `werd.db`, `admin-token.txt`, and `werd-server.log` when running as a service).

> Windows SmartScreen may warn that the program is unrecognised (it isn't code-signed). Choose *More info → Run anyway*.

## NAS / Docker

The image is built on GitHub for every new server version and published privately to `ghcr.io/qawalah/werd-server`
(Intel/AMD NAS only). The NAS needs just `docker-compose.yml`, and Watchtower installs new versions by itself.

**Once per NAS:** a read-only GitHub token, `docker login ghcr.io` and Watchtower. Follow `docs/nas-setup.md` in
DevStore (`qawalah/devstore`). It also covers moving an existing NAS install (the old folder that built the image on
the NAS) without losing data.

### Synology (Container Manager)

1. In File Station, create a folder such as `/docker/werd` and put `docker-compose.yml` in it.
2. Container Manager → **Project → Create** → name `werd`, path `/docker/werd`, it finds `docker-compose.yml` → Next → Done.
   It downloads the image and starts the server.
3. Open `http://<NAS address>:8080` in a browser — you should see “Server is running”.
   The admin password is in `/docker/werd/data/admin-token.txt`.

**Tailscale:** in `docker-compose.yml`, remove the `#` before `WERD_URLS` and put the NAS's home and Tailscale
addresses there (the Tailscale one is shown in the Tailscale app/admin console, it starts with `100.`). Phones then
learn both and switch by themselves. Rebuild/restart the project after editing.

### Any other Intel/AMD Docker host (command line)

```
cd /volume1/docker/werd            # the folder with docker-compose.yml
docker compose up -d
docker compose logs werd           # shows the admin password on first start
```

The database and admin password are in `./data` inside that folder — include it in your NAS backups.
If port 8080 is taken, set `WERD_ADDR=:8090` (or another port) in `docker-compose.yml`.

**Errors?**
- *“pull access denied”*, *“unauthorized”* or *“denied”* — the NAS isn't logged in to ghcr.io, or its token expired:
  see the NAS guide.
- *“no matching manifest”* — the NAS has an ARM processor; the image is for Intel/AMD only. Use the Windows version on a
  PC instead.

## Connect the app

In Werd (1.7.2+): **Settings → Library & family server**

- **Home address**: the address shown by the server, e.g. `192.168.1.20:8080`. No `https://` — the server speaks plain
  http unless you set up HTTPS (the app fixes a wrong `https://` by itself anyway).
- **Tailscale address** (optional): e.g. `100.101.102.103:8080` or `http://nas.tail1234.ts.net:8080`.
- Tap **Save and test**. Each address gets ✓ or ✗ with the reason.

The app then uses whichever address answers — the home one on your Wi-Fi, the Tailscale one elsewhere — and remembers
the last one that worked. The server also tells the app its own addresses (Windows: found by itself; Docker: from
`WERD_URLS`), so other family phones only need one address that works once.

### Can't connect?

| The app says | What to do |
|---|---|
| *no answer* | Wrong address, or the phone isn't on the home Wi-Fi (turn on Tailscale). On a NAS, allow port 8080 in its firewall (Synology: *Control Panel → Security → Firewall*). Test from the phone's browser: `http://<address>:8080` should show “Server is running”. |
| *refused* | The server isn't running, or a different port. Check Container Manager / the Windows window. |
| *HTTPS failed* | You typed `https://`; type the address without it. |
| *phone blocked plain http* | Install Werd 1.7.1 or later (1.7 blocked plain http). |
| *isn't the Werd server* | The port is another program's (e.g. 5000 is DSM). Use the port from `docker-compose.yml` (8080). |

**Tailscale:** install it on the NAS/PC and on each phone, signed in to the same account (or share the machine with
family accounts). The NAS's Tailscale address starts with `100.`. On a Synology, the Tailscale package works with the
Docker port mapping as is — `http://100.x.y.z:8080`.

## Using it outside home (HTTPS)

At home, plain `http://` on your Wi-Fi is fine, and so is Tailscale (it encrypts by itself). For a public internet
address, use **https** — pick one:

| Option | Good for | How |
|---|---|---|
| **Tailscale** (or ZeroTier) | a family, no port forwarding | See above — no HTTPS setup needed. |
| **NAS reverse proxy** | Synology/QNAP with a domain or DDNS | Synology: *Control Panel → Login Portal → Advanced → Reverse Proxy*: `https://werd.yourname.synology.me:443` → `http://localhost:8080`; get a Let's Encrypt certificate under *Security → Certificate*. Set `WERD_TRUST_PROXY=true` in `docker-compose.yml`. |
| **Built-in HTTPS** | a PC/server with its own domain | Forward ports 80 and 443 to it and start with `-domain werd.example.com` (Windows: `werd-server.exe install -domain werd.example.com`). Certificates come from Let's Encrypt automatically. |
| **Cloudflare Tunnel** | no open ports at all | Point a tunnel at `http://localhost:8080`. Set `WERD_TRUST_PROXY=true`. |

## Werd on the web

The server also serves the whole app for browsers at **`http://<server>:8080/app/`**, for computers, iPhones or anyone
without the Android app:

- awrad with per-line tally, focus mode with adjustable text, read-aloud (the browser's voice or the family server's
  voice, repeating each line its count), paste-to-split with automatic repetition counts, stats and streaks;
- the shared library (filter by madhhab, add a book as one werd or one per occasion) and the family (create/join,
  roles, send a werd, each member's history, inbox);
- data is kept in that browser; Settings → Backup exports/imports it as a file.

On https (reverse proxy, Tailscale HTTPS, `-domain`) it can be installed as an app and works offline; on plain http
it works in the browser normally.

## Voices (read-aloud for the family)

The server can read awrad aloud in a natural voice for every phone, and keeps each clip in `data/tts` — a shared
sound library: a line read on one phone is ready instantly on the next, and is only ever paid for once.

1. Open the admin page → **Voices**. Paste a key for one or more of: **Gemini** (Google AI Studio key), **Azure Speech**
   (key + region, e.g. `uaenorth`), **ElevenLabs**. Pick the default voice, tap **Test**, then **Save**.
2. In the app: Settings → Voice → **Family server voice**. Phones may pick any voice the server offers.

The keys can also be given once as `WERD_GEMINI_KEY`, `WERD_AZURE_KEY`, `WERD_AZURE_REGION`, `WERD_ELEVEN_KEY`
(see `docker-compose.yml`). Clips are kept until you clear them on the admin page; include `data/tts` in backups if you
want to keep them.

### Voices with no key (made on the NAS or on your PC)

Settings → Voices → **Voices on this machine**:

- **Piper voices** (Kareem, Miro, Dii): light Arabic voices that run on the NAS's processor, no internet once installed.
  Press **Install** (about 70 MB per voice, plus the engine once, 300–400 MB to download). They need the server on Linux
  or Docker; the Windows server offers the graphics-card voice instead.
- **Graphics-card voice (Chatterbox)**, on a Windows PC with an NVIDIA card: Resemble AI's Chatterbox Multilingual
  (MIT licence), much more natural. **Install** sets up its own Python, PyTorch for CUDA and the model in the data folder
  (about 6 GB, 10–30 minutes). It reads as an Arabic narrator, or in the voice of a **voice sample**: add a clear 5–15 s
  recording on the same page (any audio file; the page converts it). Use recordings whose speakers agree to it.

These voices appear on phones like the others ("server" or "your PC"). Every line is kept, so lines made once still play
when the PC is asleep.

## A PC that helps the NAS

The NAS stays the family server (always on, all the data). A stronger PC running the Windows server can do the heavy
work while it is awake: page reading on its graphics card and the graphics-card voice.

1. On the NAS dashboard: Settings → **PC helper** → *Let a PC help this server*. Copy the key.
2. On the PC's dashboard (`http://localhost:8080/admin`): Settings → **PC helper** → *Help another server*: paste the NAS
   address (both the home and Tailscale ones, separated by a comma) and the key, tick *Help that server*, Save.
3. On the PC: Settings → AI page reading → **Read on the graphics card** (downloads the CUDA build of llama.cpp, 660 MB),
   download a reading model there, and install the graphics-card voice if you want it.

The PC asks the NAS for work, so Windows needs no open port or firewall rule. The NAS dashboard shows the PC (awake or
asleep, processor, graphics card and its memory, what it is doing), and its jobs in **Work**.

- **When the PC is asleep:** pages are read on the NAS (if it has a model), and phones use their own voice for lines the PC
  hasn't made yet. With *Wake the PC when it's needed* on, the NAS wakes it with Wake-on-LAN for a page it can't read
  itself, or for a whole book's audio in the PC's voice, and waits for it. While it works, the PC stays awake; when the
  work is done, Windows sleeps as usual.
- **Wake-on-LAN** must be on in the PC's BIOS and network-card settings. The NAS learns the PC's network card by itself
  after the first connection. In Docker, waking needs `network_mode: host`, which `docker-compose.yml` sets from 1.6.
- *Send page reading to the PC whenever it is awake* makes the NAS prefer the PC (seconds instead of minutes per page).

## AI page reading (photos of book pages → text with tashkeel)

The server can read photos and PDF pages with free AI models made for Arabic, with no internet once a model is downloaded.
Phones (Settings → Reading pages → "AI on the family server") and the web app ("Scan a page with AI" in the editor) send
the page here and get the text back.

1. Open the admin page → **AI page reading**.
2. Press **Download** next to a model. Start with **QARI-OCR 0.2 (2B)**: it is built for Arabic with tashkeel
   and is the fastest. The others are there to compare.
3. Use **Try a page** to read a photo and see how long it takes on your machine.

| Model | Download | Notes |
|---|---|---|
| QARI-OCR 0.2 (2B) | 2.4 GB | Arabic + tashkeel, fastest. Recommended |
| QARI-OCR 0.4 (4B) | 3.3 GB | Newer and larger |
| Qwen2.5-VL 3B | 2.8 GB | General vision model |
| Gemma 3 4B | 3.3 GB | General vision model (Google) |

The models are kept in `data/models` (you can delete a model from the admin page). A model needs about 3–5 GB of free memory
while it reads. It is loaded on the first page and unloaded after 10 idle minutes. Reading is slow on a NAS processor:
expect 1–5 minutes per page, so leave a whole book running.

The engine (llama.cpp) is included in the Windows zip (the `llama` folder) and in the Docker image on Intel/AMD NASes.
ARM NASes don't get it: they are too slow for these models.

## The server's pages

- `http://<server>/` is the start page. It has two doors: **Open Werd** (the web app) and **Server dashboard**. Every page leads back here.
- `http://<server>/admin` is the server dashboard. Sign in with the admin password (`admin-token.txt` in the data folder, or your own with `WERD_ADMIN_TOKEN`).
  - **Dashboard:** the load board (processor, memory, disk, phones, requests per minute, with the last 15 minutes traced; the busiest resource is lit in gold), the work running now (AI page reading, book audio, model downloads, each with a **Cancel** button, and **Cancel all** when there are several) and the activity log (phones syncing, families, the library, voices, AI, errors), refreshed every 5 seconds.
  - **Families:** rename families and people, make someone a parent or child, remove someone from a family, see who was active.
  - **Library:** reports about books, search, view, make audio, hide or delete; and the **shared awrad** (single awrad people shared, public or family only), to view, hide or delete.
  - **Settings:** voices, AI page reading, server details and addresses for phones, and backup.
  - Arabic or English with the toggle at the top; works on phones too.
- Cancelling a book scan on a phone now stops it here too (and on the PC helper); a page that was cancelled stops the AI engine, which loads again for the next page.
- Inside Docker the memory figure is the container's limit when you set one, otherwise the NAS's memory.

## Backup and restore

- **Backup:** the admin page's *Backup* button downloads a consistent copy (`werd-YYYY-MM-DD.db`), or copy the whole data folder while the server is stopped.
- **Restore:** stop the server, replace `werd.db` in the data folder with the backup (delete `werd.db-wal` and `werd.db-shm` if present), start it again.

## Updating

- Windows: `werd-server.exe stop`, replace the exe, `werd-server.exe start` (or just replace and double-click).
- Docker: automatic. Watchtower checks every 10 minutes and installs a new version as soon as it's published; data is
  kept. To go back one version, put it in `image:` (e.g. `ghcr.io/qawalah/werd-server:1.8.0`) and bring the project up
  again (*Project → werd → Action → Build*, or `docker compose up -d`); put `latest` back to resume updates.
  The container uses the NAS's own network (`network_mode: host`, for waking the PC): it listens on the NAS's
  port 8080 directly. If 8080 is taken, set `WERD_ADDR=:8090` (or another port) in `docker-compose.yml`.

## Options

Every option is a flag (`werd-server.exe -addr :9090`) or an environment variable (Docker).

| Flag | Environment | Default | Meaning |
|---|---|---|---|
| `-addr` | `WERD_ADDR` | `:8080` (`:443` with `-domain`) | listen address |
| `-data` | `WERD_DATA` | Windows `C:\ProgramData\Werd Server`, Docker `/data` | data folder |
| `-admin-token` | `WERD_ADMIN_TOKEN` | generated | admin page password |
| `-domain` | `WERD_DOMAIN` | | domain(s) for automatic HTTPS (Let's Encrypt) |
| `-acme-email` | `WERD_ACME_EMAIL` | | email for Let's Encrypt notices |
| `-tls-cert`, `-tls-key` | `WERD_TLS_CERT`, `WERD_TLS_KEY` | | your own certificate files |
| `-trust-proxy` | `WERD_TRUST_PROXY=true` | off | behind a reverse proxy: take client IPs from `X-Forwarded-For` |
| `-urls` | `WERD_URLS` | (Windows: detected) | the server's addresses for phones, comma separated, e.g. `http://192.168.1.20:8080,http://100.101.102.103:8080`; the app switches between them |

## For developers

- API: `../docs/server-api.md`. Code: `main.go` (start-up, HTTPS), `api.go` (endpoints, limits), `store.go` (SQLite), `admin.go` + `web/admin.html`, `service_windows.go`.
- `go test ./...` runs the server tests. The Android app's own library/family code can be run against a live server:
  `./gradlew testDebugUnitTest -PwerdTestServer=http://127.0.0.1:8080 --tests com.werd.app.RealServerTest` (from the repo root).
- `./build.sh` runs the tests and builds the Windows exes into `dist/windows/`.
  `docker build --build-arg VERSION=1.8.0 -t werd-server .` builds the Docker image from source, the same way DevStore
  does on GitHub.

## Security notes

- Tokens are random 256-bit values, stored hashed. The admin password is compared in constant time; wrong guesses are slowed and rate-limited.
- Sign-ups, publishing, reports, family joins and all API calls are rate-limited per IP/account.
- Family progress is visible only to members of the same family, and only while the member shares it.
- The app has no age check: if children use family accounts, check the rules where you live before opening the server to others.
