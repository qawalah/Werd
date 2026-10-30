# Werd server API — v1

The phone app (1.7+) speaks this API for the **shared library** and **family**. Everything else stays on the phone.
The server is in `server/` (Go; Windows .exe and Docker image — see `server/README.md`). The app's tests also run
against an in-memory version, `app/src/test/java/com/werd/app/FakeWerdServer.kt`, and against the real server with
`./gradlew testDebugUnitTest -PwerdTestServer=http://127.0.0.1:8080 --tests com.werd.app.RealServerTest`.

- Base URL: set at build time (`./gradlew assembleRelease -PwerdServerUrl=https://api.example.com`) or in the app
  under Settings → Server address. Paths below are relative to it, e.g. `https://api.example.com/v1/library/books`.
- JSON in and out, UTF-8. Times are epoch milliseconds. Days are ISO dates (`2026-10-14`) in the user's local calendar.
- Auth: `Authorization: Bearer <token>` on every call except `POST /v1/accounts`, `GET /v1/library/books` and
  `GET /v1/library/books/{id}` (browsing the library needs no account).
- Errors: any non-2xx status with `{"error": {"code": "<code>", "message": "<human text>"}}`.
  The app reacts to: `401` (token unknown → it registers a new account), `404`, `409` (conflict, e.g. already a member),
  `413` (too large), `429` (slow down; `Retry-After` honoured). Other codes are shown as "server error".
- Limits the app already respects: book file ≤ 8 MB, ≤ 5,000 items; display name ≤ 40 chars; werd ≤ 500 lines.

## Server

### `GET /v1/info`
→ `200`
```json
{ "name": "werd-server", "version": "1.0.1", "api": 1,
  "addresses": [ { "url": "http://192.168.1.20:8080", "kind": "lan" },
                 { "url": "http://100.101.102.103:8080", "kind": "tailscale" } ] }
```
The app's "Save and test" in Settings checks this. `addresses` (1.0.1+) lists the ways phones can reach the server —
`WERD_URLS` first, then (outside Docker) the machine's own LAN and Tailscale addresses; `kind` is `lan`, `tailscale` or
`public`. The app remembers them and, when one address doesn't answer (e.g. the home address while away), uses the next.

### How the app picks an address (1.7.2+)
The app keeps a list: the last address that answered, the home address, the Tailscale address typed in Settings, and
the `addresses` learned from `/v1/info`. Each call tries them in that order (6 s connect timeout each), then the same
addresses with the other scheme (`https` ↔ `http`), and a second round after a short pause. An address counts as
working once it answers with JSON; any other answer (an HTML page from another program on that port) moves on to
the next. API errors (401, 404, 409…) come from a working server and are not retried elsewhere.

## Accounts

No email. The phone keeps a token. Before 1.8 an account was a name and a token only, so the same person on a new phone
(or after reinstalling) became a new account with the same name. Since server 1.8 an account can have a password: its
name is then unique on the server (case, tashkeel and alef forms ignored) and anyone can sign in to it again. Accounts
without a password keep working as before.

### `POST /v1/accounts`
```json
{ "displayName": "أبو محمد", "password": "optional, at least 6 characters" }
```
→ `201 { "userId": "u_8f2c…", "token": "opaque-secret" }`. With a password: `409 name_taken` when the name already has a
password account, `422 weak_password`.

### `POST /v1/accounts/signin` (1.8+)
`{"name": "ابو محمد", "password": "…"}` or `{"code": "K7Q-4MX"}` (a one-time code the owner makes on the dashboard,
valid a day) → `200 {"userId", "token", "displayName", "canReset"}`; `401 wrong_password`. Each phone gets its own token,
so signing in on one phone doesn't sign the others out. After a code sign-in (`canReset`), a new password can be set
without the old one for 30 minutes. Rate-limited per address and per name.

### `GET /v1/accounts/me` (auth, 1.8+)
→ `{"userId", "displayName", "hasPassword", "canReset", "phones"}`

### `PUT /v1/accounts/me/password` (auth, 1.8+)
`{"password": "new", "current": "old"}` — adds or changes the password; the name becomes the sign-in name
(`409 name_taken` if another account has it, `403 current_password` when the old one is needed).

