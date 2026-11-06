# Incremento sync: plan

Upstream issue: [BaskovicP/Incremento#16](https://github.com/BaskovicP/Incremento/issues/16).
The author agreed to the contribution and set its terms:

- Snapshots only for setup and recovery.
- Record-level revisions for everyday sync, found by comparing saved state with a baseline. No sync hooks in every UI action.
- Ten requirements to investigate and demonstrate before incoming data is applied automatically.
- First contribution: a bounded **desktop reader-sync prototype** (priorities, bookmarks, reading positions, managed document state). Topic scheduling, writing, knowledge tree, presets and statistics come later.
- A short design note and small staged PRs.
- Android is a separate discussion.

This plan meets those terms. It also leaves room for what we want later: LAN sync between phone and computer, and computer-to-computer sync through a cloud folder or a server. The way to get all three without three sync systems is to build **one revision format and one engine, with interchangeable transports**. The folder comes first because the author asked for it; LAN and server are later transports for the same data.

---

## 1. Architecture in one picture

```
 Incremento features (unchanged)          other device(s)
        │ save as today                          ▲
        ▼                                        │
  incremento.db / managed files                  │
        │                                        │
  ┌─────┴──────────── sync engine (backend/sync/) ───────────────┐
  │ family adapters → normalized records                        │
  │ scanner: live view vs baseline → new revisions (capture)    │
  │ outbox → packs + blobs ──► transport ──► peers              │
  │ inbox ◄── packs + blobs ◄── transport ◄── peers             │
  │ applier: verify → check live vs baseline → apply/merge/     │
  │          conflict, in one transaction with baseline+inbox   │
  └──────────────────────────────────────────────────────────────┘
  transports: SharedFolder (stage 1) · LAN HTTP (stage 4) · relay/WebDAV (stage 5)
```

- **Features stay untouched.** They keep writing to their current tables and files. The engine reads saved state, as the author suggested.
- **The engine is plain Python in upstream's `backend/`**, with no Qt. The desktop add-on and Ankrimento (through Chaquopy) run the same code, so the phone can never interpret records differently from the desktop.
- **Transports only move immutable objects** (packs and blobs). Every guarantee (ordering, duplicates, conflicts, Anki dependencies) lives in the engine, so a new transport cannot weaken them.

## 2. The data model

### Identities
| Identity | What it is | Where it lives |
|---|---|---|
| `library_id` | One shared Incremento library: UUID created when sync is first enabled | `sync_meta` table + `library.json` in the exchange |
| `device_id` | One install × profile: random UUID | `sync_meta` |
| `device_epoch` | Raised on restore or re-setup, so a restored device never reuses old sequence numbers | `sync_meta` |
| Anki reference | `card_id` + `note_id` + **note GUID** + card ordinal | inside every card-scoped record |

Profile names, filenames and file hashes are never identities (requirement 7). Card IDs survive AnkiWeb sync, and the GUID check catches a different collection that happens to reuse a card ID.

### Record families (adapters)
Each supported table becomes a **family adapter** with four functions. Each runs inside a transaction the caller passes in (requirement 4: "transaction-capable adapters").

```python
class Family:
    name: str                      # "priority", "reader_bookmark", ...
    def scan(conn) -> Iterable[(key, normalized_value)]  # complete or raise
    def read(conn, key) -> normalized_value | None
    def write(conn, key, value) -> None                    # no commit inside
    def delete(conn, key) -> None
    merge: MergePolicy             # see 5
```

**Normalization** (requirement 1):
- Canonical JSON: sorted keys, fixed float rounding per field.
- Allowlisted fields only.
- Incidental fields (`updated_at`, caches, indexes) are excluded from the value.

Each family declares which fields are durable. A test checks the table schema against the adapter, so a new column upstream fails CI until someone classifies it as synced or local.

**First families (prototype scope):**
| Family | Table(s) | Key | Merge policy |
|---|---|---|---|
| `priority` | `priorities` | card ref | register (conflict on divergence) |
| `reader_bookmark` | `reader_bookmarks` | bookmark `id` | per-record register; add/add never conflicts |
| `reading_position` | `pdf_progress`, `epub_progress`, `video_progress`, `web_progress` | card ref + reader | register, with an optional "furthest read wins" auto-resolve (a semantic rule, not a timestamp) |
| `document` | managed file + its annotations (`pdf_highlights`/`epub_highlights`) + `content_items` row | content id | **versioned unit**: concurrent versions kept, no PDF merging (requirement 8) |

**Explicitly local (never exported):**
- Logs and journals: `operation_journal`, `import_journal`, `reconciliation_runs`.
- Indexes: `*_text_index`, `document_index_state`, `note_ocr_index`.
- Daily usage counters: `*_daily_limit_usage`.
- Statistics and history: `stats*`, `topic_review_history`, `reading_page_history`.
- Browser data: `browser_*`, recent tags.
- Linked-original absolute paths.
- Credentials and add-on dependencies.

**Later families, each needing its own contract:**
- `topic_schedule` and postpones (stage 6).
- Knowledge tree.
- Writing.
- Presets and configuration.
- Daily limits and their settings.

### Revisions
```
Revision {
  id:        sha256(canonical(everything below))
  library, family, key
  parents:   [revision ids]          # [] for a first value
  device, device_epoch, seq
  value | tombstone
  deps:      { anki: card ref, blobs: [sha256...] }
  format:    sync-format version
}
```
- **Order comes from ancestry, never from time** (requirement 6). A key has one or more *heads*.
  - Incoming revision descends from our head: fast-forward.
  - Our head descends from it: ignore it (it's old).
  - Neither: concurrent, so merge policy or conflict.
- **A conflict resolution is itself a revision** with both heads as parents. Competing history is kept.
- **Content addressing makes repeated delivery harmless** (requirement 5): applying the same id twice is a no-op.

### Local sync tables (in `incremento.db`, so they commit with the data)
- `sync_meta`: library, device, epoch, format.
- `sync_baseline(family, key, revision_id, value_hash)`: "this value has been captured locally".
- `sync_revisions`: known revisions and their parents.
- `sync_outbox`: captured revisions not yet published.
- `sync_inbox(revision_id, status, reason)`: statuses are `pending_parent`, `pending_blob`, `pending_anki`, `applied`, `conflict`, `rejected`.
- `sync_receipts(peer_device, max_seq)`: what we confirmed applying from each peer.
- `sync_conflicts`: open conflicts, shown to the user.

## 3. The ten requirements and how the design meets them

| # | Author's requirement | Design answer | Demonstration (test or two-profile scenario) |
|---|---|---|---|
| 1 | Consistent exports, normalization; failed scan ≠ empty | Adapters raise on any read error; a family's scan either completes or aborts the capture; deletions come only from a completed scan; normalization excludes incidental fields | Touch `updated_at` and rewrite JSON in a different order: no revision. Make a table unreadable mid-scan: no tombstones. |
| 2 | Crash-safe capture | One SQLite transaction inserts revisions, outbox rows and baseline updates; publication happens after, from the outbox, and is idempotent | Kill the process before, during and after capture; on restart the edit is either rediscovered or pending publication, never lost or duplicated |
| 3 | Unscanned local edits | Before applying, inside `BEGIN IMMEDIATE`, compare the live value with the baseline. If they differ, capture the local edit first, so the incoming revision becomes concurrent. Files are downloaded and staged before the transaction and re-checked at commit | Edit a priority, don't scan, deliver a remote change: you get a conflict, not an overwrite. Edit during a slow blob download: the same. |
| 4 | Atomic apply, no feedback loops | `write` + baseline update + inbox outcome in one transaction; the baseline holds the applied revision, so the next scan sees no change | Kill the process during apply: either fully applied or not at all; the next scan publishes nothing |
| 5 | Unreliable delivery | Packs carry their hash in their name and are checked before use; a missing parent gives `pending_parent`; a missing blob gives `pending_blob`; duplicates are no-ops; the lease file only warns | Deliver packs reversed, twice, and truncated; deliver a pack before its blobs |
| 6 | Ancestry, conflicts, deletion | Heads and parents, tombstones as revisions; edit vs delete is a conflict that keeps the edit visible; a missing package never means deletion | Concurrent priority edits produce a conflict; resolving it creates a merge revision. Delete vs edit. Remove a pack from the folder: nothing is deleted. |
| 7 | Identity, setup, restore, retention | Library, device and epoch IDs; Anki refs with GUIDs; joining a library turns existing local data into revisions with no parents, so identical values merge and different ones conflict; restore raises the epoch, and restored values matching a known revision adopt it as baseline, so they can't overwrite newer work; retention in 7 | Join with existing work on both sides. Restore an old backup on one device: newer work elsewhere survives. |
| 8 | PDF/EPUB consistency | A document version holds (file blob hash, annotation set hash, interchange state) as one revision. Capture runs through upstream's existing `pdf_annotations` locking. Concurrent versions are both kept (the second as a sibling version). Linked-original paths stay local | **Spike in stage 1**: annotate the same PDF on two devices; crash between the PDF write and the SQLite baseline |
| 9 | Anki arrival | A record whose card or GUID is missing stays `pending_anki`. It is re-checked after each Anki sync and on profile open. Apply never goes through `recover_interrupted_imports`, and journal rows carry the device and a sync origin | Deliver Incremento data before AnkiWeb delivers the card; run interrupted-import recovery; the record is kept and applied after the Anki sync |
| 10 | Profile lifecycle, validation | Each worker captures (profile, library, generation) and drops callbacks from an old generation; packs have a size limit; JSON is checked against per-family schemas; blobs are named only by hash (no paths from packages); format versions are explicit; the allowlist also governs setup snapshots | Switch profiles mid-sync. Fuzz packs: oversized, path traversal, wrong format, unknown family. |

## 4. The exchange layout (shared folder transport)

```
<chosen folder>/IncrementoSync/v1/<library_id>/
  library.json                   written once by the creator
  devices/<device_id>/
    device.json                  name, platform, format, epoch
    lease.json                   "open since…" (warning only)
    receipts.json                max seq applied per peer
    packs/<epoch>-<seq>-<sha256>.pack   immutable, append-only
  blobs/<aa>/<sha256>            content-addressed files (PDFs, EPUBs…)
  snapshots/<device_id>/<id>/    setup/recovery snapshots (allowlisted)
```

- **Single writer per directory.** A device writes only under its own `devices/<id>/`. Blobs are content-addressed, so two devices writing the same blob write identical bytes. Cloud providers therefore never need to create "conflicted copies".
- Packs are written to a temporary name and renamed. Since rename isn't atomic everywhere, the hash in the filename is the real check: a pack whose bytes don't match it is "not arrived yet".
- The folder is never the database. Nothing opens SQLite inside it, which fixes the WAL problem from the issue.

## 5. Merge policies
- **Register** (priority, settings-like values): concurrent edits create a conflict. The user picks one, or enters a value, in a small dialog that names both devices. Identical values merge silently.
- **Furthest-read register** (reading position): an *optional* rule, proposed to the author, that auto-resolves to the further position, since "I read further on the laptop" is almost always what the user means. Off by default until the author agrees.
- **Keyed set** (bookmarks, highlights by `id`): adds never conflict; an edit to the same `id` falls back to register.
- **Document unit**: concurrent versions are both kept, and the user chooses or keeps both.
- **Tombstones**: delete vs edit is a conflict that keeps the edit visible.
- **Records of deleted Anki cards** are not tombstoned. Each device's own reconciliation cleans them up when AnkiWeb deletes the card.

## 6. When sync runs
- Scan and publish: on profile close, after an AnkiWeb sync, after reader close, and on a quiet timer. All of it runs on a captured-profile worker, never on Qt's main thread. Families keep a cheap digest so an unchanged family is skipped.
- Fetch and apply: on profile open (after AnkiWeb sync finishes), after each AnkiWeb sync, and on the timer. Whatever is pending is re-checked each time.
- Nothing runs while an import is in progress. Records from in-flight imports are not exported until their journal entry has committed.

## 7. Retention
- Packs are deleted only by their own device, and only when every device in `devices/` has receipts covering them and a newer snapshot exists.
- A device unseen for N days (default 90) is shown as "inactive" and only stops retention after the user confirms.
- Blobs: mark and sweep from the live revisions' dependencies, run by any device but only after a grace period.

## 8. Stages and PRs

Each stage is a small upstream PR with tests. Merging is the author's call, and nothing is applied automatically until stage 3 passes the scenarios.

**Stage 0: design note (upstream doc PR, no code)**
- `docs/SYNC_DESIGN.md`, condensed from sections 1–7 and the table in 3.
- States exactly what stays local.
- The document-consistency spike (step 1c) is reported here as an addendum.

**Stage 1: foundations**
- **1a.** Sync tables, IDs and the format version; schema migration through upstream's ledger. No behavior.
- **1b.** The `priority` adapter, normalization, the scanner, crash-safe capture, and an outbox written to a local directory.
  - Tests: requirements 1 and 2.
  - A schema-coverage test: every column is classified.
- **1c. Spike:** document consistency with `pdf_annotations`. Write-up only.

**Stage 2: shared-folder transport (receive without applying)**
- Pack format, blobs, the exchange layout and bounded validation (requirement 10).
- The inbox records what *would* happen ("dry run"). The author asked us to demonstrate before enabling apply, and the dry run is that demonstration.
- Tests: requirement 5, using a fake folder that delivers files reordered, duplicated and truncated.

**Stage 3: apply and conflicts for priorities**
- The transactional applier, the live-vs-baseline check, receipts, and a minimal conflict dialog.
- A harness of two disposable profiles that runs the author's list:
  1. offline edits
  2. duplicate and reordered delivery
  3. interruption recovery
  4. unscanned local edits
  5. a real conflict
- Tested on our real two-computer setup.

**Stage 4: reader families and documents**
- Bookmarks, reading positions, then document units (built on the spike's findings).
- Anki-arrival pending (requirement 9) gets real tests here, because these records follow imports.

**Stage 5: setup, restore, retention, settings UI**
- Joining a library, restore epochs, retention, and a status line: "Last synced · 2 waiting for Anki sync · 1 conflict".

**Stage 6: proposals that need the author's contracts first**
- Topic scheduling. This includes the author's customData-reference experiment, which has a 100-byte budget shared with other data; it needs Undo/Redo, concurrent reviews and stale-reference tests.
- Knowledge tree, writing, presets.

**Stage 7: more transports (separate discussion, after the folder works)**
- **LAN** (phone ↔ computer, computer ↔ computer on one network): see 9.
- **Server**: see 10.

## 9. LAN sync (phone and computer on the same network)

Why it matters for Ankrimento: on Android, Google Drive, OneDrive and iCloud **don't keep a mirrored local folder**. Only Syncthing-style apps do. So for most phone users the shared folder isn't practical, and LAN sync is the realistic mobile path.

The design keeps LAN as just another transport:
- **The same objects over HTTP.** The desktop serves a tiny API:
  - `GET /v1/library`
  - `GET /v1/devices/<id>/packs?after=<seq>`
  - `GET/PUT /v1/blobs/<sha256>`
  - `PUT /v1/devices/<me>/packs/...`
  - `PUT /v1/devices/<me>/receipts`

  The engine above it is unchanged.
- **Discovery:** mDNS `_incremento-sync._tcp` (Android `NsdManager`; on desktop the pure-Python `zeroconf` package or a manual address). Pairing doesn't depend on discovery.
- **Pairing and security:**
  - The desktop shows a QR code with address, port, a one-time token and its TLS certificate fingerprint. The phone scans it, or the user types a 6-digit code.
  - Each device then has a long-term key pair. Every later connection uses TLS with a pinned certificate plus mutual authentication, and the server accepts only paired device IDs.
  - It listens only while the user enables it, and on the local network only.
  - The same bounded validation as the folder transport.
- **Who serves:**
  - The desktop serves; the phone is a client. That suits Android's background limits: a WorkManager job when the phone is on Wi-Fi, plus "Sync now".
  - Desktop to desktop works the same way.
- **Where the code lives:**
  - The client and server are plain Python in the engine, so Chaquopy runs the same client on the phone.
  - If the author prefers not to carry a network server in the add-on, it can ship as a small companion add-on that depends on the engine.

## 10. Computer-to-computer through a server

The author wants to avoid *operating* a service, so the plan never involves an Incremento-run server:
- **WebDAV transport:** the exchange layout of section 4 stored on WebDAV, for Nextcloud, ownCloud, many NAS boxes and paid hosts. It's the same single-writer layout, so no server logic is needed.
- **A self-hosted relay:** the LAN server from section 9, run headless (one Python file, or a Docker image) on a NAS or a VPS. It has the same API and the same pairing.
- **End-to-end encryption:** optional for the folder and server transports. Packs and blobs are encrypted with a library key that is shared at pairing, so a provider or host sees only ciphertext. It's added after stage 5, and the dependency choice (for example `cryptography`) is up to the author.

## 11. Ankrimento's side

- **Today's phone sync folder (`syncfolder.py`)** exchanges whole-database snapshots. The author has ruled that out for everyday sync.
  - Keep it hidden or marked "experimental, phone ↔ phone only" until the engine lands.
  - Then replace it. Don't make it compatible.
  - Its `SafStore` (Python reaching an Android SAF folder through Kotlin) can be reused as the phone's folder transport for Syncthing users.
- **Adopting the engine:**
  - Vendor upstream's `backend/sync/`. The parity check covers it like any other module.
  - Kotlin adds the settings UI, the QR scanner for pairing, NSD discovery and a WorkManager job.
- **Readiness:** phone records must use the same Anki references. They already do, since the phone uses upstream's tables and the same collection through AnkiWeb.
- **Release checklist:** item 2, "data shared with desktop", becomes "LAN or folder sync with the agreed engine". Until then, the phone stays phone-only, with the warning already in the checklist.

## 12. Risks and open questions for the author
1. **Document units:** how big a unit should be. A single PDF with 10,000 annotations means large revisions, so packs may need deltas later. The stage-1 spike answers this.
2. **The furthest-read rule** for reading positions: acceptable, or always a conflict?
3. **Where the engine lives:** `backend/sync/` in the add-on (preferred), with the LAN server possibly in a companion add-on.
4. **Scan cost** on large libraries (tens of thousands of highlights): family digests plus incremental hashing. Measure it in stage 1b against the performance audit's numbers.
5. **Upstream is moving:** `master` is 8 commits ahead of our vendored copy (`e6e0f0c` vs `ab88266`). Rebase onto it before stage 0 and keep each PR small, so it rebases cleanly.
6. **AnkiDroid's AI policy** applies only to AnkiDroid's own repository; Incremento's contribution terms are the author's. Say openly how the code is produced, so the author can judge it with that in mind.

## 13. What to do first
1. Reply on #16: thank the author, accept the scope, and say a design note will come first (stage 0) with the document spike.
2. Update the vendored upstream to `origin/master`.
3. Write `docs/SYNC_DESIGN.md` (stage 0) and the spike in parallel. Open the doc PR, and wait for the author's review before stage 1 code.
4. Set up the two-computer test bed: two disposable Anki profiles synced to one throwaway AnkiWeb account, plus a shared test folder.
