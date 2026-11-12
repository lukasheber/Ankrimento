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
| ✅ | When a session finds nothing to study, say why and offer the session settings | Dialog with upstream's message and a Session settings button; seen on the tablet from a knowledge-tree branch with nothing due (document topics always qualify, as upstream intends) |
| ✅ | Plain-text topics in a reader (no "Show answer"): the note as its template renders it, Aa settings, Extract on selection, review bar | |
| ✅ | Items in the same learning screen as topics: question, Show answer, then Anki's grades (or Fail/Pass when enabled), with intervals, Back and the priority chip | Session deck only |
| ✅ | Items with sound, typed answers, math (MathJax) and image occlusion in the learning screen | The card is AnkiDroid's own rendering and playback, supplied by the app (`AnkrimentoApp.cardDisplay`): its play buttons and video players, its media player with text to speech, its box for a typed answer and Anki's comparison, as in its study screen. Image occlusion: Anki's own review script and styles; math: AnkiDroid's MathJax. All checked on the tablet |
| ✅ | Leech notice when an item becomes a leech in the learning screen | A notice after the answer (and whether it was suspended). Checked on the tablet: an item one lapse short of the threshold answered Again showed "This card is a leech" and the session moved on |
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
| ✅ | The viewer's own buttons on a highlight, sized for a finger | Colour, note, resize and remove grow from 16px to 32px (48px to touch), lifted clear of the text. Colour opens the phone's colours (also for the next highlight and the default, saved per profile as on desktop); note opens the highlight's dialog |
| ✅ | The viewer's card buttons [6, 11] | A highlight's card button opens its card, or starts the new-card sheet with its text and the link back (desktop's highlight field setting); Cards from highlights lists those without a card and makes one each, linked to its highlight; a card listed on the page opens in its reader; the page's and the document's cards open in AnkiDroid's browser; Add Card starts the new-card sheet on the page being read; Reading Limit opens desktop's dialog in desktop's words. Desktop's version adds Incremento's metadata fields to the note type; the phone's cards are made as its extracts are, without that schema change. Checked on the tablet |
| ✅ | Review all cards attached to the document [6] | PDF and EPUB readers' ⋮ → Review cards from this document: desktop's choices (all or due, topics/items, whole document or up to here, order) with a live count, selected by upstream's media review; Review builds desktop's PDF/EPUB review deck and opens AnkiDroid's reviewer. Checked on the tablet |
| ✅ | Cross-references and page links [6] | The PDF's own links (to a figure, a citation, another page) are the viewer's: they jump, keep a way back and preview; the preview's size and zoom are kept per profile as on desktop. Page links (desktop's connections between PDF pages): ⋮ → Page links lists the page's links from either end, each opened (at its page, the other PDF's reader) or removed; Link this page… picks a PDF from the library, which opens only to choose the place (nothing it does is saved for that PDF), then Link to this page. The viewer's own Page links panel, when its controls are shown, uses the same links. Desktop's daily page limit bounds where links point. Checked on the tablet. Not here: desktop's reference window beside the reader (the phone opens the other reader) and recordings linked to a page |
| ➖ | DjVu documents [6] | Not built: desktop converts DjVu to PDF with DjVuLibre's tools (ddjvu, djvutxt) and PyMuPDF, none of which the phone has, and no ready Android library does it. A DjVu imported on desktop arrives as a PDF |
| ✅ | Searchable PDF text / OCR status [6, 13] | The device reads each PDF's text for Find and Search ALL; a PDF without a text layer is named as such in Find, which says Incremento on a computer can add one with OCR when importing (Tesseract is desktop's) |
| ✅ | Pinch zoom: the page follows the fingers, then upstream's zoom applies (saved per document); Fit to width is always exactly 100% | |
| ➖ | **Reflow** for small screens: the page's text re-flowed to the screen width, EPUB-style | Not done: extracts, highlights and annotations made in a reflow would not map back to their exact places in the PDF (pdf.js has no such mapping), so they could not show there on desktop |
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
| ✅ | Snapshot of a region | EPUB reader ⋮ → Snapshot of a region: drag a box; it becomes a new card's image with the link back to its chapter, as desktop's snapshot of the selection does (the page's own snapshot button works too). Checked on the tablet |
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
| ✅ | Captions (target/reference) | Video reader ⋮ → Subtitles: desktop's two slots (the language learnt, and one known under it), each from an SRT or WebVTT file kept as desktop keeps it (the profile's videos, "<title>-<slot>"), shown or hidden, or removed; the captions follow the playing time, for local and YouTube videos. Desktop's download of a video's own tracks needs yt-dlp and stays there; YouTube's and Vimeo's own captions show in their players. Checked on the tablet |
| ✅ | Segment of a video in an extract | Replaces Create Mobile Clip, which duplicated the video. The bracket icon in the video reader's top bar opens a bar over the video: drag the two handles on a range slider (the video follows the handle), or play to a moment and tap Start here / End here, Preview plays the segment and stops, Extract segment makes the card. It opens at the moment playing, 30 s on. The extract's front carries the segment (start–end) as a link that plays the **parent** video from the start and stops at the end; nothing is cut or copied, and it works for local, YouTube and Vimeo videos (Vimeo only starts playing when its own play button is tapped, and tells its length only then, so the slider appears after that). Desktop has the idea as recording links (`recording_links`, desktop dialogs, local files only, not synced); the segment here lives in the card's text, which Anki syncs, with a second link to the start that desktop understands. Desktop's Create Mobile Clip is not offered on the phone |
| ✅ | Add Video from a file | Library → Add → Video file: desktop's import with "Original quality (no re-encoding)", the mode without FFmpeg |
| ➖ | Download Local Copy, Create Mobile Clip | Need yt-dlp / FFmpeg and duplicate the video; segments of the parent video replace clips |
| ✅ | Audio cards | Ordinary Anki sound plays through AnkiDroid's own player, in its reviewer and in the learning screen (checked on the tablet). Desktop's audio topics play in their card's own player, which seeks (media is served in ranges) and resumes where listening stopped |

