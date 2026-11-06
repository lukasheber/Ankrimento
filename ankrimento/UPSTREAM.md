# Taking in a new desktop Incremento

The phone runs desktop Incremento's Python backend unchanged, from `vendor/incremento`.
Everything desktop-specific the phone relies on is listed by the parity check
(`tools/parity_check.py`), and its tests run with the rest of the Python tests.
Follow these steps for every new desktop version, in order.

## 1. Update the vendored add-on

1. Replace `vendor/incremento` with the new release (the add-on folder as published,
   without `__pycache__`). Commit nothing yet.
2. Note the version and the release notes: each new feature is a roadmap question
   (step 6).

## 2. Run the parity check

```
ankrimento/.venv/Scripts/python ankrimento/tools/parity_check.py
```

It fails (exit 1) on what a new version can break silently:

| Finding | What it means | What to do |
|---|---|---|
| A desktop message the phone does not answer | The reader pages (`web/dist/pdf_viewer.js`, `frontend/epub_dock.py`, …) send something new | Bring the feature over (a handler in `pdf_reader.py`, `epub_reader.py`, …), or add the message to `NOT_ANSWERED` with the reason. Prefix the reason with `GAP:` when the phone should have it later |
| A compiled desktop name is gone | A function or constant the phone compiles from a Qt file (`ast`) was renamed or removed | Find its new name or replacement in desktop's file; update the list in the phone module (`epub_reader.py`, `statistics_view.py`, `writing.py`) |
| A translation key is gone | The phone shows a desktop text (`t("…")`) that desktop dropped or renamed | Use desktop's new key |
| A shipped entry is missing | A file listed in `addon.py` `SHIPPED_ENTRIES` (and `build.gradle.kts`) moved | Update both lists |
| A mirrored function changed, or was never recorded | Desktop changed a function the phone copies (step 3) | Update the copy, then `--record` |

It also warns when a desktop function the phone mirrors ("Mirrors ``name``" in the
phone's Python) cannot be found. A warning means: open the phone's copy and desktop's
current code side by side, and bring the copy up to date.

## 3. Compare the mirrored code

`tools/mirrors.json` keeps a fingerprint of the source of each desktop function the
phone copies ("Mirrors ``name``" in the phone's Python). The check lists:

- **changed since copied**: desktop changed that function. Open its diff (`git diff` of
  `vendor/incremento` before committing shows it) and the phone's copy (search the
  phone's Python for its name), and change the copy the same way;
- **never recorded**: a new copy. Compare it with desktop's function once.

When every listed copy matches desktop again, record the fingerprints and commit
`mirrors.json` with the rest:

```
ankrimento/.venv/Scripts/python ankrimento/tools/parity_check.py --record
```

A new copy on the phone gets a "Mirrors ``name``" note (the desktop function's name,
optionally `path.py:name`), so the check finds it. The modules with copies:
`pdf_reader.py`, `pdf_cards.py`, `epub_reader.py`, `extract.py`, `writing.py`,
`video_reader.py`, `statistics_view.py`, `pdf_links.py`, `document_review.py`, `upkeep.py`.

## 4. Run the tests

```
cd ankrimento && .venv/Scripts/python -m pytest python-tests
cd .. && ./gradlew.bat :AnkiDroid:testPlayDebugUnitTest --tests "com.ichi2.anki.ankrimento.*"
./gradlew.bat :AnkiDroid:assemblePlayDebug
```

The Python tests run desktop's backend against a real collection; `test_parity.py`
repeats the parity check, and `test_runtime.py` checks which desktop files ship.

## 5. Check on a device

Install the debug build and go through what the release notes touch, plus a short
round: a session (topic and item), a PDF and an EPUB (extract, highlight, Find),
the knowledge tree, statistics, a full backup. The data must stay readable by the
desktop version (a full backup restores on desktop).

## 6. Update the roadmap

In `ROADMAP.md`, add each new desktop feature to its section (⬜ to do, ➖ desktop-only
with the reason) and to the order of work when it matters on a phone. Commit the
vendored add-on, the phone's changes and the roadmap together, naming the desktop
version in the message.

## Known gaps

The parity check prints the messages marked `GAP:`: desktop features reached from the
reader pages that the phone does not have yet. They are also in `ROADMAP.md`.
