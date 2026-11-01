"""Full backup: desktop's backup ZIP, written on the device."""

from __future__ import annotations

import copy
import errno
import json
import os
import zipfile
from pathlib import Path

import pytest

from ankrimento import backup, facade


@pytest.fixture(autouse=True)
def _keep_config(rt):
    """Restores write Incremento's settings; later tests expect them as they were."""
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    yield
    mw.addonManager.writeConfig("incremento", saved)


def test_full_backup_is_desktops_zip_and_passes_upstreams_restore_check(rt, tmp_path):
    writing = facade.create_writing("Backup notes")
    facade.writing_save(writing, "Kept in the backup.")
    archive_path = tmp_path / "backup.zip"

    exported = json.loads(facade.export_full_backup(str(archive_path)))

    with zipfile.ZipFile(archive_path) as archive:
        names = set(archive.namelist())
        manifest = json.loads(archive.read("manifest.json"))
    profile = rt.profile
    assert {
        "anki/all_decks.apkg",
        f"user_files/{profile}/incremento.db",
        "config.json",
        "restore.txt",
        "data/priorities.json",
        "data/pdf_progress.json",
        "data/highlights.json",
        "data/stats.json",
    } <= names
    assert any(name.startswith(f"user_files/{profile}/writing/") for name in names)
    assert manifest["addon"] == "Incremento"
    assert manifest["profile_storage_key"] == profile
    # Not upstream's exporter.count, which current Anki no longer sets (it would be 0).
    assert manifest["counts"]["anki_cards_exported"] == rt.col.card_count() > 0
    assert exported["cards"] == rt.col.card_count()

    # Upstream's own restore pre-check accepts it, so desktop can restore it.
    report = backup.precheck(str(archive_path))
    assert report.card_count == rt.col.card_count()
    assert report.source_profile == profile
    assert not report.profile_mismatch


def test_full_backup_keeps_pdf_covers_that_anki_does_not_see(rt, tmp_path):
    from test_pdf import _MINIMAL_PDF

    source = tmp_path / "Covered.pdf"
    source.write_bytes(_MINIMAL_PDF)
    note = rt.col.get_card(facade.import_pdf(str(source), "Covered", priority=50.0)).note()
    cover = rt.col.media.write_data("covered-cover.png", b"\x89PNG\r\n\x1a\n")
    # A bare filename, as upstream stores covers: Anki's media scan does not count it as used.
    note["PDF_Cover_Image"] = cover
    rt.col.update_note(note)
    archive_path = tmp_path / "backup.zip"

    facade.export_full_backup(str(archive_path))

    with zipfile.ZipFile(archive_path) as archive:
        package_path = tmp_path / "all_decks.apkg"
        package_path.write_bytes(archive.read("anki/all_decks.apkg"))
    with zipfile.ZipFile(package_path) as package:
        media = json.loads(package.read("media"))
        assert cover in media.values()
        key = next(key for key, name in media.items() if name == cover)
        assert package.read(key) == b"\x89PNG\r\n\x1a\n"
    assert backup.precheck(str(archive_path)).missing_cover_count == 0


def test_full_backup_reaches_a_folder_on_other_storage(rt, tmp_path, monkeypatch):
    # Shared storage is another file system from the app's temporary folder: a rename across fails.
    rename = os.replace

    def replace_within_a_folder(source, target):
        if Path(source).parent != Path(target).parent:
            raise OSError(errno.EXDEV, "Cross-device link")
        rename(source, target)

    monkeypatch.setattr(os, "replace", replace_within_a_folder)
    archive_path = tmp_path / "shared" / "backup.zip"
    archive_path.parent.mkdir()

    facade.export_full_backup(str(archive_path))

    assert zipfile.is_zipfile(archive_path)
    assert [path.name for path in archive_path.parent.iterdir()] == ["backup.zip"]


def _files(folder):
    # Not SQLite's -wal/-shm files, which come and go as the database is opened and closed.
    return {
        path.relative_to(folder).as_posix()
        for path in folder.rglob("*")
        if path.is_file() and not path.name.endswith(("-wal", "-shm"))
    }


def test_restore_puts_the_backups_files_in_place_and_rolls_back_if_the_collection_restore_fails(rt, tmp_path):
    from incremento.backend import paths

    runtime_dir = paths.get_user_files_dir(str(rt.addon_dir), rt.profile)
    facade.writing_save(facade.create_writing("Before the backup"), "kept")
    archive_path = tmp_path / "backup.zip"
    facade.export_full_backup(str(archive_path))
    in_backup = _files(runtime_dir)
    facade.writing_save(facade.create_writing("After the backup"), "newer")
    current = _files(runtime_dir)
    assert current - in_backup

    report = json.loads(facade.restore_stage(str(archive_path)))
    assert report["cards"] > 0
    assert report["source_profile"] == rt.profile
    package = facade.restore_replace_files()
    assert zipfile.is_zipfile(package)
    assert not (current - in_backup) & _files(runtime_dir)

    # The host could not restore the collection: this device's files come back.
    facade.restore_finish(False)
    assert _files(runtime_dir) == current

    facade.restore_stage(str(archive_path))
    facade.restore_replace_files()
    facade.restore_finish(True)
    restored = _files(runtime_dir)
    assert not (current - in_backup) & restored
    assert {name for name in in_backup if name.startswith("writing/")} <= restored
    # Nothing is left behind beside the profile's folder.
    assert sorted(path.name for path in runtime_dir.parent.iterdir()) == [rt.profile]


def test_restore_keeps_this_devices_automatic_backup_settings(rt, tmp_path):
    from aqt import mw
    from incremento.backend.config_service import load_addon_config, save_addon_config

    archive_path = tmp_path / "backup.zip"
    facade.export_full_backup(str(archive_path))
    config = load_addon_config(mw.addonManager, "incremento")
    config["automatic_backups"] = {rt.profile: {"enabled": False, "directory": "this device"}}
    save_addon_config(mw.addonManager, "incremento", config)

    facade.restore_stage(str(archive_path))
    facade.restore_replace_files()
    facade.restore_finish(True)

    kept = load_addon_config(mw.addonManager, "incremento")["automatic_backups"][rt.profile]
    assert kept["directory"] == "this device"


def _slot(rt, number: int) -> str:
    from incremento.backend.paths import sanitize_profile_name

    return f"incremento_{sanitize_profile_name(rt.profile)}_auto_backup_slot_{number:02d}.zip"


def test_automatic_backups_fill_their_slots_then_reuse_the_oldest(rt):
    """Upstream's rotation (next_backup_path, prune_backups), over the files the folder holds."""
    assert json.loads(facade.automatic_backup_plan("[]", 3)) == {"write": _slot(rt, 1), "remove": []}

    held = [[_slot(rt, 1), 100], [_slot(rt, 2), 300], [_slot(rt, 3), 200], ["my-own.zip", 50]]
    assert json.loads(facade.automatic_backup_plan(json.dumps(held), 3)) == {"write": _slot(rt, 1), "remove": []}

    # Fewer versions kept than are held: the oldest go, never the one being written nor other files.
    plan = json.loads(facade.automatic_backup_plan(json.dumps(held), 2))
    assert plan["write"] == _slot(rt, 1)
    assert plan["remove"] == [_slot(rt, 3)]
