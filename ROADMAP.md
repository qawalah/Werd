# Werd — roadmap

**Status (1.15 / server 1.8.0):** the eleven items added on 27–28 Oct are built: other reading models on the server, the
graphics card's load (and a warning when the engine falls back to the processor), the dashboard in the order library →
resources → work, sign-in with a password (and merging accounts made twice), proofreading with readers of your choice, the
proofreading helpers (the Quran, spelling, a small model), proofreading a book after it is scanned (with a higher-resolution
re-read), headers/footers/footnotes, titles from each supplication's heading, the simpler proofread screen, and the smaller
counter. Not yet: marking a single line of a page as header/footer by hand (the section and footnote are editable per item
instead), and hadith/adhkar collections for the known-text helper (only the Quran is in the app).

**Status (1.14 / server 1.7.0):** the five items added on 26–27 Oct are done: the book's index, sharing a werd to the
public or family library (with its reminders and group as an option), the two-pass scan, texts with no tally, and
cancelling running work on the server.

**1.8 / server 1.1:** parent and child roles; send a werd with its settings (straight onto a child's list); per-member
30-day history; family-only books; madhhab on books with a library filter; tally view with adjustable text and a
smaller counter; collapsible voice settings.

**Status (1.7.2):** all four items below are done, phone and server (`server/`, Windows .exe and Docker).
1.7.1 allowed plain-http home servers; 1.7.2 adds a home + Tailscale address with automatic switching, https→http
correction, addresses advertised by the server (`/v1/info`, `WERD_URLS`), and per-address diagnostics in Settings.

## Rescan part of a book with a different model
Pick a page range in the proofread view, choose a provider/model, re-read only those pages.
- `SourceWorker` already reads in page batches and keeps `pagesDone`; a rescan is a job over `[from, to]` that replaces the items whose `page` is in range.
- Keep the proofread tick (`SourceItem.checked`) on items whose text did not change (match with `AiTasks.dedupeKey`).

## Export imported books
Share a book's extracted supplications as a file (and re-import it on another phone).
- A small JSON format: source title/kind/provider/model + its `SourceItem`s (title, text, count, occasion, occasionText, note, page). The original pages are optional (large).
- Import creates a `Source` with status `DONE`, so no AI is used; the backup code in `data/Backup.kt` is the pattern to follow.

## Shared library (crowdsourced books)
A library of books other users have already extracted and proofread, so nobody has to spend their own AI on the same book.
- Builds on the export format above; needs a server (upload, search, download) and accounts.
- Quality: only publish books whose items are proofread (`checked`), show how much was checked, let users report errors; dedupe by title + content hash.
- Copyright: many adhkar books are public domain, but modern editions and commentary may not be — publish the supplication texts (public domain) rather than page scans, and keep a takedown path.

## Family
Add family members, see their progress, and send awrad to their lists.
- Needs accounts and sync (today all data stays on the phone).
- Sharing a werd = sending its title + lines; progress = daily `DaySession` summaries (completed / streak), shared only with consent.
- Parent / child roles; children's accounts need extra care (privacy, consent).

## Book index (فهرس) at the top of a scanned book
Read the book's table of contents and show it as a clickable index above the book's list of awrad.
- Reading: the AI pass over the first pages (and the last pages, where many books put the فهرس) detects the contents page and returns entries as title + page number. Store them on the `Source` as an index (title, page, and the matching `SourceItem` once found); let the user fix or re-read it from the proofread view.
- Linking: match each entry to the item that starts on that page (`SourceItem.page`) or by title (`AiTasks.dedupeKey`); entries with no match stay as plain text and can be linked by hand.
- Display: the index sits at the top of the book screen as a collapsible list. Tapping an entry scrolls to its werd and shows it collapsed (title and count only); tapping again expands it with its full text and tally. Opening another one collapses the previous (an "open all" option can come later).
- Server: books shared through the family library carry the index too, so a downloaded book needs no extra AI use.

## Share a werd to a library (public or family)
Publish a single werd (not a whole book) to a library, so others can add it to their own list.
- Two libraries: **public** (anyone can browse and download) and **private / family** (only members of the family on your server). Choose the target when sharing; a werd can be moved from one to the other or withdrawn later.
- What is shared: title, lines, counts, occasion, note and source/book name. The person sharing chooses whether to include the extras below.
- **Reminders and groups as an option:** a "keep reminders and groups" switch on the share sheet. When on, the werd carries its reminder rules (time, date, place, Wi-Fi, Bluetooth) and the group it sits in. When the werd is added, the receiver sees what came with it and can accept or skip each part; place and Wi-Fi/Bluetooth names are only included when the sender ticks them, since they reveal where the sender is.
- Family library builds on the existing "send a werd" (server 1.1) and family-only books; the public library builds on the Shared library item above (accounts, reporting, takedown, de-duplication by title + content hash).
- Public items show only the texts, never personal data; progress and history are never shared.
- Needs the export format from "Export imported books" (extended with optional reminder and group fields).

## Two-pass scan to reduce OCR errors (without tashkeel, then with)
Read each page twice and compare, so mistakes in either reading show up instead of slipping into the book.
- Pass 1 reads the page **without tashkeel** (plain letters, which the model reads most reliably). Pass 2 reads it **with tashkeel**. Removing the tashkeel from pass 2 must give pass 1's letters back; where it doesn't, the page has a likely error.
- Compare after stripping tashkeel and normalising (alef forms, tatweel, spacing): a word-level diff finds the differences. Matching words are accepted as they are; differing words are flagged on the item.
- Flagged items appear first in the proofread view with the two readings side by side, and the user picks one or edits. Items with no differences can be ticked as checked automatically (`SourceItem.checked`), which cuts the proofreading work.
- Option per book: "Single pass" (cheapest), "Two passes" (default for important books), or "Two passes only for pages where the first pass looks doubtful" (very short or very long lines, odd characters, count mismatch).
- Cost: roughly doubles the AI use for the book; the second pass can use a different model through the rescan feature above, and on the family server it can run on the PC helper when it is awake.
- Note: the request said "without tashkeel" for both scans; this assumes the second scan is the one with tashkeel. Change it if you meant otherwise.

## Texts with no tally (reading-only items)
Some imported texts are not awrad: a supplication, a poem, a sermon, a biography or an introduction has no repetition count, so a tally counter makes no sense for it.
- Each item gets a kind: **werd** (has a count, shows the tally) or **text** (no count, reading only). The default comes from the scan: when no repetition count is found, the item is a text, and the user can switch it either way.
- A text opens in a plain reader: no counter, no "done" state, no streak or goal. Read-aloud and the moving text marker still work, and the user can mark it as read.
- Texts stay out of the daily tally statistics, goals and "remaining today" lists; reminders on a text just open it.
- Groups can mix both kinds. Sending a werd to family and the shared library carry the kind with the item, so a text never turns into a tally on someone else's phone.
- For imports from other apps (such as the Ba 'Alawi library), the source's own section tells which items are texts (biographies, references, introductions); the rest are checked by the scan.

## Cancel a running task on the server (bug)
Cancelling a book scan on the phone leaves the work running on the server, and the dashboard has no way to stop it.
- Today the phone only cancels its own side (`SourceWorker` cancels the WorkManager job). The server has no cancel for page-reading or voice jobs: a page read has a 15-minute limit (`ocr.go`) but nothing stops it earlier, and jobs handed to the PC helper (`helper.go`, `helper_client.go`) keep going until they finish.
- Phone: when the user cancels (or the app drops the request), tell the server which job to stop, e.g. `DELETE /v1/jobs/{id}`, and drop jobs still waiting in the queue.
- Server: every job gets a cancel function tied to its context. Cancelling stops the reading process (the AI engine's child process), removes queued pages and book-audio lines, and tells the PC helper to abandon the job it was given (the helper checks for a cancel flag on each poll and stops its engine).
- Dashboard: a Cancel button on each running or queued job (Jobs list and the PC bar), plus "Cancel all", and the event log records who cancelled what.
- Safety net: a job whose phone stopped asking for results (no poll for a few minutes) cancels itself.

## Other reading models on the server
Today the server offers a fixed set of page-reading models. Let the owner add others.
- A model list in the dashboard (AI tab): add a model by choosing a vision-language GGUF and its projector file (`mmproj`) from a link or from the data folder, give it a name, and pick one as the default. Remove ones no longer wanted.
- Show for each model its size, whether it fits the graphics card's memory, and the reading speed measured on the last page.
- The phone's model picker lists the server's models, so a scan or a rescan can choose any of them (this also feeds "Proofread with two models of my choice" below).

## Show the graphics card load
- Dashboard: live GPU load, memory used / total and temperature (from `nvidia-smi`), next to the CPU and memory bars, with a short history graph.
- Say clearly whether the reading engine is really running on the card: read `llama-server.log` for the CUDA device and the offloaded layer count, and warn ("reading on the processor") when it fell back to the CPU, with the likely cause (old driver, no CUDA device).
- The PC bar of the family server shows the same figures for the helper PC.

## Dashboard order: library first, then resources and tasks
The first screen of the dashboard should be what the family is using, not the machine.
- Order: **users' library** (users, books and awrad shared, books per user, recent additions, storage used) → **resources** (CPU, memory, disk, GPU) → **tasks** (running, queued, finished).
- Each block can be collapsed, and the last state is remembered in the browser.

## Login for users (same name detected as a new user)
Signing in with a name that already exists creates a second, separate user instead of returning the first.
- Today a name alone creates an account. Add a real sign-in: a name plus a password (or a one-time sign-in code the owner gives), so the same name on a new phone or after a reinstall returns to the same account and its library.
- Names are unique (case-insensitive, Arabic letter variants normalised); registering a taken name asks to sign in instead.
- "Forgot password" and "sign out of all phones" in the app; the owner can reset a user's password from the dashboard.
- Existing accounts: the first sign-in after the update lets the owner link duplicate accounts of the same name into one (merge their libraries and shared items).

## Proofread with two models of my choice
Two-pass scanning (above) compares the chosen reader with the phone's built-in Tesseract only. Let the user choose both readers.
- In the scan options, pick **reader A** and **reader B** from any available model: the phone's built-in reader, the server's models (including ones added in "Other reading models"), the PC helper, or Gemini / Claude.
- The same comparison runs on any pair: agreeing words are ticked, differing words are flagged with both readings in the proofread view, and the user picks one.
- Remember the pair per book, and offer a third reader to break ties on words where the first two disagree.

## Proofreading helpers: known texts, spelling, and a small context model
Extra checkers that suggest corrections in the proofread view. They only suggest; they never change the text on their own, and each suggestion names its source.
- **Known-text matching (offline first):** the Quran with tashkeel (about 1 MB, bundled or downloaded once) is matched against any line that looks like a verse. The closest verse is found by fuzzy match, and a differing word is suggested from the verse's spelling, using the words before and after it. A match shows its source (e.g. "Al-Baqarah 2:255"). Hadith collections and well-known adhkar (Hisn al-Muslim, the Prophet's supplications) work the same way, as optional downloads or an online lookup, since they are larger and their wording varies. Texts in no public corpus (the Ba 'Alawi ratibs, poems) get no match.
- **Spelling check:** a word list or small frequency model flags words that are unlikely in Arabic (look-alike letter swaps such as ر/ز, ع/غ, ة/ه). It flags only, because rare religious vocabulary and poetry would give false alarms. Runs offline with no model.
- **Small text-only model:** a 1–3B model, run on the phone or the PC helper, gets the line and the doubtful word and proposes corrections from context. It counts as another reader in "Proofread with two models of my choice", and its guesses are marked as guesses because small models can "fix" a correct word.
- **Confidence:** an item is ticked as checked only when the readers agree or an exact known-text match is found. A verse match counts as strong evidence.
- **Online lookups:** only if the user turns them on; send the least text needed; fall back to the offline data without a connection.
- **Order of work:** Quran matcher first (offline, small, exact), then spelling flags, then the optional small model.

## Proofread a book after it is scanned (and a higher resolution when it helps)
Proofreading should not have to be chosen at scan time. A book read once by one model can be checked later by the other readers and helpers.
- **Check an existing book:** ⋮ on a book → "Proofread with…". Pick the reader(s): the phone's built-in reader, the server's models, the PC helper, Gemini / Claude, and the helpers from "Proofreading helpers" (known texts, spelling, small context model). It runs over the stored pages and uses the same comparison as the two-pass scan: agreeing words are ticked as checked, differing words are flagged with both readings in the proofread view.
- **Scope:** the whole book, only the unchecked items, only items already flagged, or a page range. Items the user has already ticked or edited by hand are never overwritten; a later reading only adds a suggestion.
- **Builds on Rescan:** the existing rescan keeps the page images and re-reads them; this adds a "compare, don't replace" mode on top, and keeps each reading (`alt`) with the model that produced it, so the proofread view can say who read what.
- **Cost and progress:** shows a cost estimate before it starts (pages × reader), runs in the background like a scan, can be paused or cancelled (cancel reaches the server), and is limited to the PC helper when the user wants it free.
- **Higher resolution when it makes sense:**
  - Offer it per page or per flagged item: re-read the page at a higher resolution (or just the region of the doubtful line, cropped and enlarged), since small print and thin tashkeel are often what a model gets wrong.
  - Suggest it automatically only where it should help: pages the readers disagree on, pages flagged as doubtful, or scans whose source image is small or blurry. Not on pages that already agree, because it costs time and memory.
  - Limits: the higher resolution has to exist in the source (re-render a PDF page at a larger size; for a photo, use the original, never an upscaled copy); respect the model's image-size limit and the graphics card's memory; fall back to a tiled read of the page when the full page is too large.
  - The result is shown as one more reading to compare, not as a replacement.

## Understand headers and footers on scanned pages
Printed pages carry running headers (book or chapter title) and footers (page number, footnotes, publisher marks). Today they can end up inside the supplication text or break an item across a page.
- **Detect:** find the header and footer zones of each page by position (top and bottom strip) and by repetition (the same or nearly the same line on many pages; a number that goes up by one per page). The AI reader is asked to return them separately from the body (`header`, `footer`, `pageNumber`, `footnotes`); the on-phone reader finds them from line positions and repeated lines across the book.
- **Keep them out of the text:** header and footer lines never become part of a supplication, a count, or a title. Page numbers are not read as repetition counts ("33" at the bottom of a page is not a tally).
- **Use them:** the printed page number gives the offset for the book index links (the printed page versus the scan's page number); the running header gives the chapter or section name, which can group items automatically and label where an item came from.
- **Footnotes:** kept as a note on the item they belong to (the reference, a translation or an explanation), shown folded, and never read aloud unless the user asks.
- **Across a page break:** a supplication that continues on the next page is joined, with the header and footer in between removed, instead of becoming two items.
- **Review:** a page's detected header and footer are shown in the proofread view, where the user can mark a line as header, footer or body if it was wrong, and the choice is applied to the rest of the book.
- **Two-pass and proofreading:** the comparison ignores headers and footers, so a differing page number doesn't flag an item.

## Simpler proofread screen: original + editing only
The phone's proofread screen shows four blocks at once (the original page, reading 1, reading 2 and the editing box). That is too much for a phone screen.
- **Two areas only:** the **original page** and the **editing box**. The other readings are no longer separate sections.
- **Alternatives in a small popup:** the differing words are marked in the editing text; tapping one opens a small popup (or a slim strip above the keyboard) listing the other readings of that word, each labelled with the reader that produced it (and a known-text match, if any). One tap puts it in. "Keep mine" closes it. Next/previous buttons jump between flagged words, so the user rarely leaves the editing box.
- **Whole-paragraph alternatives:** a "Readings" button on the item opens one sheet with the full alternative texts side by side, for the rare case where a word-by-word choice is not enough. It is a sheet that opens and closes, not a permanent section.
- **Original follows the editing:** the original page view zooms and scrolls to the paragraph or section being edited, fitted to the width of the screen (like "fit to width"), with the item's lines highlighted. Moving to the next item moves the original along. The user can still pinch or drag, and a "fit" button returns it to the item. Needs each item to remember its place on the page (the box around its lines), which also helps the higher-resolution re-read of a doubtful line.
- **Layout:** the original on top and the editing box below in portrait; side by side in landscape or on a tablet. A handle between them sets the split, and the choice is remembered.
- **Keep:** keep / use-other / edit actions, the "checked" tick and the header and footer marking (see above) stay available from the editing area.

## More room for the text: a smaller tally bead area (سبحة)
In the reader the counter (the sabha) takes a large part of the screen, which leaves less room for the text of the supplication.
- Make the counter area smaller by default: a slim bar at the bottom (count, target, the tap area and the undo button) instead of the large counter.
- Let the whole text area act as the tap target when the user wants it, so the counter itself can be very small.
- Settings: counter size (large / medium / slim / hidden with tap-anywhere), and an option to hide the decorative bead graphic. The choice is remembered, and applies to the home screen card and the widget where relevant.
- Long texts: the counter slides out of the way while scrolling and returns when the user stops, so the text uses the full screen.
- Texts with no tally (see above) already show no counter, so they get the whole screen.

## Read each supplication's title from its heading (not the same title for all)
In a scanned book, the heading of a supplication ends up inside its text, and every item gets the same title (the book's or a generic one).
- **Find the heading:** the lines at the start of an item that are set apart from the body: bigger or bolder print, centred, a ruled or boxed line, a short line ending with no full stop, or words like «دعاء», «باب», «ذكر», «فصل», «وِرد». The AI reader returns it as `title` separately from `text`; the on-phone reader finds it from line size and position.
- **Running header as a fallback:** when a page has no heading of its own, use the running header from "Understand headers and footers" (chapter or section name), and say in the item that the title came from the header.
- **Out of the text:** once taken as the title, the heading line is removed from the item's text, so it isn't read twice and isn't counted in the comparison between readers.
- **Titles must differ:** if every item of a book would get the same title, keep looking (the heading, then the first words of the text), and never leave the book's own title on every item. Items still without a heading get the first few words of the text as a title the user can edit.
- **Index:** the titles feed the book index (فهرس) links, matching entries to supplications by title.
- **Fix existing books:** a "Read titles again" action (⋮ on a book) re-reads the stored pages for headings and offers the new titles for review in the proofread view, without changing text the user has edited or ticked. Titles edited by hand are kept.
- **Review:** the proofread view shows the title above the text as its own editable line, with the heading's place on the page highlighted in the original.