### Writing, Markdown and local files [5, 8]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Writing card editor with autosave and word count (upstream backups) | |
| ✅ | Markdown preview / formatting toolbar | Desktop's formatting buttons (headings 1 to 3, bold, italic, strikethrough, lists, quote, inline code, code block, rule, table) in a row above the keyboard, with desktop's labels and its own rewriting of the selection; Preview shows desktop's rendering of the text. Checked on the tablet. Not here: desktop's font size, wrap, focus mode, marker, bookmarks and backups buttons |
| ✅ | Markdown documents (import, read as EPUB-like) [5] | Add → Markdown document: desktop's Markdown import makes a study document read in the EPUB reader (its headings become chapters). Checked on the tablet. Android's file picker hands over the file alone, so its pictures in nearby files are left as their alt text, as desktop's import does with any image it can't read |
| ✅ | Local file cards [8] | Library → Add → **Any file**, and any file shared to Incremento, becomes a card with a managed copy (desktop's "import a managed copy"; upstream's own import). A PDF or EPUB is offered as a document first. The card's screen shows the name, size, type and note, with **Open with…** and **Share…** (the file goes to the other app under its own name through the app's FileProvider, read access for that file only). There is no folder to reveal on a phone, so that desktop action has no counterpart. **No keep-a-reference mode**: a path on one computer means nothing on another device and Android does not let an app keep one; a card made on desktop with a reference says its file is on that computer and offers **Attach a copy**, which turns it into a managed copy. Installable packages are kept but never opened from the app; files over 512 MB are refused (the sync engine's limit). Managed copies travel through the sync engine to every device (see the sync plan). Library chip **File**. Checked on the tablet: a file shared to Incremento becomes a card, the card's screen with Share… (the share sheet names the file as it is), an installable package kept with its notice and no Open with…/Share…, and a replacement through the system file picker; not seen on a device: the card made on desktop with a reference path (Attach a copy) |
| ➖ | Writing tabs beside the readers (up to 32 drafts), compact toolbar with links and images, export of a draft [2026.11.03] | Desktop's workspace: a tabbed pane beside the reader. The phone edits one topic at a time, and a second pane does not fit a phone screen |
| ✅ | Excerpt markers in writing | Desktop's "Send to Writing" leaves hidden markers in the text; the phone keeps them, does not count them as words, and writes them back as they were |
| ✅ | Send selected text to Writing with a link back to its source [2026.11.04] | **Send to Writing** on the selection menu of the PDF and EPUB readers (desktop sends to the writing dock that is open; a phone has none open, so a sheet asks which writing topic, or creates one). Upstream's `create_link` and desktop's marker format quote the passage, so desktop keeps the link. Back to the source from the writing: **Quoted sources** in the writing's ⋮ menu, each opening its document at the page or chapter with the words found. Back to the writing from the source: **Writing that quotes this** in the reader's ⋮ menu lists the passages on the page or chapter in view. The markers are drawn as small quotation marks in the editor. Checked on the tablet |

### Reading progress and links between documents [2026.11.03, 2026.11.04]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | **Viewed and Marked read figures**: reading that is not in order is counted by passage, not only by the furthest page [2026.11.04] | Built on desktop's own tracker (`frontend/reader_coverage.py`), so the figures, wording and rows are desktop's and sync. Every PDF page and EPUB passage that comes into view is recorded as viewed. The reader shows "Viewed 40% · Marked read 10%" under the page (PDF) or the progress bar (EPUB). One **Reading marks** sheet in each reader's ⋮ menu holds what desktop spreads over a context menu: this page or screen and this chapter read or unread, and for a PDF a from/to page range (instead of typing "3-7"); a selected passage is marked from its own menu (**Mark read**). The Library draws a thin two-layer bar under each PDF and EPUB (viewed lighter, marked solid). Checked on the tablet. Not here: desktop's context-menu entries as such, and the Document Bookshelf's two-line caption (the bar and its description for screen readers stand in) |
| ✅ | Last added sort in the Library [2026.11.03] | A fourth chip in the Library's Sort row: newest first, by note id as desktop does, so regenerated cards keep their place. Checked on the tablet |
| ✅ | Parent context while reviewing: a read-only view of the card's original parent [2026.11.03] | Desktop's sidebar becomes a dialog over the card: **Show parent** in AnkiDroid's reviewer menu and in the readers' ⋮ menu. It shows the parent's title and visible fields through upstream's own loader (saved extraction origin, or the knowledge tree for older links), loads nothing (no script, no network, no media), and says so when the card has no parent or the parent was deleted |
| ✅ | Offline snapshots of web pages (website appearance or editable reading copy) [2026.11.04] | Library → Add → **Website, saved for offline reading**, and the same choice in the share sheet next to the reading-copy article: upstream's `prepare_webpage_document` with `capture_format` html, then its EPUB import marked HTML, so the reader keeps the site's own layout (`preserve_layout`) and desktop reads it too. The reading copy is the existing Web article. Not here: desktop's preview-and-edit step before saving a snapshot (the phone saves at once; the reading copy stays editable on desktop) |
| ✅ | Passage links between documents, with Jump Back [2026.11.04] | **Following** is on the phone. PDF: a page link that carries a passage opens its other end with the exact words revealed (desktop's viewer does the revealing); another document opens over this one and Back returns, a jump inside the document returns with Back to the page it was followed from. EPUB: ⋮ → **Connections** lists the chapter's connections from either end (the words, the other book and chapter), opens one with its passage revealed, says in desktop's words when the words are no longer there, and removes one. Where reading was left is not moved by a jump. **Making** a passage link stays on desktop: it is done with two documents side by side (the phone's own "Link this page…" still makes page links). Page links, passage links and EPUB connections travel through the sync engine in both directions (one record per link, identified by its two ends). Checked on the tablet |
| ➖ | Workspace tabs (grouped readers, references, searches), hover previews of PDF links and footnotes, side-by-side panes | A windowed workflow. The phone opens the other reader (links and footnotes already show in a sheet) |
| ➖ | MCP server for AI tools | Runs a local server on a desktop; not for a phone |
| ⬜ | Set a document's priority from the Library [2026.11.11] | Desktop's bookshelf gains **Set Priority…** for one or several PDFs and EPUBs (upstream's `set_priorities`, already in the shipped backend). The Library shows priority but changes it only from the knowledge tree or the reader; a long press on a document, with selection of several, is the phone's version |
| ⬜ | Hide the parent context icon [2026.11.11] | New setting `reviewer_parent_context_enabled` (default on). The phone's **Show parent** has no switch yet; a toggle under Settings → Review would follow it |
| ➖ | Set Priority for a Browser selection [2026.11.11] | Desktop's Browser menu; AnkiDroid's browser is not extended, and priorities are set on the card (reviewer chip, readers, tree) |
| ✅ | PDF checks before import (password-protected, damaged, no text layer) [2026.11.04] | Through `pypdf` instead of desktop's PyMuPDF, and a `QPdfDocument` stand-in. Limits: a PDF with owner-only AES encryption is refused because the phone has no crypto library (desktop opens it), and a PDF with a damaged cross-reference table is accepted when its page objects can be found |

## 4. Extraction and cards [11]

| Status | Feature | Mobile notes |
|---|---|---|
| ✅ | Extract to a new card in a bottom sheet: Item/Topic, front/back, note type, priority slider | Add card (main button); Add later (the queue, added when the document is left) and Select more text (closes the sheet; the next selection joins this card), each explained on the sheet and confirmed after ("Card added under …"). Note type: desktop's extract note type setting when set, otherwise the one last used for that kind of document |
| ✅ | Priority editor (slider, 0 to 100) | |
| ✅ | **Priority always in reach on topics and items**: a "P 50" chip on screen, one tap to change it | Done for topics and items (chip in the top bar) |
| ✅ | Extract queue: several extracts, then one review | The extract sheet's Add later sets the extract aside as chosen (type, priority, its source place) and frees the draft for the next selection; a "2 to add" chip in the top bar opens them to remove any and Add all; whatever is still waiting is added when the document is left or the session moves on. Keep extracting still keeps one draft open for more text |
| ✅ | Extract card from the current reviewer card [11] | Extract on the selected text's toolbar opens the extract sheet with the card as its source (priority from it, a child of it in the knowledge tree): in AnkiDroid's reviewer, its new study screen (through a selection hook on its card's web view) and the learning screen's items. All three checked on the tablet |
| ✅ | Add Card dock / card from scratch with priority [11] | Library → Add → New card: Item/Topic, front/back, note type, deck (upstream's defaults) and priority (50, desktop's scratch priority); no source and no place in the tree, as on desktop. Made without Incremento's metadata fields, which would change the note type and force a full sync |
| ✅ | Batch Q/A cards [11] | Batch Q/A… on the extract sheet (readers, reviewer extracts, new card): the text as alternating lines or Q:/A: blocks, previewed as desktop's parser reads it, then one card per valid pair with the sheet's type, note type and priority, under the source (or in the chosen deck). Desktop's labels and summary. Checked on the tablet |
| ✅ | **Cloze from a topic** (SuperMemo's way from reading to items) | Text topics (extracts): Cloze on the selection menu opens the extract sheet with the sentence around the selection and the selection as `{{c1::…}}`, as an item of Anki's Cloze note type, with the topic's priority and place in the knowledge tree; the sentence can be widened or trimmed before Add. Without a plain cloze note type Anki's own is added (never image occlusion). New on the phone: desktop has no cloze tool. Checked on the tablet |
| ✅ | Edit a topic | Text topics: the pencil opens the topic's note in AnkiDroid's editor; the page shows the edit on return. Checked on the tablet |
| ✅ | PDF snapshot previews on cards [13] | PDF reader ⋮ → Snapshot of a region: drag a box over the page; it is copied from the screen and handed to Incremento as the viewer's own snapshot message, so it is kept as desktop keeps it (media, then the extract's front with desktop's link to the page). The extract sheet shows the image above its field. Desktop asks which field takes it; the phone uses the front. Checked on the tablet. Snapshots made on desktop show on cards as before |

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
| ✅ | Automatic backups [12] | Settings → Backup → Automatic backups (opt-in, as on desktop): into a folder picked on this device, when the app opens and every 12 h to a week, keeping the latest 1 to 20 by upstream's own rotation (its slot names, so desktop recognises them); Back up now. The policy stays on this device, since desktop's names a desktop folder. Desktop's "on close" has no dependable moment on a phone. Checked on the tablet: the system folder picker (a folder under Downloads is accepted; Android refuses the storage root and Downloads itself), the access grant, Back up now writing a valid ZIP under upstream's slot name, the status line |
| ✅ | Support bundle [12] | Settings → Upkeep → Create a support bundle: desktop's privacy-safe diagnostics ZIP (built by its backend), handed to Android's share sheet. Built and checked by tests; the share sheet is Android's |
| ➖ | Clean up unused data [13] | Desktop's clean-up (orphaned PDFs and videos, stale rows, unused screenshots) is its Tools menu's own logic, not the backend's; copying it to delete files on the phone risks removing the wrong data, so it stays on desktop |
| ✅ | Card-format updates notice [13] | Settings → Upkeep → Card format updates: desktop's check of Incremento's note types; when one is older, Update applies desktop's update after warning that AnkiWeb then needs a full sync. Shown on the tablet (up to date there); the update itself is covered by tests |

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
| ✅ | More of upstream's settings (writing, PDF details, shortcuts that make sense on a phone) | Added the ones the phone honours, with desktop's wording: Reading → always force the PDF appearance, delete X on PDF highlights, scroll to top on the next PDF page; Writing → automatic backups, word counting mode. Shown on the tablet; saving is covered by tests. Writing → the backup intervals (desktop's eight, each keeping a rolling copy). Settings that only change desktop's screens stay there |
| 🟡 | Translations | Android screens in Brazilian Portuguese, Croatian and Simplified Chinese (Croatian and Chinese with desktop's own terms from upstream's catalogs, AnkiDroid's for cards and grades); upstream's messages follow the app's language at startup as desktop's do (Incremento's language setting, or the app's when auto). Upstream has no Portuguese, so its messages stay English in pt-BR. Machine-made: a native speaker should review each language |
| ✅ | Getting Started guide (first run) | Desktop's six steps (welcome, add a document, extract, a small session, add from other apps, backup) one to a screen with a progress bar, Skip, Back and Next/Finish at thumb height. The browser-extension step becomes "add from other apps" (Android's share sheet is how things reach Incremento here). Steps with a place to go have a button: Add a document (the Library's Add sheet), session settings, backup settings; Back returns to the guide. Opens by itself the first time Incremento opens, once (the version completed is kept on the device, as desktop keeps `onboarding_completed_version`), and again from the Incremento card's menu → Getting started. Checked on the tablet in English; the other three languages' texts were not looked at on a device |
| ➖ | Dependency installer [13] | Desktop tools (FFmpeg, OCR) |
| 🟡 | Startup time | Measured on the tablet (Nov 2026): Python warms up in 1.6–1.8 s on app start and Incremento is ready 2–4 s after launch, while AnkiDroid's own deck list takes about 7 s to show; nothing waits on it at launch. The very first launch after a fresh install also unpacks Python (about 20 s, last measured earlier); not re-measured, since that needs the app's data cleared |
| ✅ | Tests: Python tests against the real backend, reviewer Robolectric tests, on-device smoke test, UI tests of the learning loop | The session bar under Robolectric on a phone-sized screen: a topic's Next / Sooner / Later and their ratings, its Topic menu, an item revealed then graded, and the topic bar on a narrow phone in Portuguese (`./gradlew :ankrimento:testDebugUnitTest`) |
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
33. ~~Video bookmarks, Vimeo, fuller playback controls~~ (done). Captions: the providers' own; desktop's subtitle files wait for sync. Extract at a moment works (its card links back to that second); a segment of a video (start to end) can be marked for an extract and plays from the parent video, with no clip made; videos can be added from a file.
34. ~~More of upstream's settings (writing, PDF details)~~ (done).
35. Getting Started guide. (Deferred)

Upkeep and rarely used:

36. ~~Support bundle, card-format update notice~~ (done). Clean-up of unused data stays on desktop.
37. ~~Batch Q/A cards, PDF snapshot previews~~ (done).
38. ~~DjVu documents~~ (not built: no ready library on the phone; desktop's import makes them PDFs).
39. PDF reflow: not built. pdf.js offers no reflowed layout whose selections map back exactly to the PDF's text positions, so extracts and highlights made in it could not land in place on desktop.

From desktop v2026.11.04 + 23 (evaluated in "Reading progress and links between documents"):

40. ~~Reading coverage (Viewed against Marked read) in the readers and the Library; Reading marks sheet with page ranges; Last added sort~~ (done).
41. ~~Parent context while reviewing; send selected text to Writing with links both ways; offline website snapshots~~ (done).
42. ~~Following a precise passage link between documents, with a way back~~ (done; the parity check has no known gaps left).
43. ~~Local file cards: managed copies, open with another app, shared by sync~~ (done).

From desktop v2026.11.11 (taken in; no schema change, parity check clean):

44. Document priority from the Library, and a switch for the parent context icon (see section 3).

To check rather than build: the leech notice (it reuses AnkiDroid's own check; reaching it needs a card's seventh lapse) and the
automatic backup folder picker on a device (the empty-session dialog was checked); native review of each translation;
UI/UX tests for the learning loop for incremental reading/studying, and improvements, think how mobile users use the features and how buttons and itens are shown. Think about about an incremental reading learning loop like the one in supermemo. user see topic, edit topics easily, make cloze to generate itens, etc.

~~Create procedure to implement upstream changes to guard compatibility with desktop Incremento~~ (done: `UPSTREAM.md`, and `tools/parity_check.py`, also run by the Python tests, which lists desktop messages, compiled names, translation keys, shipped files and mirrored functions the phone relies on, and the known gaps).

Deferred: sync add-on and other sync work (until agreed with upstream's
author), SuperMemo import, Webpage to PDF.