### `POST /v1/accounts/me/signout-all` (auth, 1.8+) → `{"token"}`: every other phone is signed out; this one gets a new token.
### `DELETE /v1/accounts/me/session` (auth, 1.8+): signs this phone out (`409 no_password` for an account without a password).

### `PUT /v1/accounts/me`
```json
{ "displayName": "أبو محمد" }
```
→ `200 {}` (`409 name_taken` for a password account whose new name is taken)

### Dashboard (1.8+)
`POST /admin/api/users/{id}/signin-code` → `{"code", "expiresAt"}` · `DELETE /admin/api/users/{id}/password` ·
`GET /admin/api/users/duplicates` (names with more than one account) · `POST /admin/api/users/{id}/merge {"into": "<id>"}`
(moves books, awrad, inbox, history, family and the phones' sign-ins into the other account, then deletes it).

## Library

A book is the `.werdbook` document (below). The server stores the document, and a summary for listing.

### `GET /v1/library/books?q=<text>&madhhab=<m>&offset=0&limit=30`
Optional auth: with a token, the caller's family-only books are included. `madhhab` (1.1+) filters by one of
`general, hanafi, maliki, shafii, hanbali, jafari, other` (empty = all). Each summary also has `visibility`
(`public` | `family`) and `madhhab`.

`q` matches title and author (Arabic: compare without tashkeel and with أ/إ/آ → ا, ة → ه, ى → ي — see
`OfflineExtract.bare` in the app). Order: most downloaded first.
→ `200`
```json
{
  "total": 42,
  "books": [
    { "id": "b_19a…", "title": "حصن المسلم", "author": "سعيد بن وهف القحطاني", "language": "ar",
      "items": 267, "checked": 267, "downloads": 1520, "publisher": "أبو محمد", "publishedAt": 1760440000000 }
  ]
}
```

### `GET /v1/library/books/{id}`
→ `200` the full `.werdbook` document with `"id"` added. Count it as a download.

`GET /v1/library/books/{id}?preview=true` → the same document but with only the first 5 items, plus the summary
fields (`itemCount`, `checked`, `downloads`, `publisher`, `publishedAt`). Not counted as a download.

### `POST /v1/library/books` (auth)
Body: a `.werdbook` document, optionally with `"madhhab"` (kept in the document) and `"visibility": "family"`
(1.1+; only the publisher's family sees it — needs a family, else `422 no_family`; not kept in the document). The app only offers this when **every** item is proofread (`checked: true`);
the server should still refuse (`422`) books where fewer than all items are checked, books with no items,
and documents over the limits. Duplicate check: same normalised title + same set of item texts → `409` with
`{"error": {"code": "duplicate", "message": "…", "id": "<existing id>"}}`.
→ `201 { "id": "b_19a…" }`

### `POST /v1/library/books/{id}/reports` (auth)
```json
{ "reason": "سقطت كلمة من دعاء الاستيقاظ", "item": 3 }
```
`item` (optional) is the item's index in the document. → `201 {}`. Reports are for moderators; nothing changes automatically.

### The `.werdbook` document
```json
{
  "format": "werd-book", "version": 1,
  "title": "حصن المسلم", "author": "سعيد بن وهف القحطاني", "language": "ar",
  "provider": "gemini", "model": "gemini-3.8-pro", "pageCount": 120, "exportedAt": 1760440000000,
  "items": [
    { "title": "سيد الاستغفار", "text": "اللَّهُمَّ أَنْتَ رَبِّي…", "count": 1,
      "occasion": "morning_evening", "occasionText": "تقال في الصباح والمساء",
      "note": "من قالها موقناً بها…", "page": 33, "checked": true }
  ]
}
```
`occasion` is one of: `morning, evening, morning_evening, after_prayer, sleep, waking, friday, travel, home, food,
mosque, distress, ramadan, general`. Only texts are shared — never page images.

Since 1.14 an item may carry `"textOnly": true` (a text to read, no counter), and the book may carry its own table of
contents: `"index": [{"title": "أذكار الصباح", "page": 12, "item": 3}]` — `page` is the printed page number, `item` the
index in `items` of the supplication the entry opens (`-1` when it opens none).

### Shared awrad (server 1.7+)
Single awrad shared to the library, for everyone on the server (`public`) or only the sharer's family (`family`).

- `GET /v1/library/werds?q=<text>&scope=public|family|mine&offset=0&limit=30` — no account needed for public ones; signed in,
  the family's own and your own are included. → `{"werds": [summary], "total": n}`; a summary has `id, title, note, lineCount,
  lines` (the first line only), `hasSettings, textOnly, visibility, publisher, publishedAt, downloads, mine`.
- `GET /v1/library/werds/{id}[?add=1]` → the whole werd: all `lines` (`{text, count}`) and `werd`, its settings as shared
  (`colorIndex, goalDaysMask, note, group, groupColor, textOnly, reminders[]`). `add=1` counts it as added.
- `POST /v1/library/werds` (auth) `{"title", "note", "lines": [{text, count}], "werd": {settings} | absent, "textOnly", "visibility"}`
  → `201 {"id"}`, or `409 duplicate {"id"}` when the same lines are already shared the same way.
- `PUT /v1/library/werds/{id}` (auth, the sharer) `{"visibility": "public" | "family"}` — move it.
- `DELETE /v1/library/werds/{id}` (auth, the sharer) — withdraw it.

Reminders in `werd.reminders` are the werd's own and its group's. Place, Wi-Fi and Bluetooth reminders (which tell where
the sharer is) are only included when the sharer ticks them; whoever adds the werd picks which reminders, the group and
the goal days to keep. Counts, progress and history are never part of a shared werd.

## Running work (server 1.7+)
### `POST /v1/ocr` (auth)
`{"image": "<base64>", "model": "", "task": "book:12", "title": "حصن المسلم"}` — `task` names the phone's job (one book's scan),
`title` is shown on the dashboard. A page of a cancelled job answers `409 cancelled`.

### `DELETE /v1/tasks/{task}` (auth)
Stops every page of that job being read or waiting — here or on the helping PC (which is told on its next heartbeat and
stops its engine) — and refuses further pages of it for a few minutes. Closing the request (the phone gives up) stops that
page too. The dashboard can cancel one task or all of them (`DELETE /admin/api/tasks[/{id}]`), and book audio jobs
(`DELETE /admin/api/tts/jobs/{id}`).

### `POST /v1/ai/complete` (auth, 1.8+)
`{"prompt": "…", "model": ""}` → `{"text"}`: a text-only answer from one of the server's reading models (the app's
proofreading helper asks which word fits where page readings disagree). `404 ocr_off` without an engine or model.

### Reading models and the graphics card (1.8+)
The owner can add any vision model (a GGUF and its `mmproj` projector): `POST /admin/api/ocr/custom
{"name", "modelUrl", "projUrl"}` (downloaded) or `{"name", "modelPath", "projPath"}` (files already on the machine),
`"prompt"`: `arabic` | `qari` | the instruction; `DELETE /admin/api/ocr/custom/{id}`. They are kept in
`data/models/custom.json`, listed with the built-in ones (`GET /v1/ocr/models`), and can be the default.
`GET /admin/api/ocr` reports each model's last reading time (`secsPerPage`) and `report`: what the engine's log says about
the graphics card (`cuda`, `onCard`, `layers`, `device`, `problem` — e.g. a driver too old for the CUDA engine, which
then runs on the processor). The dashboard shows the card's load, memory and temperature (`gpu`, `gpuHistory`).

## Family

One family per user (for now). A family has an invite code to share; anyone with the code can join.

### `POST /v1/families` (auth)
```json
{ "name": "عائلة أبي محمد" }
```
→ `201 { "familyId": "f_…", "name": "عائلة أبي محمد", "inviteCode": "K7Q-4MX" }`  (`409` if already in a family)

### `POST /v1/families/join` (auth)
```json
{ "inviteCode": "K7Q-4MX" }
```
→ `200` same shape as above. `404` unknown code, `409` already in a family.

### `GET /v1/families/me` (auth)
→ `200` (`404` when not in a family)
```json
{
  "familyId": "f_…", "name": "عائلة أبي محمد", "inviteCode": "K7Q-4MX",
  "members": [
    { "userId": "u_…", "displayName": "أبو محمد", "isMe": true, "sharing": true,
      "progress": { "day": "2026-10-14", "updatedAt": 1760440000000, "streak": 11, "due": 4, "done": 2,
                    "werds": [ { "title": "أذكار الصباح", "done": 33, "total": 33, "completed": true } ] } }
  ]
}
```
`progress` is the member's latest `PUT /v1/progress`, or `null` when they have none or `sharing` is false.

### Roles (1.1+)
Members have `role`: `parent` or `child`. The creator is a parent; people who join are children. `GET /v1/families/me`
adds `role` and `seenAt` to each member and `myRole` to the family. When the last parent leaves, the earliest member
becomes the parent.

### `PUT /v1/families/me/members/{userId}` (auth, parent)
`{ "role": "parent" | "child" }` → `200 {}`. `403 not_parent`, `404 not_in_family`, `409 last_parent`.

### `DELETE /v1/families/me/members/{userId}` (auth, parent)
Takes someone out of the family. → `200 {}`.

### `GET /v1/families/me/members/{userId}/history?days=30` (auth)
→ `200 {"days": [<progress>, …]}` newest first, one per day (the last `PUT /v1/progress` of that day; kept 120 days).
A parent may read any member who shares progress; anyone may read their own.

### `DELETE /v1/families/me/membership` (auth)
Leave the family. The last member leaving deletes it. → `200 {}`

### `PUT /v1/progress` (auth)
Today's summary; the phone sends it after a werd is completed and every few hours. Overwrites the previous one.
```json
{ "sharing": true, "day": "2026-10-14", "streak": 11, "due": 4, "done": 2,
  "werds": [ { "title": "أذكار الصباح", "done": 33, "total": 33, "completed": true } ] }
```
With `"sharing": false` the server must drop the stored progress and stop showing it. → `200 {}`

### `POST /v1/families/me/members/{userId}/werds` (auth)
Send a werd to a family member's inbox.
```json
{ "title": "ورد السحر", "note": "اقرأه قبل الفجر", "lines": [ { "text": "أستغفر الله", "count": 100 } ],
  "werd": { "colorIndex": 2, "goalDaysMask": 127, "note": "…", "group": "أذكار",
            "reminders": [ { "type": "DAILY", "hour": 4, "minute": 30, "daysMask": 127, "skipIfDone": true } ] },
  "autoAdd": true }
```
→ `201 { "id": "w_…" }`. `404` if that user is not in the sender's family. `werd` (1.1+, ≤ 64 KB) is the werd's
settings, passed through as is; only time reminders (`DAILY`, `ONCE`, `INTERVAL`, `PRAYER`) are sent. `autoAdd` is
honoured only from a parent to a child: the child's app adds it without asking.

### `GET /v1/inbox` (auth)
→ `200`
```json
{ "werds": [ { "id": "w_…", "from": { "userId": "u_…", "displayName": "أبو محمد" },
               "title": "ورد السحر", "note": "…", "lines": [ { "text": "أستغفر الله", "count": 100 } ],
               "sentAt": 1760440000000, "werd": { … }, "autoAdd": false } ] }
```

### `DELETE /v1/inbox/{id}` (auth)
The user added or dismissed it. → `200 {}`

## Voices (server 1.2+)

### `GET /v1/tts/voices` (auth)
→ `200 {"enabled": true, "default": "azure:ar-SA-HamedNeural", "voices": [{"id": "gemini:Charon", "provider": "gemini", "name": "Charon", "detail": ""}, …]}`.
Voices of every provider the owner set up on the admin page (Gemini, Azure, ElevenLabs).

### `POST /v1/tts` (auth)
`{"text": "…", "voice": "<id>" | ""}` → `200` audio (`audio/mpeg` or `audio/wav`), header `X-Werd-Cached: true|false`.
Each clip is kept in `data/tts`, so a line is made once for the whole family. `404 tts_off` when no voice is set up,
`424 tts_failed` when the voice service refused (message says why).

## Notes for the implementation
- Store tokens hashed. Rate-limit account creation and publishing per IP.
- Family data is personal: only members of the same family may read each other's progress; `sharing: false` hides it.
- Children: the app has no age gate yet — if children use family accounts, check the rules where you operate before launch.
- Moderation: reports land in a queue; unpublishing a book should keep it downloadable for those who already have it (they hold a copy).
