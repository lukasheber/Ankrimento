# Ankrimento roadmap

Goal: the mobile app does what desktop Incremento does, on the same data,
in a way that suits a phone or tablet. Desktop features are listed from
upstream's `MANUAL.md` (the section number is in brackets).

Legend:

- ✅ done and checked on a device
- 🟡 partial: works, but missing parts or not yet mobile-shaped
- ⬜ to do
- ➖ not planned on mobile (reason given)
- ⏸ deferred: decided to wait (reason given)

Mobile principles that guide every item:

- **One learning loop.** No "close this, then open the next". Every element
  ends with a choice in the footer, and Back returns to the previous one,
  as in SuperMemo.
- **Thumb first.** Primary actions sit at the bottom. Tools go in the top
  bar, and everything else goes in sheets, never in rows of small buttons.
- **The document owns the screen.** A tap hides the bars.
- **Same data as desktop.** Settings that desktop stores per document
  (text size, appearance, priority, progress) go through upstream code, so
  they sync. Device-only presentation choices (typeface, margins) stay on
  the device.

---

## 1. Learning loop and sessions [3, 4]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Start a session (upstream session selection, Incremento Session filtered deck) | |
| ✅ | Topics answered in their reader: More / Same / Less with due intervals | |
| ✅ | Next element opens straight away, without passing through the reviewer | |
| ✅ | Back to the previous element (one undo, only while nothing changed since) | |
| ✅ | Topic actions: Read to here (PDF, EPUB), Done, Postpone, Revisit 3/6/12 months | A row of chips above the answers |
| ✅ | Revisit → Choose date… (date picker, tomorrow onwards) | Upstream's revisit with a date, so desktop keeps it out of reading sessions until then |
| ⬜ | Settings to show or hide Done, Postpone and Extract in the bar, as desktop's Review buttons | |
| ✅ | **Fixed "Incremento" entry on the main screen** that is the session's filtered deck: Continue while it has cards, Start when empty, gear for session settings | The deck's own row is hidden from the list |
| 🟡 | When a session finds nothing to study, say why and offer the session settings | Dialog with upstream's message and a Session settings button; not yet seen on a device (document topics always qualify, as upstream intends) |
| ✅ | Plain-text topics in a reader (no "Show answer"): the note as its template renders it, Aa settings, Extract on selection, review bar | |
| ✅ | Items in the same learning screen as topics: question, Show answer, then Anki's grades (or Fail/Pass when enabled), with intervals, Back and the priority chip | Session deck only; cards with sound, typed answers, math or image occlusion still use the reviewer |
| ⬜ | Items with sound, typed answers, math (MathJax) and image occlusion in the learning screen | |
| ⬜ | Leech notice when an item becomes a leech in the learning screen | The reviewer shows one |
| ➖ | Swipe between elements | Sideways swipe turns pages in the readers; moving between elements stays on the footer buttons, where a stray swipe cannot answer a topic |
| ✅ | Session settings: card count, topic/item and document mix, random/priority, card states, top-up | In the settings screen |
| ⬜ | Session presets: save, load, rename, delete | |
| ⬜ | Advanced scheduler options: quotas, tag quotas, filters, card states, priority order, scope | One settings screen with sections |
| ⬜ | Session preview before starting | |
| 🟡 | Branch study from the knowledge tree | Works on the emulator; did not start on the tablet; to investigate |
| ✅ | Desktop review hooks: review time, topic A-factor, custom schedule, learning leech tags | |
| ⬜ | New AnkiDroid study screen (ReviewerFragment) opens readers like the legacy one | Buttons done; readers not yet |

## 2. Main screen and navigation [2, 9]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Library screen (from the Incremento card): Continue reading on this device, documents by kind with position and priority, Add | |
| ⬜ | Move the remaining deck-list menu items (knowledge tree, statistics, sync, profile) into the hub and a settings screen | |
| ⬜ | Library search, sort (title, priority, recent) and covers | |
| ✅ | Add sheet: PDF, EPUB, web or video link, writing | Audio still to add |
| ✅ | Share a link from any app to Incremento (web or video topic) | |
| ⬜ | Search ALL: highlights, sources, document text, cards [9] | |
| 🟡 | Knowledge tree [9]: browse, study branch, priority | Dialog list today; needs a full screen with expand/collapse, reveal current card, go to parent |
| ➖ | Command palette, keyboard shortcuts [14] | Replaced by visible tools and gestures; hardware keyboard shortcuts maybe later |

