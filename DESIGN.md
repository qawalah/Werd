---
name: Werd
description: The Android werd counter, drawn as a misbaha — warm ivory, one green action colour, and beads for every count.
colors:
  ivory-ground: "#F7F3EC"
  card: "#FFFFFF"
  ink: "#22201C"
  muted-ink: "#6E675C"
  outline: "#CFC6B6"
  card-border: "#EAE3D6"
  werd-green: "#127A57"
  green-container: "#D5EEE2"
  done-tint: "#E6F3EC"
  hero-ink: "#22201C"
  hero-bead: "#6FD3A8"
  bead-empty: "#8F8778"
  bead-current: "#22201C"
  brass: "#8A6A2E"
  brass-container: "#F1E6CC"
  night-ground: "#121210"
  night-card: "#1A1A17"
  night-ink: "#ECE8DF"
  night-green: "#6FD3A8"
  misbaha-walnut: "#2A1D12"
  misbaha-amber: "#D9902B"
  dawn-indigo: "#2B2A5C"
  dawn-peach: "#E08A64"
  classic-green: "#0E6B4E"
  classic-gold: "#A67C24"
  alarm-red: "#B3261E"
typography:
  scripture:
    fontFamily: "Amiri, serif"
    fontSize: "26sp (A+/A− adjustable)"
    fontWeight: 400
    lineHeight: 1.9
  wordmark:
    fontFamily: "Amiri, serif"
    fontSize: "28sp"
    fontWeight: 700
  counter-numeral:
    fontFamily: "Tajawal, sans-serif"
    fontSize: "56sp"
    fontWeight: 700
    fontFeature: "tnum"
  title:
    fontFamily: "Tajawal, sans-serif"
    fontSize: "18sp"
    fontWeight: 700
  body:
    fontFamily: "Tajawal, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Tajawal, sans-serif"
    fontSize: "13sp"
    fontWeight: 500
rounded:
  xs: "8dp"
  sm: "12dp"
  md: "18dp"
  lg: "24dp"
  xl: "30dp"
  pill: "999dp"
spacing:
  gutter: "16dp"
  card-pad: "16dp"
  stack: "12dp"
  bead-gap: "4dp"
components:
  button-count:
    backgroundColor: "{colors.werd-green}"
    textColor: "{colors.card}"
    rounded: "{rounded.pill}"
    size: "64dp"
  werd-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "16dp"
  werd-card-done:
    backgroundColor: "{colors.done-tint}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
  today-hero:
    backgroundColor: "{colors.hero-ink}"
    textColor: "{colors.ivory-ground}"
    rounded: "{rounded.lg}"
    padding: "20dp"
  chip-auto-count:
    backgroundColor: "{colors.brass-container}"
    textColor: "{colors.ink}"
    rounded: "{rounded.xs}"
  counter-disc:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.pill}"
    size: "150–250dp"
---
# Design System: Werd

## Overview

**Creative North Star: "The Misbaha in the Hand"**

Every werd is a string of beads, and the counter is the loop of a misbaha with its imam bead at the top. A count is never a percentage or a progress bar; it is a bead that fills. The screens around the beads stay quiet: warm ivory paper, white cards, one green action colour, Arabic text set large in Amiri with full tashkeel.

The look serves four scenes: after prayer with the phone in one hand, listening in the pocket with the screen off, the dim rooms of dawn and night, and children counting along. So every daily action sits under the thumb, and the dark schemes are as worked as the light ones.

Colour is a choice. Standard (ivory and green) is the default. Misbaha (walnut and amber), Dawn (indigo and peach) and Classic (the original deep green and gold with its patterned header) sit beside it in Settings → Colours. Every scheme maps onto the same roles, so the shapes never change.

**Key Characteristics:**
- Beads instead of rings, bars or glyph ornaments, everywhere a count or a divider appears.
- The round counter: a turning loop of 33 beads and the imam around a large numeral; or, by choice, a hanging string you pull beads down.
- A dark Today hero on Home that shows and steers the voice session running in the background.
- One green action colour per scheme; brass/amber only for quiet accents.
- Arabic-first right-to-left layout; the bead strings run in reading direction.

## Colors

Each of the four palettes fills the same Material 3 roles plus a set of extra roles (`ExtraColors`). The frontmatter lists Standard in full, plus the anchor colours of the other three.

