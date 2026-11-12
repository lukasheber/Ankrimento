# Ankrimento

Incremental reading on Android: a fork of [AnkiDroid](https://github.com/ankidroid/Anki-Android) that runs the Python
backend of the desktop add-on [Incremento](https://github.com/BaskovicP/Incremento), unchanged, inside the app. It keeps
your decks and cards in AnkiDroid and adds what Incremento adds to Anki on the desktop: a mixed session of topics and
items, a reader for PDFs, EPUBs, web pages, video and text, extracts and clozes made from what you read, priorities, the
knowledge tree, statistics, writing, and backups.

**Status: beta.** [0.1.0 beta 1](https://github.com/lukasheber/Ankrimento/releases) is out for 64-bit Android devices. It is
tested on one tablet only, so expect rough edges; `ankrimento/ROADMAP.md` lists what is done, what is deliberately not on the
phone, and what is left, and `ankrimento/RELEASE_CHECKLIST.md` what stands between this and a store release.

## Install

Download the APK for your device from the [releases](https://github.com/lukasheber/Ankrimento/releases) (`arm64-v8a` for
almost every phone and tablet, `x86_64` for emulators and Chromebooks), check it against `SHA256SUMS.txt`, and open it
(Android asks to allow installs from your browser or file manager). It installs beside AnkiDroid and does not touch it.
On first start it asks for "All files access", which keeps your collection in a place that survives uninstalling.
The APKs are signed with the key whose SHA-256 is `93:12:FD:94:FD:47:4E:5B:CA:CE:F2:72:E1:54:46:28:FD:2A:35:87:68:F1:84:D0:44:23:71:59:A8:08:79:E2`.

## Three repositories

| | |
|---|---|
| **Ankrimento** (this one) | The Android app: AnkiDroid with the `ankrimento/` module and a few hooks in AnkiDroid's screens |
| [Incremento](https://github.com/BaskovicP/Incremento) | The desktop add-on by Paulo (BaskovicP), vendored unchanged in `vendor/incremento` |
| [Incremento Sync](https://github.com/lukasheber/incremento-sync) | A separate Anki add-on that syncs Incremento's own data between devices; the same files run on desktop Anki and in this app (`vendor/incremento-sync`). It switches off cleanly: *Settings → This device → Incremento Sync* here, `enabled` in its config on desktop |

AnkiWeb keeps syncing the cards as usual; Incremento Sync carries what Incremento keeps beside them (priorities, the
tree, positions, highlights, documents…) through a shared folder or a small hub on your network.

## Build

```
git clone --recurse-submodules https://github.com/lukasheber/Ankrimento
cd Ankrimento
./gradlew :AnkiDroid:assemblePlayDebug        # APK: AnkiDroid/build/outputs/apk/play/debug/
```

Needs a 64-bit Android device (arm64). The Python side has its own tests (they run the unchanged add-on on a desktop
Anki backend): see `ankrimento/README.md` for the virtualenv, then `cd ankrimento && .venv/bin/python -m pytest`.

`ankrimento/README.md` explains how the pieces fit together; `ankrimento/UPSTREAM.md` is the procedure for taking in a new
Incremento (and the parity check that guards it).

## Branches

`main` follows AnkiDroid's `main`, so AnkiDroid's own updates can be merged in. **`ankrimento`** is the app and the default
branch.

## Licence and credit

GPL-3.0-or-later, as AnkiDroid (`COPYING`, `LICENSES/`). AnkiDroid is the work of its contributors; its original README is
kept as `README.AnkiDroid.md`. Incremento is Paulo's (BaskovicP) work; Incremento's code stays under Incremento's own terms and
is loaded from its repository, not changed. This project is not affiliated with or endorsed by AnkiDroid or Anki.
