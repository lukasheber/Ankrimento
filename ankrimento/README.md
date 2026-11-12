# Ankrimento module

Runs [Incremento](https://github.com/BaskovicP/Incremento)'s Python backend,
unchanged, inside AnkiDroid using [Chaquopy](https://chaquo.com/chaquopy/).

## How it fits together

```
AnkiDroid (Kotlin)
  └─ Ankrimento.kt ──calls──▶ ankrimento.facade (Python, JSON in/out)
                                 └─ incremento.backend.*   ← vendor/incremento, unchanged
                                      ├─ aqt.*             ← stand-in (src/main/python/aqt)
                                      └─ anki.*            ← Python anki 26.9.3 from PyPI
                                           └─ anki._rsbridge ← ankrimento.rsbridge
  AnkrimentoHost.kt ◀──command / db_command───┘
    └─ AnkiDroid's open Rust backend (same collection, same undo queue)
```

- **One backend.** Python's `anki` normally loads a native `_rsbridge`. Here
  `ankrimento.rsbridge` forwards those calls to AnkiDroid's already-open
  backend (`Backend.runMethodRaw` / `runDbCommand`). Both sides are built from
  the same Anki release, so RPC indices agree. The build fails if AnkiDroid's
  backend version and `ankiPythonVersion` drift apart.
- **Desktop layout.** The add-on is installed to
  `<filesDir>/ankrimento/addons21/incremento/`, exactly like desktop's
  `addons21/incremento/`: upstream code, `user_files/<profile>/` and
  `meta.json` (user config). App updates replace only the code.
- **Desktop names.** Upstream is imported as `incremento.backend.*` (without
  running its Qt `__init__.py`), so config keys and paths match desktop.
- **Stand-ins.** `aqt` provides `mw`, `gui_hooks`, `operations`, `utils` and
  `qt.QTimer`. `PyQt6.QtPdf.QPdfDocument` reports empty documents so the
  PDF text/cover fallbacks return "nothing extracted".

## Rules

1. Never edit `vendor/incremento`. Adapt in the stand-ins or the facade.
2. Desktop behaviour wired up in Incremento's Qt composition root
   (`vendor/incremento/__init__.py`) is reproduced in `facade.py`, marked
   "desktop wiring". Check those places on every upstream bump.

### Desktop wiring status

| Desktop hook (in `__init__.py`) | Status |
|---|---|
| "Start Incremental Learning" → `learnFunction` | done, with a headless dialog (`session.py`) reporting saved settings |
| `reviewer_will_init_answer_buttons` / button times | done (`answer_buttons`): More/Same/Less, Fail/Pass |
| `reviewer_will_answer_card`: topic prepare, custom-schedule prepare, fail/pass remap | done (`before_answer`) |
| topic postpone button | todo |
| `reviewer_did_answer_card`: review time, topic A-factor, custom schedule, learning leech | done (`wiring.py`, desktop's order) |
| learning-leech prepare (`reviewer_will_answer_card`) | done (`before_answer`) |
| `reviewer_did_show_question/answer`, `state_did_change`: review time, session timing | done (`wiring.py`, session) |
| focus-timer widget, diagnostics | not ported (desktop Qt UI / support bundle) |
| `undo_state_did_change`: topic and custom-schedule reconciliation | done (`undo_state_changed`, after every `ChangeManager` op) |
| profile open: interrupted-import recovery | done (`wiring.recover_interrupted_imports` at start) |
| profile open: postpone/skip timers | partial: expired timed postpones are released when a session starts (`learnFunction`) |

### Readers and extraction

- **PDF** (`pdf_reader.py`, `PdfReaderActivity`): upstream's pdf.js viewer
  (`web/`), unchanged, with desktop's `pdf_dock` message handlers ported onto
  the same backend calls. Not yet: annotation sync with the PDF file,
  snapshots, search, linked references, reviewing a page's cards.
- **Extraction** (`extract.py`, `ExtractDialog`): the viewer's Extract fills a
  draft; the dialog adds a note with desktop's extract priority, topic/item
  tags, knowledge-tree lineage and PDF source row. Unlike desktop, mobile
  does not add Incremento's hidden metadata fields to note types: that is a
  schema change forcing a one-way full sync.
- **EPUB** (`epub_reader.py`, `EpubReaderActivity`): each extracted chapter
  in a WebView with desktop's EPUB page script. `frontend/epub_dock.py`
  imports Qt, so its `_build_page_script` is compiled from upstream's source
  on its own (the file ships as data, never imported); its `console.log`
  bridge feeds the ported handlers (progress, read marker, highlights,
  chapter navigation, extraction with an `epub_card_sources` row). The
  toolbar calls the page script's own actions, as desktop's does.
- **Web** (`web_reader.py`, `WebReaderActivity`): the page with desktop's
  `web/web_dock_bridge.js` (shipped as data), which saves the URL and scroll
  position and restores them (`incrementoApplyRestoreState`). Extract offers
  the selection. "Add to Incremento" in Android's share sheet
  (`ShareToIncrementoActivity`) adds a shared link through `add_web_card`.
  Not yet: desktop's extraction anchors and snapshots.
- **Video** (`video_reader.py`, `VideoReaderActivity`): YouTube through
  upstream's `web/video_player.html`, downloaded videos with Android's
  player; the position (`set_video_position`) and observed watch time
  (`record_video_watch`) are saved as desktop's video dock saves them.
  Shared YouTube/Vimeo links become video topics.
- **Writing** (`writing.py`, `WritingActivity`): Markdown edited in place and
  autosaved with `write_writing_text` (backups per the settings) and daily
  word counts (`set_writing_word_stats`), as desktop's writing dock saves.
  "New Incremento writing…" creates one (`add_writing_card`).

### Knowledge tree

"Incremento knowledge tree…" lists upstream's tree (`load_knowledge_tree_snapshot`)
indented; an entry offers studying its branch (`build_branch_study_scope` passed
to `learnFunction`, as desktop's tree does) or editing its priority.

### Statistics

"Incremento statistics…" summarizes upstream's `load_stats`,
`load_media_activity` and `load_daily_history`: today, all time, pages read,
video watch time and the last seven days.

### Syncing Incremento data

AnkiWeb syncs the cards; Incremento's own data (priorities, the knowledge tree, bookmarks and positions, highlights,
links between documents, local files and the documents themselves) is synced by the separate
[**Incremento Sync**](https://github.com/lukasheber/incremento-sync) add-on, the same one desktop Anki uses. It
works through a shared folder on desktop, and on the phone through a small hub on your own network (a six digit code the hub shows, typed in
*Incremento settings → This device*; the phone finds the hub itself). The phone does not sync through a folder: that was never tried with Drive, Dropbox
or Syncthing on a device, so it is not offered.

On the phone the add-on is vendored as `vendor/incremento-sync` and installed beside Incremento
(`<filesDir>/ankrimento/addons21/incremento_sync/`), as on desktop. It extends Incremento's backend while it is on
and puts everything back when it is off: *This device → Incremento Sync* switches it, and nothing syncs while it is
off. The sync data on the device is kept, so switching on again resumes. The profile name must match the desktop
profile. Ankrimento syncs after AnkiDroid's own sync and from the Incremento card's menu; the add-on's
`incremento_sync/loader.py` explains the switch.

### AnkiDroid hooks

| Where | What |
|---|---|
| `DeckPicker.onCreate` | registers `IncrementoMenu` ("Start Incremento session", "Add PDF to Incremento") |
| legacy `Reviewer` (AnkiDroid's default) | answer remap and post-answer scheduling, Incremento's answer buttons, question/answer shown, reviewer closed, opening a PDF topic's reader |
| new study screen (`ReviewerViewModel`, `AnswerAreaView`, `AnswerButton`, `ReviewerFragment`) | answer remap and post-answer scheduling, Incremento's answer buttons, question/answer shown; readers are not opened automatically there yet |
| `EaseButton` | a `setup` overload taking a text label |

The hooks do nothing until Ankrimento has started (an Incremento session or a PDF import).

## Desktop tests

The Python layer is tested on desktop CPython against a real Anki backend
that plays AnkiDroid's role (`python-tests/conftest.py`):

```bash
cd ankrimento
py -3.13 -m venv .venv
.venv/Scripts/python -m pip install "anki==26.9.3" pytest beautifulsoup4 pypdf
.venv/Scripts/python -m pytest
```

## Device smoke test

Debug builds include `AnkrimentoSmokeTestActivity` (in `src/debug/`), which
runs the review flow on the device's collection: adds notes tagged
`ankrimento-smoke`, starts Python, selects a session, builds the deck,
answers a topic with More, checks the A-factor and that one undo restores
both the card and Incremento's state.

```bash
./gradlew :AnkiDroid:assemblePlayDebug -Pandroid.injected.build.abi=x86_64
adb install -r -t AnkiDroid/build/intermediates/apk/play/debug/AnkiDroid-play-x86_64-debug.apk
adb shell am start -n com.ichi2.anki.debug/com.ichi2.anki.ankrimento.debug.AnkrimentoSmokeTestActivity
adb logcat -s AnkrimentoSmoke python.stderr
```

## Bumping upstream

- **Incremento:** follow `UPSTREAM.md` (submodule move, update report, parity check, tests, device check, roadmap).
- **AnkiDroid backend:** when the build stops with the version-mismatch error,
  set `ankiPythonVersion`, `ankiWheelUrl` and `ankiWheelSha256` in
  `build.gradle.kts` to the matching PyPI `anki` wheel, update the venv, and
  rerun the tests.
