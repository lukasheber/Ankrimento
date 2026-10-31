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
| `_topic_reviewer_will_answer_card` → `prepare_topic_answer` | done (`before_answer`) |
| topic postpone button, item fail/pass remap, custom-schedule prepare | todo |
| `reviewer_did_answer_card` → `on_topic_card_answered` | done (`after_answer`) |
| timer, review time, custom schedule, learning leech, diagnostics | todo |
| `undo_state_did_change` → `reconcile_topic_state_after_anki_operation` | done (`undo_state_changed`, after every `ChangeManager` op) |
| `undo_state_did_change` → custom-schedule reconciliation | todo |
| profile open: postpone/skip runtimes, interrupted-import recovery | todo |

### AnkiDroid hooks

| Where | What |
|---|---|
| `DeckPicker.onCreate` | registers `IncrementoSessionMenu` ("Start Incremento session") |
| `ReviewerViewModel.answerCardInternal` (new study screen) | `ratingToSubmit` before, `onCardAnswered` after the answer |
| legacy `Reviewer` (AnkiDroid's default) | **not hooked yet**: topic answers there get normal Anki scheduling |

The hooks do nothing until an Incremento session has started Ankrimento.

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
