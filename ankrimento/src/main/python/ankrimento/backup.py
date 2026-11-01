"""Full backup: desktop's "Export Full Backup" ZIP, made on the device.

The archive is the one desktop writes (``incremento/__init__.py``,
``_start_full_backup``; desktop wiring), built with the same upstream helpers,
so desktop restores a phone backup and the device can check a desktop one.

Two differences, both where current Anki changed under upstream:

- The card count comes from ``Collection.export_anki_package``. Upstream
  reads ``exporter.count``, which Anki's legacy exporter shim no longer sets,
  so its manifest says 0 cards and its own pre-check then rejects the backup.
- PDF and EPUB cover images (bare filenames Anki's media scan does not see)
  are added to the package afterwards; upstream's ``prepareMedia`` override,
  which did this, is no longer called by Anki's exporter.
"""

from __future__ import annotations

import datetime
import json
import os
import shutil
import tempfile
import zipfile
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from unittest import mock

from ankrimento import runtime


def export(out_path: str) -> dict[str, int]:
    """Writes the full backup of this profile to ``out_path``."""
    import anki.buildinfo
    from anki.collection import ExportAnkiPackageOptions
    from aqt import mw
    from incremento.backend import paths
    from incremento.backend.config_service import load_addon_config
    from incremento.backend.db import (
        DB_NAME,
        export_highlights_json,
        export_pdf_progress_json,
        export_priorities_json,
        export_stats_json,
        get_connection,
    )
    from incremento.backend.export_bundle import snapshot_database, snapshot_tree

    rt = runtime.current()
    addon_dir = str(rt.addon_dir)
    profile = rt.profile
    user_files_dir = str(paths.get_user_files_dir(addon_dir, profile))
    config = load_addon_config(mw.addonManager, "incremento")
    today = datetime.date.today().isoformat()

    conn = get_connection(addon_dir, profile)
    conn.commit()
    priority_count = conn.execute("SELECT COUNT(*) FROM priorities").fetchone()[0]
    highlight_count = conn.execute("SELECT COUNT(*) FROM pdf_highlights").fetchone()[0]
    pdf_progress_count = conn.execute("SELECT COUNT(*) FROM pdf_progress").fetchone()[0]
    stats_count = conn.execute("SELECT COUNT(*) FROM stats").fetchone()[0]

    with tempfile.TemporaryDirectory(prefix="incremento_export_") as tmp_dir:
        tmp_root = Path(tmp_dir)
        apkg_path = tmp_root / "all_decks.apkg"
        db_snapshot_path = tmp_root / DB_NAME
        database = snapshot_database(paths.get_db_path(addon_dir, profile), db_snapshot_path)

        cards = rt.col.export_anki_package(
            out_path=str(apkg_path),
            options=ExportAnkiPackageOptions(
                with_scheduling=True,
                with_deck_configs=True,
                with_media=True,
                legacy=True,
            ),
            limit=None,
        )
        _add_covers(rt.col, apkg_path)

        stage_root = tmp_root / "bundle"
        user_files_stage = stage_root / "user_files"
        profile_stage = user_files_stage / profile
        profile_stage.mkdir(parents=True)
        user_files_stats = snapshot_tree(
            user_files_dir,
            str(profile_stage),
            skip_relpaths={DB_NAME, f"{DB_NAME}-wal", f"{DB_NAME}-shm"},
        )
        db_stage_path = profile_stage / DB_NAME
        db_stage_path.write_bytes(db_snapshot_path.read_bytes())

        # Written beside the destination, as upstream does: the final rename stays on one file system.
        archive_fd, archive_name = tempfile.mkstemp(
            prefix=".incremento-backup-", suffix=".zip", dir=os.path.dirname(os.path.abspath(out_path))
        )
        os.close(archive_fd)
        archive_tmp_path = Path(archive_name)
        try:
            with zipfile.ZipFile(archive_tmp_path, "w", zipfile.ZIP_DEFLATED) as zf:
                zf.write(apkg_path, "anki/all_decks.apkg")
                zf.write(db_stage_path, f"user_files/{profile}/{DB_NAME}")
                for root, _, filenames in os.walk(user_files_stage):
                    for filename in filenames:
                        file_path = Path(root) / filename
                        if file_path == db_stage_path:
                            continue
                        zf.write(file_path, file_path.relative_to(stage_root).as_posix())
                zf.writestr("config.json", json.dumps(config, ensure_ascii=False, indent=2))
                zf.writestr("restore.txt", _restore_instructions(profile, mw.pm.name))
                zf.writestr("data/priorities.json", export_priorities_json(addon_dir, profile))
                zf.writestr("data/pdf_progress.json", export_pdf_progress_json(addon_dir, profile))
                zf.writestr("data/highlights.json", export_highlights_json(addon_dir, profile))
                zf.writestr("data/stats.json", export_stats_json(addon_dir, profile))
                user_files_copied = int(user_files_stats["files_copied"]) + 1
                user_files_bytes = int(user_files_stats["bytes_copied"]) + db_stage_path.stat().st_size
                manifest = {
                    "schema_version": 2,
                    "export_date": today,
                    "addon": "Incremento",
                    "scope": "current_profile",
                    "anki_version": anki.buildinfo.version,
                    "profile": mw.pm.name,
                    "profile_storage_key": profile,
                    "database": database,
                    "counts": {
                        "anki_cards_exported": int(cards),
                        "priorities": int(priority_count or 0),
                        "pdf_progress": int(pdf_progress_count or 0),
                        "highlights": int(highlight_count or 0),
                        "stats_rows": int(stats_count or 0),
                        "user_files_copied": user_files_copied,
                        "user_files_skipped": int(user_files_stats["files_skipped"]),
                        "user_files_bytes": user_files_bytes,
                    },
                    "files": {
                        "anki/all_decks.apkg": "All decks from the current Anki profile, including scheduling and media",
                        f"user_files/{profile}/": "Incremento runtime snapshot for the current profile "
                        "(PDFs, EPUBs, videos, writing, browser profiles, database)",
                        "config.json": "Incremento add-on config",
                        "restore.txt": "Restore instructions for a fresh install",
                        "data/priorities.json": "Card priorities (human-readable copy)",
                        "data/pdf_progress.json": "PDF reading positions, zoom levels, and appearance choices",
                        "data/highlights.json": "PDF text highlights",
                        "data/stats.json": "Session, daily and lifetime statistics",
                    },
                }
                zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))

            with zipfile.ZipFile(archive_tmp_path) as check_zip:
                if check_zip.testzip() is not None:
                    raise RuntimeError("Backup ZIP validation failed")
            os.replace(archive_tmp_path, out_path)
        except BaseException:
            archive_tmp_path.unlink(missing_ok=True)
            raise
    return {"cards": int(cards), "files": user_files_copied, "bytes": os.path.getsize(out_path)}


