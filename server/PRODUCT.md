# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
- **The server owner (Abu, the family's admin).** Runs the Werd server on a Synology NAS (Docker) or a Windows PC and opens its pages from a PC browser and from his phone, about equally. Jobs: see whether the server is healthy and busy, watch what the family and phones are doing, manage families and roles, curate the shared library, configure voices and AI page reading, back up.
- **Family members on the web.** Use the Werd web app (`/app/`) in a browser instead of the Android app: tally awrad, read, browse the library, see family progress.

## Product Purpose
Werd (ورد) turns supplication text into a tally counter with reminders, stats and read-aloud. The server is the family's private companion: a shared library of books/awrad, family groups with parent/child roles (parents send a werd with its settings and follow children's progress), a shared voice library, and an offline AI page reader. Success: the owner can tell at a glance that everything is running, what just happened, and how hard the machine is working, and can fix anything without touching files.

## Positioning
A self-hosted, family-only home for a devotional app: everything stays on the family's own NAS/PC, works without internet at home (Tailscale away), and heavy work (voices, AI reading of book pages with tashkeel) is done once on the server and shared with every phone.

## Operating Context
- Runs as a Docker container on a Synology NAS (Container Manager, often an Intel/AMD Celeron-class CPU with limited RAM) or as `werd-server.exe` on Windows.
- Reached at the LAN address (e.g. `http://nas:8080`) or over Tailscale; plain HTTP is common.
- Long-running background work: book audio generation jobs (TTS), AI model downloads (2–3 GB), AI page reading (minutes per page, CPU-bound), phone sync.
- Admin access is protected by one admin password (bearer token).

## Capabilities and Constraints
- Single Go binary with embedded static pages (`web/admin.html`, `web/app/`); vanilla HTML/CSS/JS, no build step, no external CDNs (works offline on the LAN). Fonts bundled (Amiri, Tajawal).
- Three web surfaces: `/` start page, `/admin` server dashboard, `/app/` Werd web app. From any of them the user must be able to get back to the start page (logout from admin returns there).
- Dashboard language: one language at a time with an AR/EN toggle; Arabic is right-to-left.
- Must work on phone widths and wide desktop screens equally.
- Resource figures come from the OS (Linux `/proc` and cgroup inside Docker; Windows system APIs) and from the server's own counters; no external monitoring service.
- Terminology: werd/awrad (ورد/أوراد), family (عائلة), parent/child (وليّ/ابن), library (المكتبة), voices (الأصوات), AI page reading (قراءة الصفحات بالذكاء الاصطناعي), madhhab (مذهب).

## Brand Commitments
- Name: Werd · ورد. App palette already in use on the phone and web app: deep green primary (#0E6B4E) with a warm gold accent; Amiri for Arabic text, Tajawal for UI.
- Content is devotional: respectful, calm, no gamified noise.

## Evidence on Hand
- Real data comes only from the running server (users, families, books, clips, jobs, models). No invented usage numbers, testimonials or benchmarks.
- Measured AI reading results (one test page): QARI‑OCR 0.2 0% CER; QARI 0.4 4.8%; Gemma 3 4B 29%; Qwen2.5‑VL 3B 46%; Tesseract 55%.

## Product Principles
1. Glanceable health first: "is it running, what is it doing, how loaded is it" answered before anything else.
2. Show real activity, never fake liveliness: every number and event comes from the server.
3. One coherent home: every page leads back to the start page and to the others.
4. Respect the content: calm presentation for devotional material; Arabic is first-class, not a translation afterthought.
5. Safe administration: destructive actions confirm; the family's data is never lost by an update.

## Accessibility & Inclusion
Full RTL support in Arabic mode; readable on small phones; sufficient contrast in light and dark.
