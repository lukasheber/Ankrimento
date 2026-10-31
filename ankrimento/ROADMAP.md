# Ankrimento roadmap

Goal: the mobile app does what desktop Incremento does, on the same data,
in a way that suits a phone or tablet. Desktop features are listed from
upstream's `MANUAL.md` (the section number is in brackets).

Legend:

- ✅ done and checked on a device
- 🟡 partial: works, but missing parts or not yet mobile-shaped
- ⬜ to do
- ➖ not planned on mobile (reason given)

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
| ✅ | Topic actions: Done, Postpone, Revisit 3/6/12 months | In the review bar's menu |
| ✅ | **Fixed "Incremento" entry on the main screen** that is the session's filtered deck: Continue while it has cards, Start when empty, gear for session settings | The deck's own row is hidden from the list |
| ⬜ | When a session finds nothing to study, say why (e.g. "Documents within topics: 0%") and offer the session settings | Today: upstream's short toast |
| ⬜ | Plain-text topics in a simple reader (no "Show answer") | Today they open in the reviewer |
| ⬜ | Items in the same learning screen as topics: question, Show answer, then Fail/Pass or Anki's grades | Removes the last hand-off to the legacy reviewer |
| ⬜ | Swipe between elements (left = next with the default choice, right = Back) | |
| 🟡 | Session settings: card count, topic/item mix | Basic dialog only |
| ⬜ | Session presets: save, load, rename, delete | |
| ⬜ | Advanced scheduler options: quotas, tag quotas, filters, card states, priority order, scope | One settings screen with sections |
| ⬜ | Session preview before starting | |
| 🟡 | Branch study from the knowledge tree | Works on the emulator; did not start on the tablet; to investigate |
| ✅ | Desktop review hooks: review time, topic A-factor, custom schedule, learning leech tags | |
| ⬜ | New AnkiDroid study screen (ReviewerFragment) opens readers like the legacy one | Buttons done; readers not yet |

## 2. Main screen and navigation [2, 9]

| Status | Feature | Mobile notes |
|---|---|---|
| ⬜ | Incremento hub: session card with Continue/Start, Continue reading, Library | Replaces about 10 overflow menu items |
| ⬜ | Library: all documents with type filters, progress and priority; tap to read | Covers desktop's Quick Open and Bookshelf |
| ⬜ | Add sheet: PDF, EPUB, web link, video link, writing, audio | FAB on the hub |
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
| ⬜ | Contents (outline) sheet | Upstream sends `incremento_pdf_contents`, which isn't handled yet |
| 🟡 | Bookmarks, annotations, highlight notes, colours | Reachable only through upstream's desktop panel; need native sheets |
| ⬜ | Review all cards attached to the document [6] | |
| ⬜ | Cross-references and page links [6] | |
| ⬜ | DjVu documents [6] | |
| ⬜ | Searchable PDF text / OCR status [6, 13] | |
| ⬜ | Pinch zoom | |

### EPUB

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Upstream page script, position restore, read marker | |
| ✅ | Aa sheet: text size and page colour (synced per book); typeface, spacing, margins (device) | |
| ✅ | Contents sheet with chapter titles | |
| ✅ | Long-press, then Extract / Highlight | |
| ✅ | Progress strip (chapter, % of book); tap to hide the bars | |
| ⬜ | Paged mode (horizontal page turns) as an option to scrolling | |
| ⬜ | Search in book | |
| 🟡 | Links and footnotes inside the book | Opened externally or ignored |
| ⬜ | Highlights list and highlight notes | |

### Web

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Page with upstream bridge; scroll position saved; extract selection | |
| ✅ | Back steps through page history first | |
| ⬜ | Reader mode (clean article text, Aa sheet like EPUB) | |
| ⬜ | Webpage to PDF [5] | |
| ⬜ | Web extract anchors (jump back to the extract's place) | |

### Video and audio [7, 7A]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | YouTube (upstream player page) and local files | |
| ✅ | Position resume and watch time, as desktop saves them | |
| ⬜ | Vimeo | |
| ⬜ | Native controls: play/pause, ±10 s, speed, seek | |
| ⬜ | Extract at the current timestamp (card tied to the video moment) | Desktop's main video action |
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
| 🟡 | Extract to a new card: front/back, note type, topic/item, priority | Works; AlertDialog to become a Material bottom sheet |
| ✅ | Priority editor (slider, 0 to 100) | |
| 🟡 | **Priority always in reach on topics and items**: a "P 50" chip on screen, one tap to change it | Topic readers: done (chip in the top bar). Items: needs the learning screen (today it is in the reviewer's menu) |
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
| ✅ | Incremento data via a shared folder (SAF: Drive, Dropbox, …), conflict-safe snapshots | |
| 🟡 | Desktop "Ankrimento Sync" add-on | Built and API-checked; not yet run inside real desktop Anki |
| ⬜ | Sync status on the hub (last sync, conflicts, Sync now) | |
| ⬜ | Full backup and restore [12] | |
| ⬜ | Support bundle [12] | |
| ⬜ | Clean up unused data [13] | |
| ⬜ | Card-format updates notice [13] | |

## 7. Import [5]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | PDF, EPUB from the device | |
| ✅ | Web and video links (menu and share sheet) | |
| ⬜ | SuperMemo collection import [5] | Backend is Python, so it can run as on desktop |
| ⬜ | Audio import | |
| ➖ | Browser extension companion [5] | Desktop browser only; the share sheet covers mobile |

## 8. Settings, language and quality [13]

| Status | Feature | Mobile notes |
|---|---|---|
| ⬜ | Settings screen: upstream's extraction, review, topic and writing options, plus sync folder and profile | Today spread over menu dialogs |
| ⬜ | Translations: upstream's locales for backend messages are shipped; Android strings are English only | |
| ⬜ | Getting Started guide (first run) | |
| ➖ | Dependency installer [13] | Desktop tools (FFmpeg, OCR) |
| 🟡 | Startup time: Python cold start about 20 s on the first launch, about 3 s after warm-up | Warm-up runs on app start |
| 🟡 | Tests: Python tests against the real backend, reviewer Robolectric tests, on-device smoke test | Add UI tests for the learning loop |
| 🟡 | Tablet layout: works full screen | Two-pane library plus reader to consider |

---

## Order of work

1. ~~Fixed Incremento entry on the main screen~~ (done). Priority chip on
   items. Explain empty sessions.
2. Plain-text topics and items in the learning screen, so the loop never
   leaves it, plus swipe.
3. Extract bottom sheet, Keep extracting, and video extract at timestamp.
4. Hub and Library (Continue reading, Add sheet, sync status).
5. Settings screen and session presets/advanced options.
6. Native PDF tools: search, contents, bookmarks, highlights/notes.
7. Knowledge tree screen, Search ALL, statistics screen, focus timer.
8. Backup/restore, SuperMemo import, translations.