def automatic_plan(held: list[tuple[str, int]], keep: int) -> dict[str, Any]:
    """Which automatic backup to write next in the backup folder, and which to remove.

    The folder is one the user picked through Android's storage framework, not a path, so
    upstream's rotation (``next_backup_path``, then ``prune_backups`` keeping the new one)
    runs on a stand-in folder of empty files with the same names and modification times
    (``held``: name and milliseconds).
    """
    import time

    from incremento.backend.backup_schedule import next_backup_path, prune_backups

    profile = runtime.current().profile
    with tempfile.TemporaryDirectory() as stand_in:
        folder = Path(stand_in)
        for name, modified_ms in held:
            if not name or "/" in name or "\\" in name or name in (".", ".."):
                continue
            path = folder / name
            path.touch()
            os.utime(path, ns=(int(modified_ms) * 1_000_000, int(modified_ms) * 1_000_000))
        target = next_backup_path(folder, profile, int(keep))
        target.touch()
        now = time.time_ns()
        os.utime(target, ns=(now, now))
        removed = prune_backups(folder, profile, int(keep), protected=target)
        return {"write": target.name, "remove": sorted(path.name for path in removed)}


def precheck(path: str) -> Any:
    """Upstream's restore pre-check of a full backup (``BackupReport``).

    Upstream ends by opening the package with the installed Anki. Here that
    would open a second collection on AnkiDroid's own backend, replacing the
    one in use, so that step is left out; upstream's SQLite checks of the
    same package (tables, header, orphan cards, card count) still run.
    """
    from incremento.backend import restore_bundle

    with _without_second_collection(restore_bundle):
        return restore_bundle.precheck_backup(path, target_profile=runtime.current().profile)


