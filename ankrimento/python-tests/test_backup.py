"""Full backup: desktop's backup ZIP, written on the device."""

from __future__ import annotations

import errno
import json
import os
import zipfile
from pathlib import Path

from ankrimento import backup, facade


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
