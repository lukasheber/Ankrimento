# Release checklist

What stands between the current debug build and a public release. Tick each item as it is done;
the order is the suggested one. See `ROADMAP.md` for features and `UPSTREAM.md` for taking in a new
desktop Incremento.

## 1. Agreement with Incremento's author (blocker)

- [x] Contact the author: explain the app, ask for consent to bundle the add-on unchanged. (2026-11-12: agreed to an APK build on GitHub under the same licence as AnkiDroid, and to the sync engine staying a separate add-on.)
- [ ] Check the add-on's licence and its compatibility with AnkiDroid's GPL-3.0; record it in the
      repository (`LICENSES/`, `REUSE.toml`) and in the app's About screen.
- [ ] Agree how the author and the add-on are credited (store listing, About, first run).
- [x] Agree the sync format for Incremento's own data (see 2): a separate add-on, [incremento-sync](https://github.com/lukasheber/incremento-sync).

## 2. Data shared with desktop (blocker)

AnkiWeb syncs the collection, not Incremento's own data (priorities, knowledge tree, highlights,
reading positions, topic schedule).

- [ ] Decide for the first release: phone-only (said plainly in the listing and on first run), or a
      one-way import of desktop's data, or the agreed sync.
- [ ] If phone-only: warn when the collection syncs with a desktop that has Incremento, so users
      are not surprised by data that differs.
- [ ] Check what desktop does with cards and topics made on the phone (open them on desktop after an
      AnkiWeb sync: priority, tree, documents' files).
- [ ] Full backup made on the phone restores on desktop (and the reverse) with the release build.

## 3. The app's own identity (blocker)

- [x] Name: Ankrimento (store and trademark check still to do before a store listing).
- [x] Application ID `com.memoria.ankrimento`, so it installs beside AnkiDroid without conflict.
- [ ] Icon, splash and store graphics.
- [x] Crash reports and usage analytics: off (no endpoint, settings hidden).
- [ ] Links in the app (help, issues, privacy, donate, About): own pages, AnkiDroid credited.
- [ ] AnkiDroid's own update checks, analytics and AnkiWeb wording: review what the fork keeps.
- [x] Source published: github.com/lukasheber/Ankrimento (About still to link it).

## 4. Release build

- [x] Release variant (`full` flavour) builds with R8 and lint; nothing broke at start, import, PDF, EPUB, session.
- [ ] Signing key created (outside the repo, `~/.ankrimento-signing/`); back it up: without it, updates cannot be signed.
- [x] Sizes: arm64 72 MB, x86_64 74 MB; only arm64 and x86_64 are built (the Python runtime has no 32-bit build).
- [ ] Confirm the debug smoke-test activity and its options are absent.
- [ ] Smoke-test the release build on the tablet: start, a session (topic and item), PDF, EPUB,
      web, video, extract, cloze, statistics, backup and restore.

## 5. Devices and data sizes

- [ ] At least two phones (a small screen, a low-memory one) besides the tablet.
- [ ] Android 7 (API 24) to the current version; on Android 7 check snapshots (their fallback).
- [ ] A large collection (thousands of cards), a large PDF (500+ pages), a long EPUB.
- [ ] Battery and storage after a week of normal use.
- [ ] First launch after a fresh install: time it; show progress while Python unpacks if it is long.

## 6. First use

- [ ] Getting Started on first run: topics and items, priority, extracts and clozes, the session.
- [ ] Empty states that say what to do (empty library, empty session, empty tree).
- [ ] Help links to a short guide.

## 7. Languages and accessibility

- [ ] Native review of Brazilian Portuguese, Croatian and Simplified Chinese, or ship English first.
- [ ] TalkBack walk-through: the readers, the session bar, the sheets and dialogs.
- [ ] Font size at 200 %, display size at largest: nothing clipped.
- [ ] Contrast in light and dark themes and in each reader's page colours.

## 8. Still to check on a device

- [ ] Leech notice in the learning screen (a review card failed to its leech threshold).
- [ ] Automatic backup folder picker and a backup written there.
- [ ] Share to Incremento from a browser and a file manager.

## 9. Store and legal

- [ ] Privacy policy (what stays on the device; crash reports, if any).
- [ ] Google Play: data-safety form, content rating, target SDK as required, closed test first.
- [ ] Alternatively or also: GitHub releases, F-Droid (reproducible build requirements).
- [ ] Store listing: what the app is, that it is a fork of AnkiDroid with desktop Incremento, its
      limits (phone-only data until sync).

## 10. Keeping it up to date

- [ ] A procedure for AnkiDroid's own updates (merging upstream), like `UPSTREAM.md` for Incremento.
- [ ] Release notes and versioning.
- [ ] Where users report bugs, and how they are triaged.

## Suggested path

A limited beta (a GitHub release or a Play closed test) once 1, 3 and 4 are done, labelled
phone-only until sync exists; use the beta for 5 and 7.