@contextmanager
def _without_second_collection(restore_bundle: Any):
    with mock.patch.object(restore_bundle, "_verify_anki_open", lambda path, expected_cards: None):
        yield


class _Restore:
    """A restore in progress: upstream's staged backup, then the swapped Incremento folder."""

    def __init__(self, staged: Any, staging_dir: Path) -> None:
        self.staged = staged
        self.staging_dir = staging_dir
        self.swap: Any = None
        self.previous_config: dict | None = None

    def cleanup(self) -> None:
        self.staged.cleanup()
        shutil.rmtree(self.staging_dir, ignore_errors=True)


_restore: _Restore | None = None


def stage_restore(path: str) -> dict[str, Any]:
    """Checks a full backup and unpacks it beside this profile's folder (upstream's pre-check and staging).

    Nothing on the device changes yet; returns what the backup holds, for the user to confirm.
    """
    from incremento.backend import paths, restore_bundle

    discard_restore()
    rt = runtime.current()
    report = precheck(path)
    staging_dir = Path(tempfile.mkdtemp(prefix="incremento-restore-"))
    try:
        # Upstream stages the collection beside the live one; here the host restores the
        # collection from the package, so it is staged in a temporary folder instead.
        staged = restore_bundle.stage_backup(
            path, report, staging_dir / "collection.anki2", paths.get_user_files_dir(str(rt.addon_dir), rt.profile)
        )
    except BaseException:
        shutil.rmtree(staging_dir, ignore_errors=True)
        raise
    global _restore
    _restore = _Restore(staged, staging_dir)
    return {
        "source_profile": report.source_profile,
        "target_profile": report.target_profile,
        "profile_mismatch": report.profile_mismatch,
        "cards": report.card_count,
        "media": report.media_count,
        "files": report.profile_file_count,
        "missing_covers": report.missing_cover_count,
    }


def replace_files() -> str:
    """Puts the staged Incremento folder and settings in place, keeping this device's until :func:`finish_restore`.

    Returns the backup's Anki package, which the host restores as the collection
    (AnkiDroid's own collection-package import), as desktop swaps in the collection file.
    """
    import copy

    from aqt import mw
    from incremento.backend import paths, restore_bundle
    from incremento.backend.config_service import load_addon_config, save_addon_config
    from incremento.backend.db import close_connection

    restore = _require_restore()
    rt = runtime.current()
    addon_dir = str(rt.addon_dir)
    runtime_dir = paths.get_user_files_dir(addon_dir, rt.profile)
    close_connection(addon_dir, rt.profile)
    previous = restore.staged.runtime_stage_dir / "previous"
    swap = restore_bundle.BackupSwap([])
    if runtime_dir.exists():
        os.replace(runtime_dir, previous)
    try:
        os.replace(restore.staged.runtime, runtime_dir)
    except BaseException:
        if previous.exists():
            os.replace(previous, runtime_dir)
        raise
    swap.changes.append((runtime_dir, previous if previous.exists() else None))
    restore.swap = swap
    # As desktop's restore: the backup's settings, but this device's automatic-backup folders.
    restore.previous_config = copy.deepcopy(load_addon_config(mw.addonManager, "incremento"))
    restored = copy.deepcopy(restore.staged.config)
    restored["automatic_backups"] = copy.deepcopy(restore.previous_config.get("automatic_backups", {}))
    save_addon_config(mw.addonManager, "incremento", restored)
    return str(restore.staged.profile_stage_dir / "package.apkg")


def finish_restore(succeeded: bool) -> None:
    """After the host's collection restore: keep the restored folder, or put this device's back."""
    from aqt import mw
    from incremento.backend.config_service import save_addon_config
    from incremento.backend.db import close_connection

    global _restore
    restore = _require_restore()
    rt = runtime.current()
    try:
        if restore.swap is not None:
            if succeeded:
                restore.swap.commit()
            else:
                close_connection(str(rt.addon_dir), rt.profile)
                restore.swap.rollback()
                if restore.previous_config is not None:
                    save_addon_config(mw.addonManager, "incremento", restore.previous_config)
    finally:
        restore.cleanup()
        _restore = None


