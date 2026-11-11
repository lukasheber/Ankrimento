# Taking in a new desktop Incremento

The phone runs desktop Incremento's Python backend unchanged, from `vendor/incremento`, a git submodule pinned to one
upstream commit. Everything desktop-specific the phone relies on is checked by `tools/parity_check.py`, which the Python
tests also run. Follow these steps for every new desktop version, in order. Plan an afternoon: the first run
(v2026.10.30-rc.1 → v2026.11.04 + 23 commits, schema 23 → 26) took a changed PDF check, a new shipped file, a new
Markdown toolbar and two stand-in rewrites.

Needed: the Python environment (`ankrimento/.venv`, built as in `README.md`, **including `pypdf`**), network for Gradle
(Chaquopy installs `pypdf` from PyPI), and the tablet with the debug app (`com.ichi2.anki.debug`, never the installed
AnkiDroid) for step 8.

## 1. Move the submodule

Work on a branch, so a bad update is one `git checkout` away.

```
git checkout -b update-incremento-<short commit>
git -C vendor/incremento fetch --tags origin
git -C vendor/incremento tag --sort=-creatordate | head      # the releases
git -C vendor/incremento log --oneline HEAD..origin/master | head -30
```

Choose the target and write down why (in the commit message):

- **A tag** (`vN`) is a release: desktop users run it, so data written by it is the data the phone must read. This is the
  default.
- **master past the last tag** is allowed when the commits after it matter (a fix the phone needs, a feature to bring
  over, a contribution of ours merged upstream), but say so: nobody runs that exact version on desktop yet, so check
  the data round trip (step 7) against the tag too.

```
git -C vendor/incremento checkout <tag or commit>
git submodule status                                         # the new pin, e.g. v2026.11.04-23-g9b46946
```

The only change in this repository is one line, the submodule's commit. Never edit files inside `vendor/incremento`.

## 2. Read the report

```
ankrimento/.venv/Scripts/python ankrimento/tools/upstream_report.py
```

It only reads. `--from` defaults to the commit this repository pins (`HEAD`), `--to` to the submodule's checkout. Read it
top to bottom before running any test; it tells you where to look:

| Section | What to do with it |
|---|---|
| Releases and commits | Which merged changes and releases are in between; a note appears when the target is past the last tag |
| Changelog | The release notes. Each bullet is a roadmap question (step 9) |
| Shipped files | Which files of `SHIPPED_ENTRIES` (`addon.py`) changed. `backend/` changes run on the phone as they are; `frontend/` files are read by the phone, never imported, so a change there can break a mirror or a compiled function. Locale files and `backend/mcp_*` (the MCP server for AI tools) are collapsed |
| Database migrations | New schema numbers. The phone's `incremento.db` is migrated by upstream's code the first time it starts, so desktop must still read it afterwards (step 7) |
| Upstream frontend files newly imported | A shipped file started importing `frontend/<x>.py` which the phone does not ship. Plain Python the phone needs: ship it (below). Qt only: ignore. Most are Qt only |
| Qt and aqt names the backend newly imports | A stand-in is missing in `src/main/python/{PyQt6,aqt}`. Add one |
| Translation keys | Count of new keys; the phone uses `t("key")` directly, so new text is free, but a new Kotlin string that mirrors a desktop one should use it |

What the report cannot see: new *calls* on a stand-in class that already exists (the 2026.11 PDF check started calling
`QPdfDocument.error()` and `pageCount()`, which the old stand-in lacked). The tests find those (step 5).

### Shipping another upstream file

Edit `SHIPPED_ENTRIES` in `src/main/python/ankrimento/addon.py` **and** the include list of `incrementoAddonSource` in
`build.gradle.kts`, and add the name to the expected list in `python-tests/test_runtime.py`.

## 3. Run the parity check

```
ankrimento/.venv/Scripts/python ankrimento/tools/parity_check.py
```

It fails (exit 1) on what a new version can break silently:

| Finding | What it means | What to do |
|---|---|---|
| A desktop message the phone does not answer | The reader pages (`web/dist/pdf_viewer.js`, `frontend/epub_dock.py`, …) send something new | Bring the feature over (a handler in `pdf_reader.py`, `epub_reader.py`, …), or add the message to `NOT_ANSWERED` with the reason. Prefix the reason with `GAP:` when the phone should have it later |
| A compiled desktop name is gone | A function or constant the phone compiles from a Qt file (`ast`) was renamed or removed | Find its new name in desktop's file; update the list in the phone module (`epub_reader.py`, `statistics_view.py`, `writing.py`) |
| A translation key is gone | The phone shows a desktop text (`t("…")`) that desktop dropped or renamed | Use desktop's new key |
| A shipped entry is missing | A file listed in `addon.py` `SHIPPED_ENTRIES` (and `build.gradle.kts`) moved | Update both lists |
| A mirrored function changed, or was never recorded | Desktop changed a function the phone copies (step 4) | Update the copy, then `--record` |

A `NOT_ANSWERED` entry needs a reason that says what the phone does instead ("desktop's dialog to replace a missing PDF;
the phone re-imports the document"). Decide deliberately: *desktop-only* (no reason to exist on a phone) or `GAP:`
(the phone should have it; then it also goes in `ROADMAP.md`).

## 4. Compare the mirrored code

`tools/mirrors.json` keeps a fingerprint of the source of each desktop function the phone copies ("Mirrors ``name``" in
the phone's Python). The check lists:

- **changed since copied**: desktop changed that function. Read its diff
  (`git -C vendor/incremento diff <old>..<new> -- <file>`) and the phone's copy (search the phone's Python for the
  name), and change the copy the same way;
- **never recorded**: a new copy. Compare it with desktop's function once.

When every listed copy matches desktop again, record the fingerprints and commit `mirrors.json` with the rest:

```
ankrimento/.venv/Scripts/python ankrimento/tools/parity_check.py --record
```

A new copy on the phone gets a "Mirrors ``name``" note (the desktop function's name, optionally `path.py:name`), so the
check finds it. The modules with copies: `pdf_reader.py`, `pdf_cards.py`, `epub_reader.py`, `extract.py`, `writing.py`,
`video_reader.py`, `statistics_view.py`, `pdf_links.py`, `document_review.py`, `upkeep.py`.

Do not record to silence the check: a fingerprint says "I compared this copy with this version of desktop's function".

## 5. Run the tests

```
cd ankrimento && .venv/Scripts/python -m pytest python-tests
cd .. && ./gradlew.bat :AnkiDroid:assemblePlayDebug :ankrimento:testDebugUnitTest
```

The Python tests run desktop's backend against a real collection; `test_parity.py` repeats the parity check,
`test_runtime.py` checks which desktop files ship, `test_upstream_report.py` guards this procedure's tool. Gradle builds
the APK with Chaquopy (it needs network the first time) and runs the Kotlin tests under Robolectric.

Failures that a new version causes, and what they mean:

| Failure | Cause | Fix |
|---|---|---|
| `AttributeError: 'QPdfDocument' object has no attribute …` (many tests at once) | Upstream calls something new on a Qt class the phone stands in for | Extend the stand-in in `src/main/python/PyQt6` or `aqt`, with a test next to it (`test_qtpdf.py`). Make it behave like Qt where desktop checks the result, not just return a value |
| `No module named 'frontend'`, or `'__name__' not in globals` while compiling a page script | The compiled upstream function now imports a sibling `frontend/` module, or reads `__name__` | Ship that module (see step 2) and give the compile namespace in the phone module `__name__`/`__package__` (`epub_reader.py` does) |
| A phone test about word counts, toolbar buttons or Markdown fails | Desktop changed the writing dock's helpers or buttons (`writing.py` mirrors them) | Compare with `_apply_markdown_transform` and `_count_words`, update the mirror and its test |
| A test that never touched upstream fails after the pin moved | A new setting default, a new table or a changed return value in a backend function the phone calls through `facade.py` | Read the upstream change of that function; the phone follows upstream |

A test that only fails in the full run is usually order dependent: run it alone first.

## 6. New features that need a decision at once

A new upstream behaviour can reach the phone without a feature request: a new check on import, a new column. For each,
decide *before* the device check: **keep it** (the phone does it too), **adapt it** (the phone does the same through
another library: `pypdf` stands behind the PDF checks, since PyMuPDF is not on the phone), or **refuse it clearly** (an
error in desktop's words). Record the choice in the commit message, and the limits in `ROADMAP.md`.

## 7. Data safety

- The phone's `incremento.db` is migrated by the new upstream code. Open the phone's **full backup** with desktop
  Incremento (the version in the pin, or the last tag) and check that it restores and shows the documents.
- Anything the phone writes that desktop reads (cards, extracts, highlights, writing files) goes through upstream
  functions, so a new schema is covered; a table the phone adds itself is not. The sync engine owns its own tables
  (`sync_schema.py`), so upstream's migration numbers cannot collide with it.

## 8. Check on a device

Install the debug build on the tablet and go through what the changelog touches, plus a short round: a session (topic
and item), a PDF (import one with a text layer and one without; extract, highlight, Find) and an EPUB (extract,
highlight, Find), a Markdown writing topic with the toolbar, the knowledge tree, statistics, a full backup. Restore
what the test changed (documents added, settings) when done.

## 9. Evaluate and update the roadmap

For each changelog bullet and each new message the parity check lists, answer in this order:

1. **Does it change data the phone shares with desktop?** Then the phone must keep reading and writing it (done by steps
   3 to 7).
2. **Does a phone user do this?** Passive and background features (tracking what was read, sorting the library, safer
   imports) usually do; windowed desktop workflows (tabs, side-by-side panes, hover previews, the MCP server) do not.
3. **Is it cheap through upstream's backend?** The backend usually exposes it already (`reader_coverage.py` computes
   what was viewed); the phone then only needs a screen.

Put each feature in `ROADMAP.md` (⬜ to do, 🟡 partial, ➖ desktop-only with the reason, `GAP:` in `NOT_ANSWERED` for the
messages), and in the order of work when it matters on a phone.

## 10. Commit

One commit (or the branch squashed to one): the new submodule pin, the phone's changes, `mirrors.json`, the roadmap.
The message names the desktop version as `git describe` prints it (`v2026.11.04-23-g9b46946`), why that target and not
the last tag, what broke and was adapted, and what was left (the `GAP:` entries). Commit as the user, with no Claude
author or co-author lines.

## Known gaps

The parity check prints the messages marked `GAP:`: desktop features reached from the reader pages that the phone does
not have yet. They are also in `ROADMAP.md`.

## The sync engine overlay

Incremento's sync engine (`backend/sync_*.py`, upstream issue 16) is not in upstream yet (checked at v2026.11.04 + 23:
no `sync_*` file), so it ships beside the vendored add-on from `ankrimento/engine/`. The `incrementoAddonSource` Gradle
task and the test harness (`python-tests/conftest.py`, `assemble_addon_source`) merge `engine/backend` into the add-on's
`backend/`; nothing in `vendor/incremento` is edited, and the engine creates its own tables (`sync_schema.py`), so the
vendored schema, whatever its number, is fine.

On every update, check that nothing upstream collides with the overlay: `git -C vendor/incremento ls-tree -r --name-only
HEAD | grep backend/sync_`. If upstream now has the engine, delete `engine/` and the `from("engine")` block in
`build.gradle.kts`.

The overlay also carries `sync_links.py` (PDF page and passage links, EPUB connections: one record per link, keyed by its two ends); a migration that adds a column to `document_links` or `epub_connections` must be classified in `sync_records.py` and carried there.

The overlay also carries `sync_files.py`, which shares the files of local file cards (managed copies) with every device; it reads the collection's notes, so a new Anki or upstream note-type change to "Incremento Local File" (field names, the `managed_copy` mode) is what to check there.

Refresh the overlay from an Incremento checkout with `python ankrimento/tools/update_engine.py <checkout>`. It records the
commit and a hash per file in `engine/ENGINE.json`; `test_engine_sync.py` fails if a file was edited by hand.