## 3. Readers [6, 7, 8]

### PDF

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Upstream pdf.js viewer, page restore, daily page limit | |
| ✅ | Page bar: previous/next, scrub slider, "Page X of N" | |
| ✅ | View sheet: zoom, fit to width, page colour (Original/Dark/Night, synced) | |
| ✅ | Long-press selection, then Extract from the selection menu | |
| ✅ | Highlight left by extraction; mark read to here | |
| ✅ | Tap to hide the bars | |
| 🟡 | Find in document | Opens upstream's panel; needs a native search bar with results |
| ✅ | Contents (outline) sheet: nested entries with page numbers, the current part marked | Through the viewer's own contents jump |
| 🟡 | Bookmarks, annotations, highlight notes, colours | Reachable only through upstream's desktop panel; need native sheets |
| ⬜ | Review all cards attached to the document [6] | |
| ⬜ | Cross-references and page links [6] | |
| ⬜ | DjVu documents [6] | |
| ⬜ | Searchable PDF text / OCR status [6, 13] | |
| ⬜ | Pinch zoom | Two-finger zoom on the page, through upstream's zoom so it is saved per document |
| ⬜ | **Reflow** for small screens: the page's text re-flowed to the screen width, EPUB-style | Only if extracts, highlights and annotations made in reflow map back to their exact places in the original PDF layout (and show there on desktop); otherwise not done |
| ✅ | Swipe sideways to turn pages (while the page fits the width) | |
| ✅ | Pull past the end of a page for the next one, past its top for the previous ("Release for the next page") | Through upstream's page turns, so its daily limit applies |

### EPUB

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Upstream page script, position restore, read marker | |
| ✅ | Aa sheet: text size and page colour (synced per book); typeface, spacing, margins (device) | |
| ✅ | Contents sheet with chapter titles | |
| ✅ | Long-press, then Extract / Highlight | |
| ✅ | Progress strip (chapter, % of book); tap to hide the bars | |
| ⬜ | Paged mode (horizontal page turns) as an option to scrolling, in the Aa sheet | |
| ✅ | Pull past the end of a chapter for the next, past its start for the previous | |
| ✅ | Swipe sideways to move one screen on or back (a couple of lines stay in view); it never changes chapter | |
| ⬜ | Search in book | |
| 🟡 | Links and footnotes inside the book | Opened externally or ignored |
| ⬜ | Highlights list and highlight notes | |

