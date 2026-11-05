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
| ✅ | Topic actions: Done, Postpone, Revisit 3/6/12 months or a chosen date | A row of chips above the answers. Mark read to here is in the reader's ⋮ menu |
| ✅ | Revisit → Choose date… (date picker, tomorrow onwards) | Upstream's revisit with a date, so desktop keeps it out of reading sessions until then |
| ✅ | Settings to show or hide Done, Postpone and Extract in the bar, as desktop's Review buttons | Settings → Review: desktop's review buttons group, Done, Postpone, Extract (the extract tool; the selection menu keeps Extract) |
| ✅ | **Fixed "Incremento" entry on the main screen** that is the session's filtered deck: Continue while it has cards, Start when empty, gear for settings, ⋮ for Library, Add, Knowledge tree, Statistics (and Sync now when a folder is set) | The deck's own row is hidden from the list; on a tablet its side panel has no Study button |
| 🟡 | When a session finds nothing to study, say why and offer the session settings | Dialog with upstream's message and a Session settings button; not yet seen on a device (document topics always qualify, as upstream intends) |
| ✅ | Plain-text topics in a reader (no "Show answer"): the note as its template renders it, Aa settings, Extract on selection, review bar | |
| ✅ | Items in the same learning screen as topics: question, Show answer, then Anki's grades (or Fail/Pass when enabled), with intervals, Back and the priority chip | Session deck only |
| ✅ | Items with sound, typed answers, math (MathJax) and image occlusion in the learning screen | The card is AnkiDroid's own rendering and playback, supplied by the app (`AnkrimentoApp.cardDisplay`): its play buttons and video players, its media player with text to speech, its box for a typed answer and Anki's comparison, as in its study screen. Image occlusion: Anki's own review script and styles; math: AnkiDroid's MathJax. All checked on the tablet |
| 🟡 | Leech notice when an item becomes a leech in the learning screen | A notice after the answer (and whether it was suspended); not yet seen on a device |
| ➖ | Swipe between elements | Sideways swipe turns pages in the readers; moving between elements stays on the footer buttons, where a stray swipe cannot answer a topic |
| ✅ | Session settings: card count, topic/item and document mix, random/priority, card states, top-up | In the settings screen |
| ✅ | Session presets: save, load, rename, delete | Settings → Session → Preset, stored as desktop stores them; each preset keeps its own session deck (hidden from the deck list like the base one, renamed with its preset, emptied when its preset is deleted) |
| ✅ | Advanced scheduler options: quotas, tag quotas, filters, card states, priority order, scope | Settings → Session → Advanced options: topics/items filters (checked by Anki before saving), tag quotas with Other cards, content types scheduled first, documents outside tag quotas, strict enforcement, scope (card states are in Session), Order values on tags and content types (desktop's priority order), and the phase order with each phase on or off (under strict enforcement), named as desktop names them. Desktop's slider locks and groups are not offered: they only link the desktop dialog's sliders, and the scheduler never reads them |
| ✅ | Session preview before starting | The Incremento card's ⋮ → Preview session: the cards upstream's scheduler picks with the current settings (as desktop's session dialog previews them), each with its type, pick mode and tag; Start this session starts with exactly those cards while the settings stay the same, as desktop's preview override does |
| ✅ | Branch study from the knowledge tree | Checked on the tablet; an empty branch explains why with upstream's message |
| ✅ | Desktop review hooks: review time, topic A-factor, custom schedule, learning leech tags | |
| ✅ | New AnkiDroid study screen (ReviewerFragment) opens readers like the legacy one | Checked on the tablet: a topic opens in its reader over the new screen; answers move on from reader to reader (the screen opens nothing more while one of its readers is open); a rating handed back is answered there; the reader's Back ends the session and closes the screen |

## 2. Main screen and navigation [2, 9]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Library screen (from the Incremento card): Continue reading on this device, documents by kind with position and priority, Add | |
| ✅ | Deck-list overflow menu free of Incremento items: each has its place on the card, in the library or in settings | |
| ✅ | Library search, sort (title, priority, recent) and covers | Title search; sort by priority (the configured direction, as desktop's bookshelf), title, or recently read on this device; PDF and EPUB covers from desktop's cover fields |
| ✅ | Add sheet: PDF, EPUB, Markdown, audio, web or video link, writing | |
| ✅ | Share a link from any app to Incremento (web or video topic) | |
| ✅ | Search ALL: highlights, sources, document text, cards [9] | From the Incremento card's ⋮ and the Library: upstream's own search with desktop's filters (as chips, saved as desktop saves them), results grouped as desktop groups them; a result opens its PDF page with the words highlighted, its EPUB section, or its card. PDF text is off by default, as on desktop |
| ✅ | Knowledge tree screen [9]: fold/unfold branches, kind and priority per node; Open, Study this branch, Priority | From the Library's top bar and the menu |
| ✅ | Knowledge tree: reveal the current card, go to parent, search, postpone a subtree | Readers' ⋮ → Show in knowledge tree opens the tree with the card's branch unfolded and the card marked; the node sheet's Go to parent; the top bar's search (upstream's, titles and note text) reveals a result; Postpone this branch… shows upstream's simulation with the preset desktop's postpone dialog would load (the branch's, else the default, else upstream's defaults), then applies it |
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
| ✅ | Find in document | A search sheet with the matching pages and excerpts; a match opens with its words highlighted, then a previous/next bar. Upstream's text index is filled by the viewer's pdf.js on the device (desktop uses PyMuPDF), through upstream's indexer, so Search ALL finds it too |
| ✅ | Contents (outline) sheet: nested entries with page numbers, the current part marked | Through the viewer's own contents jump |
| ✅ | Bookmarks, annotations, highlight notes, colours | Bookmarks and highlights sheet (⋮): bookmark the page, open or delete bookmarks; highlights by page with their colour (upstream's eight) and note, each opened, recoloured, noted or deleted; Highlight on the selection menu; upstream's six annotation styles (highlight, underline, squiggly, dashed, dotted, strikethrough) in each highlight's dialog, drawn by the viewer; a comment on each bookmark |
| ✅ | Review all cards attached to the document [6] | PDF and EPUB readers' ⋮ → Review cards from this document: desktop's choices (all or due, topics/items, whole document or up to here, order) with a live count, selected by upstream's media review; Review builds desktop's PDF/EPUB review deck and opens AnkiDroid's reviewer. Checked on the tablet |
| ✅ | Cross-references and page links [6] | The PDF's own links (to a figure, a citation, another page) are the viewer's: they jump, keep a way back and preview; the preview's size and zoom are kept per profile as on desktop. Page links (desktop's connections between PDF pages): ⋮ → Page links lists the page's links from either end, each opened (at its page, the other PDF's reader) or removed; Link this page… picks a PDF from the library, which opens only to choose the place (nothing it does is saved for that PDF), then Link to this page. The viewer's own Page links panel, when its controls are shown, uses the same links. Desktop's daily page limit bounds where links point. Checked on the tablet. Not here: desktop's reference window beside the reader (the phone opens the other reader) and recordings linked to a page |
| ⬜ | DjVu documents [6] | |
| ✅ | Searchable PDF text / OCR status [6, 13] | The device reads each PDF's text for Find and Search ALL; a PDF without a text layer is named as such in Find, which says Incremento on a computer can add one with OCR when importing (Tesseract is desktop's) |
| ✅ | Pinch zoom: the page follows the fingers, then upstream's zoom applies (saved per document); Fit to width is always exactly 100% | |
| ⬜ | **Reflow** for small screens: the page's text re-flowed to the screen width, EPUB-style | Only if extracts, highlights and annotations made in reflow map back to their exact places in the original PDF layout (and show there on desktop); otherwise not done |
| ✅ | Swipe sideways to turn pages (while the page fits the width) | |
| ✅ | Pull past the end of a page for the next one, past its top for the previous ("Release for the next page") | Through upstream's page turns, so its daily limit applies |

### EPUB

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Upstream page script, position restore, read marker | The position is saved when leaving or moving on, even right after a scroll. The read marker is saved as desktop saves it but not drawn on the phone (a toast confirms) |
| ✅ | Aa sheet: text size and page colour (synced per book); typeface, spacing, margins (device) | |
| ✅ | Contents sheet with chapter titles | |
| ✅ | Long-press, then Extract / Highlight | |
| ✅ | Progress strip (chapter, % of book); tap to hide the bars | |
| ✅ | Paged mode as an option to scrolling, in the Aa sheet | Aa → Page turning: Scroll or Pages (this device). In Pages a swipe or a tap on the outer quarter turns a whole screen, the next page starting on the line the edge cut; past the end or start it turns the chapter. The chapter stays one vertical page underneath, so upstream's saved positions, highlights, read mark and extracts work unchanged (sideways pages would break them) |
| ✅ | Pull past the end of a chapter for the next, past its start for the previous | |
| ✅ | Swipe sideways to move one screen on or back (a couple of lines stay in view); it never changes chapter | |
| ✅ | Search in book | Find in book: chapters matching (upstream's text index) with excerpts; a result opens its chapter with the words found and selected by the page script, as desktop does, then previous/next. Search ALL's EPUB results open the same way |
| ✅ | Links and footnotes inside the book | Links follow on a tap (desktop's links toggle, always on), resolved by upstream's own resolver: a footnote or other short passage shows in a sheet over the page with Go to note; other links open their chapter at the anchor; Back to where you were (chip or Back) returns to the exact place. Web links open in the browser; unsafe ones are refused with upstream's message |
| ✅ | Highlights list and highlight notes | Highlights (⋮): the book's highlights by chapter with colour, style and note; one opens its chapter with its words found, or is recoloured, restyled, noted or deleted, the page following through its own hooks. A colour or style chosen on the page's own menu is kept, as desktop keeps it |

### Web

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Page with upstream bridge; scroll position saved; extract selection | |
| ✅ | Back steps through page history first | |
| ✅ | **Import a web page as an article**: clean text read in the EPUB reader (Aa sheet, contents, progress, extracts), from the Library's Add or the share sheet | Upstream's page-to-Markdown fetch and Markdown document import, so desktop reads it too |
| ✅ | Images in imported articles | Upstream's converter turns images into links; the phone downloads each one beside the article first (through upstream's public-network fetch, within its import limits), so upstream's Markdown import stores the pictures with the document, for desktop too. One that can't be fetched stays upstream's link. Checked on the tablet with a Wikipedia article |
| ⏸ | Webpage to PDF [5] | Deferred in favour of the HTML / plain text import above |
| ✅ | Web extract anchors (jump back to the extract's place) | A web extract carries desktop's link back to the page it came from (its URL, unless the extract source links setting turns web links off); tapping it opens the web topic at that page. Checked on the tablet |

### Video and audio [7, 7A]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | YouTube (upstream player page) and local files | |
| ✅ | Position resume and watch time, as desktop saves them | |
| ✅ | Vimeo | Played in Vimeo's own player page, as desktop's dock loads it; position, watch time, bookmarks, ±10 seconds and speed work through its video. A shared Vimeo link becomes a video topic, as on desktop. Checked on the tablet |
| ✅ | Extract at the current timestamp: the front links back to that moment, as desktop's "Add Card at this point" | |
| ✅ | Native controls: back and forward 10 seconds, desktop's speeds (0.75× to 2×) | In the reader's menu, for YouTube, Vimeo and local files; play/pause and the seek bar are the player's own. Checked on the tablet |
| ✅ | Titles for shared links: YouTube's title, or the page's own title | Looked up when a link comes without one; the URL stays if that fails |
| ✅ | Open an extract's link back to its source, on mobile | Extracts made on the phone carry desktop's link back, as desktop's fill adds it (unless the extract source links setting turns a kind off): web page, PDF page (with the excerpt found and highlighted), EPUB chapter and place, video moment. The links open their reader from cards in the readers and in AnkiDroid's reviewer. Checked on the tablet for web, PDF and EPUB. The EPUB place is the scroll position; desktop also focuses the selection by its offset |
| ✅ | Video bookmarks | Desktop's video bookmarks: keep the moment playing, open one (playback moves there), comment or delete it; desktop's labels (0:19). Checked on the tablet |
| 🟡 | Captions (target/reference) | YouTube's and Vimeo's own captions show in their players. Desktop's paired subtitle files (target and reference language) are not shown yet: they reach the phone only with Incremento's sync, which is deferred |
| ➖ | Create Mobile Clip, Download Local Copy | Need FFmpeg/yt-dlp; desktop makes clips that sync as media |
| ✅ | Audio cards | Ordinary Anki sound plays through AnkiDroid's own player, in its reviewer and in the learning screen (checked on the tablet). Desktop's audio topics play in their card's own player, which seeks (media is served in ranges) and resumes where listening stopped |

### Writing, Markdown and local files [5, 8]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Writing card editor with autosave and word count (upstream backups) | |
| ✅ | Markdown preview / formatting toolbar | Desktop's formatting buttons (heading, bold, italic, lists, quote, code block, rule) in a row above the keyboard, with desktop's labels and its own rewriting of the selection; Preview shows desktop's rendering of the text. Checked on the tablet. Not here: desktop's font size, wrap, focus mode, marker, bookmarks and backups buttons |
| ✅ | Markdown documents (import, read as EPUB-like) [5] | Add → Markdown document: desktop's Markdown import makes a study document read in the EPUB reader (its headings become chapters). Checked on the tablet. Android's file picker hands over the file alone, so its pictures in nearby files are left as their alt text, as desktop's import does with any image it can't read |
| ⬜ | Local file cards [8] | Open with another app |

## 4. Extraction and cards [11]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Extract to a new card in a bottom sheet: Item/Topic, front/back, note type, priority slider | Add card (main button); Add later (the queue, added when the document is left) and Select more text (closes the sheet; the next selection joins this card), each explained on the sheet and confirmed after ("Card added under …"). Note type: desktop's extract note type setting when set, otherwise the one last used for that kind of document |
| ✅ | Priority editor (slider, 0 to 100) | |
| ✅ | **Priority always in reach on topics and items**: a "P 50" chip on screen, one tap to change it | Done for topics and items (chip in the top bar) |
| ✅ | Extract queue: several extracts, then one review | The extract sheet's Add later sets the extract aside as chosen (type, priority, its source place) and frees the draft for the next selection; a "2 to add" chip in the top bar opens them to remove any and Add all; whatever is still waiting is added when the document is left or the session moves on. Keep extracting still keeps one draft open for more text |
| 🟡 | Extract card from the current reviewer card [11] | AnkiDroid's reviewer: Extract on the selected text's toolbar opens the extract sheet with the card as its source (priority from it, a child of it in the knowledge tree); checked on the tablet. Items in the learning screen have the same Extract on their selection (not yet tried on a device). Not yet in AnkiDroid's new study screen |
| ✅ | Add Card dock / card from scratch with priority [11] | Library → Add → New card: Item/Topic, front/back, note type, deck (upstream's defaults) and priority (50, desktop's scratch priority); no source and no place in the tree, as on desktop. Made without Incremento's metadata fields, which would change the note type and force a full sync |
| ⬜ | Batch Q/A cards [11] | |
| ⬜ | PDF snapshot previews on cards [13] | Mobile already shows snapshots created on desktop |

## 5. Statistics and focus [10]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Statistics screen: today's tiles, cards per day for 7 days (tap a day), by kind today and all time, reading and watching | From the Library's top bar and the menu |
| ✅ | Longer history (30 days, a year), tag charts | Desktop's scopes as chips: Today, 7 days, 30 days, Year, All time. Each opens with desktop's progress dashboard (pages, study time and cards with the change from the period before; daily bars, weekly for a year, a tap gives the day or week). Periods add desktop's streak line and its activity calendar (weekday rows, scrolls to the latest weeks, a tap gives the day); Today and All time add cards by kind and by tag (desktop's tag pies, as rows). Numbers and texts are desktop's own helpers. Not here: the session scope (it needs desktop's session counters), daily goals, CSV export, the cumulative chart and the Documents and Learning views. Desktop keeps no focus-timer statistics beyond the summary the timer already shows |
| ✅ | Focus timer | Readers' ⋮ → Focus timer: desktop's presets, start/pause/reset, a chip in the top bar while it runs, one timer across elements; at the end a tone (desktop's setting) and desktop's summary (cards answered and PDF/EPUB pages read in the run, today so far). Not yet: desktop's automatic start for chosen card types or tags, and answers in AnkiDroid's own reviewer |

## 6. Sync, data and safety [12, 13]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Cards and media via AnkiWeb (AnkiDroid's own sync) | |
| ⏸ | Incremento data via a shared folder (SAF: Drive, Dropbox, …), conflict-safe snapshots | Works on the device; further sync work waits for an agreement with upstream's author on the sync format |
| ⏸ | Desktop "Ankrimento Sync" add-on | Built and API-checked; deferred until there is an agreement with upstream's author |
| ⏸ | Sync status on the hub (last sync, conflicts, Sync now) | With the sync work above |
| ✅ | Full backup / restore (same ZIP as desktop) [12] | Settings → Backup. Export: upstream's restore pre-check accepts it on desktop. Restore: upstream's pre-check and staging, an AnkiDroid safety backup, Incremento's folder swapped and the collection restored through AnkiDroid's own collection import (rolled back if it fails), then the app restarts. Upstream's own export and restore, so a phone backup restores on desktop and back: cards with scheduling and media, plus Incremento's folder (database, PDFs, EPUBs, videos, writings). In the Incremento settings, under Backup |
| 🟡 | Automatic backups [12] | Settings → Backup → Automatic backups (opt-in, as on desktop): into a folder picked on this device, when the app opens and every 12 h to a week, keeping the latest 1 to 20 by upstream's own rotation (its slot names, so desktop recognises them); Back up now. The policy stays on this device, since desktop's names a desktop folder. Desktop's "on close" has no dependable moment on a phone. Not yet checked on a device past the settings rows (the folder picker is a system screen) |
| ⬜ | Support bundle [12] | |
| ⬜ | Clean up unused data [13] | |
| ⬜ | Card-format updates notice [13] | |

## 7. Import [5]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | PDF, EPUB from the device | |
| ✅ | Web and video links (menu and share sheet) | |
| ⏸ | SuperMemo collection import [5] | Deferred |
| ✅ | Audio import | Add → Audio file (MP3, M4A, AAC, WAV): desktop's audio import makes its Incremento Audio topic. Checked on the tablet: plays, seeks, resumes |
| ➖ | Browser extension companion [5] | Desktop browser only; the share sheet covers mobile |

## 8. Settings, language and quality [13]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Settings screen (the Incremento card's gear): session, review, extraction and priority, reading, this device's profile and sync folder | Upstream's own settings, normalized by upstream on every change |
| ⬜ | More of upstream's settings (writing, PDF details, shortcuts that make sense on a phone) | |
| 🟡 | Translations | Android screens in Brazilian Portuguese, Croatian and Simplified Chinese (Croatian and Chinese with desktop's own terms from upstream's catalogs, AnkiDroid's for cards and grades); upstream's messages follow the app's language at startup as desktop's do (Incremento's language setting, or the app's when auto). Upstream has no Portuguese, so its messages stay English in pt-BR. Machine-made: a native speaker should review each language |
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
5. ~~Settings screen, session presets, advanced options~~ (done).
6. ~~Full backup / restore (same ZIP as desktop)~~ (done; automatic backups later).
7. ~~Native PDF tools: search, contents, bookmarks, highlights/notes~~ (done;
   annotation styles remain).
8. ~~Knowledge tree screen, Search ALL, statistics screen, focus timer~~ (done).
9. ~~EPUB tools to match the PDF's: search in the book, highlights list and notes~~ (done).
10. ~~Library search, sort and covers~~ (done).
11. ~~Translations~~ (pt-BR, hr, zh-Hans done; native review needed).
12. ~~Knowledge tree: reveal the current card, go to parent, search, postpone a subtree~~ (done).
13. ~~Extract queue (several extracts, then one review)~~ (done).
14. ~~Session preview before starting~~ (done).
15. ~~New AnkiDroid study screen (ReviewerFragment) opens readers~~ (done).
16. ~~EPUB paged mode~~ (done).
17. ~~Images in imported web articles~~ (done).
18. ~~Automatic backups~~ (built; the folder picker step still to try on a device).
19. ~~PDF annotation styles and bookmark comments~~ (done; EPUB styles too).
20. ~~Advanced options: phase order, Order values~~ (done; locks and groups only link desktop's sliders).
21. ~~Math in the learning screen~~ (done; sound, typed answers and image occlusion stay in AnkiDroid's reviewer).

### Next, by priority

Daily reading and extracting:

22. ~~Faster first launch~~ (done).
23. ~~Extract from the card shown in AnkiDroid's reviewer; add a card from scratch with a priority~~ (done; the new study screen's Extract remains).
24. ~~Web extract anchors~~ (done).
25. ~~Links back to the source, for every kind of extract~~ (done).
26. ~~Review all cards made from a document~~ (done; the phone also empties the review deck when the review ends
    and keeps it out of the deck list while nothing in it is due).
26a. ~~The extract sheet made clear~~ (done: Add card first; Add later and Select more text, each explained on the sheet;
     a confirmation after each, saying where the card went).
26b. ~~The extract sheet's note type follows the document open~~ (done: desktop's extract note type setting when set,
     otherwise the type last used for that kind of document).
27. ~~Items with sound, typed answers or image occlusion in the learning screen~~ (done, with AnkiDroid's own card display and player).

Things desktop has:

28. ~~Longer statistics: 30 days, a year, tag charts~~ (done; desktop has no focus-timer statistics to add).
29. Tablet two-pane layout (library beside the reader). (Deferred: it does not suit the phone-first experience.)
30. ~~PDF cross-references and page links; OCR status~~ (done).
31. ~~Markdown documents (import) and the writing formatting toolbar~~ (done).
32. ~~Audio import; check audio cards~~ (done).
33. ~~Video bookmarks, Vimeo, fuller playback controls~~ (done). Captions: the providers' own; desktop's subtitle files wait for sync. Extract at a moment works (its card links back to that second); desktop's Create Mobile Clip cuts the video with FFmpeg, which the phone lacks.
34. More of upstream's settings (writing, PDF details).
35. Getting Started guide. (Deferred)

Upkeep and rarely used:

36. Support bundle, clean-up of unused data, card-format update notice.
37. Batch Q/A cards, PDF snapshot previews.
38. DjVu documents. Implement only if there is a readly available library to read DjVu files.
39. PDF reflow, only if extracts and highlights map back to the PDF exactly.

To check rather than build: the empty-session dialog, the leech notice and the
automatic backup folder picker on a device; native review of each translation;
UI tests for the learning loop.

Create procedure to implement upstream changes to guard compatibility with desktop Incremento. tracking parity check, parity feature check, tests, etc.

Deferred: sync add-on and other sync work (until agreed with upstream's
author), SuperMemo import, Webpage to PDF.
