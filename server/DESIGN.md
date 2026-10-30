---
name: Werd server
description: The family server's start page and dashboard, in Werd's deep green, cream and gold.
colors:
  cream-ground: "#FBF7EE"
  surface: "#FFFFFF"
  surface-sand: "#F5EEDD"
  ink: "#1D2520"
  muted-ink: "#66655A"
  hairline: "#EADFC4"
  hairline-soft: "#F1E8D3"
  werd-green: "#0E6B4E"
  green-mist: "#DCEDE3"
  board-green: "#08452F"
  board-rule: "#1E5B44"
  board-mute: "#A9C7B8"
  prayer-gold: "#C19A3E"
  lamp-gold: "#E9D7A6"
  gold-ink: "#4A3A10"
  gold-tint: "#F6ECD2"
  amber: "#A86B12"
  alarm-red: "#B3261E"
  red-tint: "#F9E1DF"
  slate-blue: "#3F6489"
typography:
  board-title:
    fontFamily: "Amiri, serif"
    fontSize: "32px"
    fontWeight: 700
    lineHeight: 1.15
  board-numeral:
    fontFamily: "Tajawal, system-ui, sans-serif"
    fontSize: "clamp(30px, 3.1vw, 44px)"
    fontWeight: 700
    lineHeight: 1.12
    letterSpacing: "-0.01em"
    fontFeature: "tnum"
  title:
    fontFamily: "Tajawal, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 700
  body:
    fontFamily: "Tajawal, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "Tajawal, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 500
  scripture:
    fontFamily: "Amiri, serif"
    fontSize: "19px"
    lineHeight: 1.9
rounded:
  control: "10px"
  panel: "14px"
  board: "18px"
  pill: "999px"
spacing:
  gutter-desktop: "28px"
  gutter-phone: "14px"
  section: "18px"
  panel-pad: "16px 18px"
components:
  button-primary:
    backgroundColor: "{colors.werd-green}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    height: "38px"
    padding: "0 16px"
  button-ghost:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    height: "38px"
  board-cell-lit:
    backgroundColor: "{colors.lamp-gold}"
    textColor: "{colors.board-green}"
    rounded: "{rounded.panel}"
  chip-active:
    backgroundColor: "{colors.werd-green}"
    textColor: "{colors.surface}"
    rounded: "{rounded.pill}"
  panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.panel}"
    padding: "16px 18px"
---
# Design System: Werd server

## Overview

**Creative North Star: "The Mosque Timetable Board"**

The server is read the way a masjid's prayer-time board is read: one deep-green board with large numerals, and the one that matters now lit in gold. Around it, everything is a calm working screen on cream paper with hairline rules. There are standard tables, chips, tabs and buttons, set in Tajawal, with Arabic as a first-class right-to-left language.

Expression is spent in three places only: the board, the Amiri wordmark and titles, and the faint girih geometry inside the board's title block. Everything else stays quiet so the activity log and the work table can be scanned.

**Key Characteristics:**
- A deep-green board of five numerals. The busiest resource is lit in gold, and a critical state is carried by a red tag.
- Cream ground, white panels, hairline borders, tabular numerals.
- AR/EN, one language at a time, with full RTL mirroring.
- Rail navigation on desktop, bottom navigation on the phone, and Home always one tap away.

## Colors

The palette is Werd's own: the green of a prayer mat, cream paper and lamp gold, with red and amber held back for real alarms.