def discard_restore() -> None:
    """Drops a staged restore that was not confirmed."""
    global _restore
    if _restore is not None and _restore.swap is None:
        _restore.cleanup()
        _restore = None


def _require_restore() -> _Restore:
    if _restore is None:
        raise RuntimeError("No full backup is staged for restore")
    return _restore


def _add_covers(col: Any, apkg_path: Path) -> None:
    """Adds referenced PDF/EPUB cover images missing from the package, as upstream's exporter did."""
    from incremento.backend.backup_media import _safe_cover_filename
    from incremento.backend.epub_manager import EPUB_COVER_FIELD, EPUB_NOTE_TYPE
    from incremento.backend.pdf_manager import PDF_COVER_FIELD, PDF_NOTE_TYPE

    media_dir = Path(col.media.dir())
    covers: list[str] = []
    for model_name, cover_field in ((PDF_NOTE_TYPE, PDF_COVER_FIELD), (EPUB_NOTE_TYPE, EPUB_COVER_FIELD)):
        model = col.models.by_name(model_name)
        if model is None:
            continue
        field_index = next((i for i, field in enumerate(model["flds"]) if field["name"] == cover_field), None)
        if field_index is None:
            continue
        for fields in col.db.list("SELECT flds FROM notes WHERE mid = ?", model["id"]):
            parts = fields.split("\x1f")
            if len(parts) > field_index:
                name = parts[field_index].strip()
                path = media_dir / name
                if _safe_cover_filename(name) and path.is_file() and not path.is_symlink():
                    covers.append(name)
    with zipfile.ZipFile(apkg_path) as apkg:
        media = json.loads(apkg.read("media"))
    included = set(media.values())
    missing = [name for name in dict.fromkeys(covers) if name not in included]
    if not missing:
        return
    next_key = max((int(key) for key in media), default=-1) + 1
    # A ZIP member can't be replaced in place: copy the package with the covers and the new media map.
    rewritten = apkg_path.with_suffix(".tmp")
    with zipfile.ZipFile(apkg_path) as source, zipfile.ZipFile(rewritten, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            if info.filename != "media":
                with source.open(info) as data, target.open(info, "w") as out:
                    shutil.copyfileobj(data, out)
        for offset, name in enumerate(missing):
            key = str(next_key + offset)
            target.write(media_dir / name, key)
            media[key] = name
        target.writestr("media", json.dumps(media))
    os.replace(rewritten, apkg_path)


def _restore_instructions(profile: str, profile_display_name: str) -> str:
    """Upstream's restore.txt text."""
    return "\n".join(
        [
            "Incremento Current-Profile Backup Restore",
            "==============================",
            "",
            "This archive contains:",
            "1. anki/all_decks.apkg  -> Anki cards, scheduling, and referenced media",
            f"2. user_files/{profile}/ -> Incremento profile files and database",
            "3. config.json          -> Incremento add-on settings",
            "",
            "Recommended restore order:",
            "1. Install Anki.",
            "2. Install the Incremento add-on.",
            "3. Open the Anki profile you want to replace.",
            "4. Choose Incremento -> Restore Full Backup and select this ZIP.",
            "5. Review the pre-check and confirm replacement.",
            "6. Verify cards, media, PDFs, writing notes, highlights, and progress.",
            "7. Re-enable automatic collection/media sync only after checking the restored profile.",
            "",
            "Notes:",
            f"- This backup belongs to the Anki profile: {profile_display_name}",
            "- Both the APKG and Incremento runtime snapshot cover that profile only.",
            "- The runtime snapshot includes PDFs, EPUBs, videos, writing files, browser profiles, and the database.",
            "- A safety backup of the current Anki collection is made before replacement.",
            "- Automatic-backup folder settings for existing profiles are retained.",
            "- AnkiWeb is not checked; a later ordinary sync may merge remote changes or deletions.",
            "- If this restored collection should replace AnkiWeb, force a one-way Upload.",
            "  Download replaces the restored cards; media sync always merges separately.",
        ]
    )