### Primary
- **Werd Green** (#127A57; night #6FD3A8): the count button, filled beads, the reading line's border, the spoken word, selected chips.

### Secondary
- **Brass** (#8A6A2E) and **Brass Container** (#F1E6CC): the auto-count chip, quiet accents. In Misbaha the same roles are walnut/amber, in Dawn peach, in Classic gold.

### Neutral
- **Ivory Ground** (#F7F3EC): page background. **Card** (#FFFFFF) with **Card Border** (#EAE3D6).
- **Ink** (#22201C) and **Muted Ink** (#6E675C): text.
- **Hero Ink** (#22201C): the Today hero on Home; light text and **Hero Bead** (#6FD3A8) beads.
- **Night** (#121210 ground, #1A1A17 cards, #ECE8DF ink): the dark scheme for dawn and night.

### Beads
- **Bead** = the primary colour; **Bead Empty** (#8F8778) is an outline only; **Bead Current** (#22201C) is the ring around the bead being said now.

### Named Rules
**The Three-to-One Bead Rule.** An empty bead's outline must reach at least 3:1 against the surface it sits on, in every palette and in both light and dark. A bead that cannot be seen is a count that lies.

**The One Action Colour Rule.** Each scheme has exactly one action colour (the primary). Done, streak and voice marks use tints and icons, never a second loud colour.

## Typography

**Scripture:** Amiri (supplication text, the wordmark, previews of the next line)
**UI:** Tajawal (everything else)

### Hierarchy
- **Scripture** (Amiri 400, 26sp by default, adjustable with A+/A−, 1.9 line height): the line being read. It is the hero of every reading screen.
- **Counter numeral** (Tajawal 700, about 56sp, tabular): inside the bead loop, with "of N" under it and "round k" when the target is over 33.
- **Title** (Tajawal 700, 18sp): werd names and section titles.
- **Body** (Tajawal 400, 15sp): sentences and sub-lines.
- **Label** (Tajawal 500, 13sp): chips, the "now being said: repetition k" line, counts beside bead strings.

## Layout

Phone-first, one column, 16dp gutters.

- **Home:** wordmark and Gregorian and Hijri dates, then the Today hero (the play/pause for the live voice session, or open the next werd; a bead string of the day's awrad; the streak only when above zero), then werd cards.
- **Reader:** a progress header with a bead string of lines, line cards (scripture, number badge, the count button, and a bead string for targets 2–33), then a fixed voice bar at the bottom carrying pause, "now being said: repetition k", the voice chip and the auto-count chip.
- **Focus:** a bead string of lines at the top, the scripture line, then the round counter at 36% of the height (150–250dp) in the thumb zone.

## Elevation & Depth

The app is flat: cards are white on ivory with a 1dp border. Only two elements are lifted:
- The counter disc in dark schemes uses surfaceContainerHigh with a 2dp shadow, so the loop separates from the night ground.
- Bottom sheets (the voice chooser).

## Shapes

- Corner radii are 8 / 12 / 18 / 24 / 30 dp. Cards use 18dp and the hero 24dp.
- Beads are circles. The imam bead is an oval with a short thread, drawn at the top of the loop.
- Count buttons and the counter disc are full circles.

## Components

### Round counter (signature, must keep)
- `MisbahaRing`: a loop of 33 beads plus the imam that turns one bead under the gold counting mark with every tap. The beads stay the same size whatever the target.
- Short targets light only their beads; the rest are dimmed. Targets over 33 go round in loops of 33, with "round k" under the numeral, and the imam arrives under the mark when a loop is complete.
- The imam, its long carved shahid and the tassel ride round with the loop. They hang like a pendulum, swinging as the loop speeds up and settles.
- The numerals sit on a tray inside the loop, with a soft halo so the tassel can pass behind them. While the voice says a repetition, the current bead breathes (`live`).
- The whole area is the tap target. Volume keys count too.

### Pull-down counter (the alternative)
- `MisbahaPull`: the string hangs down the middle with a gap at the thumb. Dragging down anywhere pulls the next bead across the gap; it drops with a small bounce onto the counted beads, and the string slides down one bead.
- One bead per pull. A second bead in the same pull needs a long, heavy pull.
- Beads have one fixed size. Long targets run off the top of the screen, and a gold spacer sits after every 33.
- The switch is in the focus header and in Settings ("Counter").

### Bead string
- `BeadString`: filled beads for done, outlined beads for remaining, and a ring around the current bead.
- Runs right-to-left in Arabic. Past 24 beads it shows "+N".
- Used for lines in a werd, for repetitions on a line card, for the day's awrad, and as dividers (one or three beads).

### Werd card
- Accent dot, title, a preview of the current line, and a bead string of lines.
- A voice mark (RecordVoiceOver) when a voice has every line saved on the phone.
- Streak shown with the fire icon.

### Voice bar and chooser
- **Pause/play:** the bar's main button.
- **Voice chip:** names the active voice ("Charon · جاهز", "+N" when others are ready too). It opens the chooser sheet, which lists each voice with a bead strip of saved lines and the phone voice last.
- **Auto-count chip:** on by default. With it on, the tally follows the voice.

### Background session
- One read-aloud session per app process, run in a mediaPlayback foreground service.
- The media notification has pause, resume and stop.
- Home's Today hero and the reader both show and steer the same session.

### Text to read
A werd marked as a text (a poem, a sermon, a supplication said once) has no counter anywhere: the card shows a gold book
mark and its first lines instead of the bead string; the reader shows the text in plain cards with the moving read-aloud
marker and one "Mark as read" button. Texts stay out of goals, streaks and statistics.

### Book index and folded supplications
A book with a فهرس shows it first, as a foldable card of underlined links (page numbers at the end). A book's
supplications are folded to their title line; one opens at a time, with a gold outline. A link scrolls to its
supplication and opens it.

### Two readings
Where a two-pass scan's readings disagree, the proofreader shows a soft red panel: the text with the disputed words in
bold red, underlined, the other reading under it, and "Keep this reading" / "Use the other". The book's chips gain
"To check (n)".

## Do's and Don'ts

### Do:
- **Do** show every count as beads; the numeral sits beside or inside them.
- **Do** keep the tally and the voice in step: the label says the repetition being spoken, and the bead fills when it ends.
- **Do** test every new surface in all four palettes, light and dark.
- **Do** keep daily actions in the bottom half of the screen.

### Don't:
- **Don't** use emoji or Unicode glyphs (✦, 🔥) as ornaments or icons; draw beads or use Material icons.
- **Don't** add a second action colour, badges, confetti or gamified noise.
- **Don't** box the spoken word; mark it with the bead colour, bold and an underline.
- **Don't** replace the round counter with a bar or a ring percentage.

## Proofreading (1.15)
- **Two areas only.** The proofreader shows the original page and the editing box. Words that another reader or a helper
  questions are marked inside the editing box (red, underlined). Tapping one, or the arrows on the item's slim strip, opens
  a small list: what each reader read there, and each helper's suggestion, each labelled with where it came from
  ("Server · QARI-OCR 0.4", "Quran · البقرة 255", "Spelling"). "Keep this word" marks it gold. When every marked word is
  dealt with, the item is ticked. The full other readings are in a sheet (⋮ → The other readings), not on the screen.
- **The page follows the item.** Focusing an item's title or text scrolls the page so its lines sit at the top of the
  pane, fitted to the width (a little zoom when the item is short), with a band marking them. The ⌖ button brings it back
  after pinching. The split between the two areas is remembered.
- **Any reader, any time.** A two-pass scan's second reader can be any reader (built-in, the server's models, this phone's
  model, Gemini/Claude). A finished book can be proofread later (⋮ → Proofread with…) by more readers, optionally reading
  each item's part of the page again from a sharper rendering. Readings only add marks; they never change text, and
  never untick an item the user ticked.
- **Helpers suggest, never change.** The Quran (in the app) is matched against lines that quote it; a look-alike-letter
  spelling check; a small model asked which word fits. Uthmani spellings that only differ by the rasm (الصلوة/الصلاة) are
  not suggested.

## Page furniture and titles (1.15)
Running headers, page numbers, footers and footnotes are taken off each page before its supplications are found (by
position and by repetition across pages). A page number is never a count; footnote marks become ⁽١⁾ so "(٣)" stays a
count. Footnotes go to the item that marks them, folded under it and never read aloud. Text that runs over a page break is
joined to the item before it. Titles come from each item's own heading; when the book's title or one heading would title
almost every item, the item's heading inside its text (or its first words) is used instead (⋮ → Fix titles for books
already scanned).

## Counter size (1.15)
The tally view's counter is large, medium (default), slim (a bar with the count, undo and "+") or hidden (tap the text).
While a long text scrolls, the counter steps aside.