### Web

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Page with upstream bridge; scroll position saved; extract selection | |
| ✅ | Back steps through page history first | |
| ✅ | **Import a web page as an article**: clean text read in the EPUB reader (Aa sheet, contents, progress, extracts), from the Library's Add or the share sheet | Upstream's page-to-Markdown fetch and Markdown document import, so desktop reads it too |
| ⬜ | Images in imported articles | Upstream's converter turns images into text links ("[Image: …](url)"); download them or drop the links |
| ⏸ | Webpage to PDF [5] | Deferred in favour of the HTML / plain text import above |
| ⬜ | Web extract anchors (jump back to the extract's place) | |

### Video and audio [7, 7A]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | YouTube (upstream player page) and local files | |
| ✅ | Position resume and watch time, as desktop saves them | |
| ⬜ | Vimeo | |
| ✅ | Extract at the current timestamp: the front links back to that moment, as desktop's "Add Card at this point" | |
| 🟡 | Native controls: Back 10 seconds | Play/pause, speed and seek still come from the player itself |
| ✅ | Titles for shared links: YouTube's title, or the page's own title | Looked up when a link comes without one; the URL stays if that fails |
| ⬜ | Open a video extract's link at its moment, on mobile | Desktop opens it; the link is upstream's pycmd |
| ⬜ | Video bookmarks | |
| ⬜ | Captions (target/reference) | |
| ➖ | Create Mobile Clip, Download Local Copy | Need FFmpeg/yt-dlp; desktop makes clips that sync as media |
| 🟡 | Audio cards | Ordinary Anki media, played by AnkiDroid itself; not yet checked |

### Writing, Markdown and local files [5, 8]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Writing card editor with autosave and word count (upstream backups) | |
| ⬜ | Markdown preview / formatting toolbar | |
| ⬜ | Markdown documents (import, read as EPUB-like) [5] | |
| ⬜ | Local file cards [8] | Open with another app |

## 4. Extraction and cards [11]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Extract to a new card in a bottom sheet: Item/Topic, front/back, note type, priority slider; Keep extracting keeps the draft | |
| ✅ | Priority editor (slider, 0 to 100) | |
| ✅ | **Priority always in reach on topics and items**: a "P 50" chip on screen, one tap to change it | Done for topics and items (chip in the top bar) |
| ⬜ | "Keep extracting" queue (several extracts, then one review) | Button exists; needs mobile flow |
| ⬜ | Extract card from the current reviewer card [11] | |
| ⬜ | Add Card dock / card from scratch with priority [11] | |
| ⬜ | Batch Q/A cards [11] | |
| ⬜ | PDF snapshot previews on cards [13] | Mobile already shows snapshots created on desktop |

## 5. Statistics and focus [10]

| Status | Feature | Mobile notes |
|---|---|---|
| 🟡 | Statistics | Dialog with 7-day summary; needs a screen with charts and history |
| ⬜ | Focus timer | Persistent chip in the reader top bar |

## 6. Sync, data and safety [12, 13]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Cards and media via AnkiWeb (AnkiDroid's own sync) | |
| ⏸ | Incremento data via a shared folder (SAF: Drive, Dropbox, …), conflict-safe snapshots | Works on the device; further sync work waits for an agreement with upstream's author on the sync format |
| ⏸ | Desktop "Ankrimento Sync" add-on | Built and API-checked; deferred until there is an agreement with upstream's author |
| ⏸ | Sync status on the hub (last sync, conflicts, Sync now) | With the sync work above |
| ⬜ | Full backup and restore [12] | |
| ⬜ | Support bundle [12] | |
| ⬜ | Clean up unused data [13] | |
| ⬜ | Card-format updates notice [13] | |

## 7. Import [5]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | PDF, EPUB from the device | |
| ✅ | Web and video links (menu and share sheet) | |
| ⏸ | SuperMemo collection import [5] | Deferred |
| ⬜ | Audio import | |
| ➖ | Browser extension companion [5] | Desktop browser only; the share sheet covers mobile |

## 8. Settings, language and quality [13]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Settings screen (the Incremento card's gear): session, review, extraction and priority, reading, this device's profile | Upstream's own settings, normalized by upstream on every change |
| ⬜ | More of upstream's settings (writing, PDF details, shortcuts that make sense on a phone) | |
| ⬜ | Translations: upstream's locales for backend messages are shipped; Android strings are English only | |
| ⬜ | Getting Started guide (first run) | |
| ➖ | Dependency installer [13] | Desktop tools (FFmpeg, OCR) |
| 🟡 | Startup time: Python cold start about 20 s on the first launch, about 3 s after warm-up | Warm-up runs on app start |
| 🟡 | Tests: Python tests against the real backend, reviewer Robolectric tests, on-device smoke test | Add UI tests for the learning loop |
| 🟡 | Tablet layout: works full screen | Two-pane library plus reader to consider |

---

## Order of work

1. ~~Fixed Incremento entry, priority chip, plain-text topics and items in
   the learning screen~~ (done).
2. ~~Page turning by swipe and pull-past-the-end in PDF and EPUB~~ (done).
3. ~~Extract bottom sheet and video extract at timestamp~~ (done).
4. ~~Library with Continue reading and Add; web pages as articles; link
   titles~~ (done).
5. ~~Settings screen~~ (done); session presets and advanced options.
6. Native PDF tools: search, contents, bookmarks, highlights/notes.
7. Knowledge tree screen, Search ALL, statistics screen, focus timer.
8. Backup/restore, translations.

Deferred: sync add-on and other sync work (until agreed with upstream's
author), SuperMemo import, Webpage to PDF.