### Primary
- **Werd Green** (#0E6B4E): primary buttons, active navigation, selected chips, the CPU line in charts, positive status badges.
- **Board Green** (#08452F): the load board, the start-page hero and the login header. Always carries light text.

### Secondary
- **Prayer Gold** (#C19A3E) and **Lamp Gold** (#E9D7A6): the lit board cell, the Amiri titles on green, the memory line in charts, the focus ring and text selection.

### Neutral
- **Cream Ground** (#FBF7EE): page background.
- **Surface** (#FFFFFF) and **Surface Sand** (#F5EEDD): panels and quiet fills.
- **Ink** (#1D2520) and **Muted Ink** (#66655A): text.
- **Hairline** (#EADFC4) and **Hairline Soft** (#F1E8D3): borders and row rules.

### State
- **Alarm Red** (#B3261E): errors, critical resource state, destructive hover.
- **Amber** (#A86B12): open reports and other warnings.
- **Slate Blue** (#3F6489): downloads and voice events.

### Named Rules
**The One Lamp Rule.** Exactly one board cell is lit in Lamp Gold: the busiest of processor, memory and disk. Alarm is never shown by changing the lamp's colour. It is shown by a red tag and red numerals on the cell that needs it, even when that cell is not the lit one.

## Typography

**Display Font:** Amiri (wordmark, board title, scripture text)
**Body Font:** Tajawal (all UI)

**Character:** Amiri gives the devotional voice; Tajawal is a clean bilingual workhorse with tabular numerals for every figure.

### Hierarchy
- **Board title** (Amiri 700, 32px, 1.15): only on the green board and the start-page hero. In English it switches to Tajawal 700 at 26px.
- **Board numeral** (Tajawal 700, clamp(30px, 3.1vw, 44px), tabular): the five load readings.
- **Title** (Tajawal 700, 16px): panel headings.
- **Body** (Tajawal 400, 15px, 1.55): rows, sentences.
- **Label** (Tajawal 500, 12.5–13px): table headers, sub-lines, chips.
- **Scripture** (Amiri, 19px, 1.9): lines of a book in the viewer and in reports.

## Layout

On desktop, a 92px rail sits on the inline-start edge. Next to it are a sticky 64px top bar (search, AR/EN, sign out) and content with a 28px gutter, a 1480px maximum width and an 18px gap between sections. The dashboard places the board full-width, then alerts, then two columns: Work and the load chart (1.45fr), and Activity (1fr).

At 1100px and below, the board's title spans the full row above its five cells, and the columns stack. At 760px and below, the rail becomes a fixed bottom navigation, the board shows three cells per row, tables become stacked rows, and the gutter is 14px.

## Elevation & Depth

The system is flat, with tonal panels: white panels on cream with a 1px hairline. Only two things carry shadow: the board (`0 10px 30px rgba(8,69,47,.16)`) and floating layers such as search results, toasts and the book drawer (`0 8px 28px rgba(8,69,47,.07)`).

## Shapes

- Controls have 10px radii, panels 14px, and the board and hero 18px.
- Status badges and chips are pills.
- Avatars are circles carrying an initial.
- The girih motif (an eight-point star with a circle) appears only at 8% opacity inside green fields.

## Components

### Buttons
- **Primary:** Werd Green with white text, 38px tall, 10px radius, weight 700.
- **Ghost:** white with a hairline border; the border and text turn green on hover.
- **Danger:** ghost style; the border and text turn red on hover.
- **Icon button:** 34px square with a hairline border.
- **Focus:** a 2px Prayer Gold outline.

### Chips
Pill-shaped filters with a count. The active chip is filled Werd Green. The errors chip turns red when active.

### Board (signature)
The board has a title block (Amiri title, live line, machine, totals) followed by five cells: Processor, Memory, Disk, Phones and Requests.
- Each cell shows a label, a numeral with a small unit, a sub-line and a 15-minute trace (line only) or a used/free bar.
- The lit cell is Lamp Gold with Board Green ink.
- A cell at 90% or above carries a red tag; disk also raises a red notice at the top of the dashboard.

### Tables
- Header text is 12.5px Muted Ink; row rules are Hairline Soft.
- Columns are sortable through a button in the header carrying `aria-sort`.
- On the phone, tables become stacked rows.

### Navigation
- The rail shows a 54×30 pill around each icon; the active pill is Green Mist with Werd Green text.
- The bottom nav repeats the four tabs plus Home.
- The Library tab carries a red count badge for open reports.

## Do's and Don'ts

### Do:
- **Do** keep every figure in Tajawal with tabular numerals and Western digits.
- **Do** isolate Arabic names inside English sentences with `<bdi>` (and the reverse).
- **Do** use the Arabic plural forms (one, two, few, many) for every counted noun.
- **Do** keep Home reachable from every page, including the login screen.

### Don't:
- **Don't** light more than one board cell, or recolour the lamp to signal alarm.
- **Don't** use emoji or Unicode glyphs as icons; use the inline SVG set.
- **Don't** put kickers or eyebrow labels above headings.
