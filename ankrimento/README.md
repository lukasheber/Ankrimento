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
| profile open: postpone/skip runtimes, interrupted-import recovery | todo |

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
- Web, video and writing readers: todo.

### Syncing Incremento data

AnkiWeb syncs the cards; `syncfolder.py` syncs Incremento's own data
(`user_files/<profile>`: `incremento.db` and managed files) and settings
through a shared folder. The module documents the format. On Android the
folder is picked with the Storage Access Framework ("Incremento sync
folder…"), so it can be a folder kept in step with Google Drive, Dropbox or
a computer by Autosync, Dropsync, FolderSync or Syncthing. The profile name
("Incremento profile…") must match the desktop profile.

Ankrimento pulls before a session starts and pushes (releasing its lease)
when the reviewer closes; "Sync Incremento data" syncs on demand.

The desktop side is the **Ankrimento Sync** add-on (`desktop-addon/`), which
ships the same `syncfolder.py`; `python desktop-addon/build.py` writes
`build/ankrimento_sync.ankiaddon`. It finds Incremento's add-on folder
(numbered when installed from AnkiWeb), and syncs when a profile opens,
after each AnkiWeb sync, and when the profile closes. Tools → Ankrimento
Sync picks the folder (inside Google Drive, Dropbox…) and syncs on demand.
Its Anki API use was checked against aqt 26.9.3; it has not yet been run
inside Anki desktop.

### AnkiDroid hooks

| Where | What |
|---|---|
| `DeckPicker.onCreate` | registers `IncrementoMenu` ("Start Incremento session", "Add PDF to Incremento") |
| legacy `Reviewer` (AnkiDroid's default) | answer remap and post-answer scheduling, Incremento's answer buttons, question/answer shown, reviewer closed, opening a PDF topic's reader |
| `ReviewerViewModel.answerCardInternal` (new study screen) | answer remap and post-answer scheduling; its buttons keep AnkiDroid's labels (todo) |
| `EaseButton` | a `setup` overload taking a text label |

The hooks do nothing until Ankrimento has started (an Incremento session or a PDF import).

## Desktop tests

The Python layer is tested on desktop CPython against a real Anki backend
that plays AnkiDroid's role (`python-tests/conftest.py`):

```bash
cd ankrimento
py -3.13 -m venv .venv
.venv/Scripts/python -m pip install "anki==26.9.3" pytest beautifulsoup4
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

- **Incremento:** `git -C vendor/incremento fetch && git -C vendor/incremento checkout <commit>`,
  rerun the desktop tests, then review the "desktop wiring" table against the
  new `__init__.py`.
- **AnkiDroid backend:** when the build stops with the version-mismatch error,
  set `ankiPythonVersion`, `ankiWheelUrl` and `ankiWheelSha256` in
  `build.gradle.kts` to the matching PyPI `anki` wheel, update the venv, and
  rerun the tests.
