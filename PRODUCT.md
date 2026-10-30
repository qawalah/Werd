# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- **The family's adults (Abu, the owner, and other parents).** They keep daily awrad (morning and evening adhkar, post-prayer tasbih, a weekly wird from a book), import texts from books and photos, send awrad to their children and follow their progress. Use scenes, all confirmed:
  - right after prayer, phone in hand, tapping the counter while reading;
  - listening with the screen off: the voice reads in the pocket while walking or driving;
  - early morning before Fajr and at night after Isha, in dim rooms.
- **Children.** They follow awrad their parents send (auto-added, with reminders) and count along. The interface must stay simple and safe for them.

## Product Purpose
Werd (ورد) turns supplication text into a tally counter: paste or scan a werd and it splits into lines with their repetition counts; then read, count by tap or volume key, or listen while a voice reads each line the right number of times. It adds reminders (time, prayer, place), streaks and stats, widgets, a book library (scan books with AI or offline readers), and an optional family server (shared library, families with parents/children, shared voices, AI page reading). Success: the daily werd is finished with attention on the words, not on the phone.

## Positioning
A werd companion that understands the text: counts are detected from the text itself ("ثلاثاً", "(٣)", "×100"), the voice repeats each line exactly as many times as it should be said and the tally follows it, and a family's own server shares books, voices and progress privately.

## Operating Context
- One-handed use after prayer; volume keys as counter; haptics; keep-screen-on.
- Background listening with the screen off (voice + tally must continue and resume).
- Dim rooms at dawn/night: dark theme matters as much as light.
- Arabic first (RTL) with English UI available; Arabic supplication text is the content and must be beautifully set with full tashkeel.
- Optional family server on the home NAS (LAN/Tailscale) with an admin dashboard in the browser.

## Capabilities and Constraints
- Native Android, Kotlin + Jetpack Compose (Material 3 components), Room database, WorkManager; minSdk 26, targetSdk 36; arm64 + armv7 APKs.
- Voices: phone TTS, Piper on-phone, Gemini/Azure/ElevenLabs keys, family-server voices; clips cached per voice.
- Page reading: Tesseract, server AI, on-phone AI (llama.cpp), Gemini Nano.
- Terminology: werd/awrad (ورد/أوراد), line/dhikr (سطر), count/target (العدد/المطلوب), streak (أيام متتالية), family (عائلة), parent/child (وليّ/ابن), library (المكتبة), sources (المصادر).
- Confirmed must-keep: the big round counter of focus mode.

## Brand Commitments
- Name: Werd · ورد. Devotional, respectful, calm; no gamified noise.
- The user did not pin the current palette or typefaces for the app revamp; only the big round counter is a binding element.

## Evidence on Hand
- Real awrad content (أذكار الصباح، أذكار المساء، التسبيح بعد الصلاة, books like الوظيفة الزروقية) used for demos.
- App screenshots of the incumbent look in app/screenshots/.
- No usage statistics, testimonials or store ratings exist; none may be invented.

## Product Principles
1. The words come first: the supplication text is the hero of every reading screen.
2. The count never lies: whatever reads or taps, the tally follows exactly, in the foreground and the background.
3. One hand, low light: every daily action works with a thumb and stays comfortable at dawn and night.
4. Calm, never gamified: progress is encouraged quietly.
5. Family-safe: children see only what they need.

## Accessibility & Inclusion
Arabic RTL first-class; large readable Arabic text with adjustable size; works for children; dark mode for dim rooms; TalkBack labels on controls.
