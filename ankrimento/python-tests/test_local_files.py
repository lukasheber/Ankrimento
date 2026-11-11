"""Local file cards: any file kept in the profile, opened with another app."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ankrimento import facade

NOTE = b"Lecture handout, week 3.\n" * 20


def _file(tmp_path, name="Handout.docx", data=NOTE) -> str:
    path = tmp_path / name
    path.write_bytes(data)
    return str(path)


def _add(tmp_path, name="Handout.docx", data=NOTE, **kwargs) -> dict:
    return json.loads(facade.import_local_file(_file(tmp_path, name, data), kwargs.pop("title", "Handout"), "Topics", 50.0, kwargs.pop("note", "")))


def test_a_file_becomes_a_local_file_card_with_a_managed_copy(rt, tmp_path):
    from incremento.backend.local_file_manager import LOCAL_FILE_MODE_FIELD, LOCAL_FILE_NOTE_TYPE, LOCAL_FILE_PATH_FIELD

    added = _add(tmp_path)

    note = rt.col.get_card(added["card_id"]).note()
    assert note.note_type()["name"] == LOCAL_FILE_NOTE_TYPE
    assert note[LOCAL_FILE_MODE_FIELD] == "managed_copy"
    stored = rt.addon_dir / "user_files" / rt.profile / note[LOCAL_FILE_PATH_FIELD]
    assert stored.read_bytes() == NOTE
    assert facade.card_reader(added["card_id"]) == "file"


def test_the_screen_gets_the_name_size_note_and_where_the_file_is(rt, tmp_path):
    added = _add(tmp_path, note="Read before the lab.")

    info = json.loads(facade.local_file_info(added["card_id"]))

    assert info["name"] == "Handout.docx"
    assert info["mode"] == "managed_copy"
    assert info["available"] is True
    assert info["size"] == len(NOTE)
    assert info["note"] == "Read before the lab."
    assert info["mime"] == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    assert Path(info["path"]).read_bytes() == NOTE


def test_a_file_with_an_unknown_type_is_opened_as_plain_bytes(rt, tmp_path):
    added = _add(tmp_path, name="data.zzz")

    assert json.loads(facade.local_file_info(added["card_id"]))["mime"] == "application/octet-stream"


def test_a_card_made_on_desktop_with_a_reference_path_says_its_file_is_on_that_computer(rt, tmp_path):
    from incremento.backend.local_file_manager import add_local_file_card

    source = _file(tmp_path, "Elsewhere.pdf")
    card_id = add_local_file_card(str(rt.addon_dir), rt.profile, rt.col, source_path=source, title="Elsewhere", mode="reference")

    info = json.loads(facade.local_file_info(card_id))

    # The path belongs to the computer that made the card, even when it happens to exist here.
    assert info["mode"] == "reference"
    assert info["available"] is False
    assert info["original_path"] == source


def test_attaching_a_copy_turns_a_reference_into_a_managed_copy(rt, tmp_path):
    from incremento.backend.local_file_manager import LOCAL_FILE_MODE_FIELD, LOCAL_FILE_PATH_FIELD, add_local_file_card

    card_id = add_local_file_card(str(rt.addon_dir), rt.profile, rt.col, source_path=_file(tmp_path, "Old.pdf"), title="Old", mode="reference")
    replacement = _file(tmp_path, "Phone copy.pdf", b"%PDF-1.4 the phone's own copy")

    info = json.loads(facade.local_file_attach(card_id, replacement))

    note = rt.col.get_card(card_id).note()
    assert note[LOCAL_FILE_MODE_FIELD] == "managed_copy"
    assert note[LOCAL_FILE_PATH_FIELD].startswith("files/")
    assert info["available"] is True and info["mode"] == "managed_copy"
    assert Path(info["path"]).read_bytes() == b"%PDF-1.4 the phone's own copy"


def test_attaching_a_copy_to_a_managed_card_replaces_its_file(rt, tmp_path):
    added = _add(tmp_path)

    info = json.loads(facade.local_file_attach(added["card_id"], _file(tmp_path, "Handout v2.docx", b"second version")))

    assert Path(info["path"]).read_bytes() == b"second version"
    assert info["name"] == "Handout v2.docx"


def test_an_installable_package_is_kept_but_never_opened(rt, tmp_path):
    added = _add(tmp_path, name="tool.apk", data=b"PK\x03\x04 not really")

    info = json.loads(facade.local_file_info(added["card_id"]))

    assert info["can_open"] is False
    assert info["blocked"] == "package"


def test_an_ordinary_file_can_be_opened(rt, tmp_path):
    assert json.loads(facade.local_file_info(_add(tmp_path)["card_id"]))["can_open"] is True


def test_a_file_too_large_to_keep_and_share_is_refused(rt, tmp_path, monkeypatch):
    from ankrimento import local_files

    monkeypatch.setattr(local_files, "MAX_BYTES", 10)

    with pytest.raises(ValueError, match="too large"):
        _add(tmp_path, data=b"x" * 11)


def test_a_missing_source_is_refused(rt, tmp_path):
    with pytest.raises(FileNotFoundError):
        facade.import_local_file(str(tmp_path / "nothing.bin"), "Nothing", "Topics", 50.0, "")


def test_a_pdf_or_epub_is_better_added_as_a_document(rt):
    assert facade.local_file_suggests("Paper.PDF") == "pdf"
    assert facade.local_file_suggests("book.epub") == "epub"
    assert facade.local_file_suggests("notes.docx") == ""


def test_the_library_lists_a_file_with_the_other_documents(rt, tmp_path):
    added = _add(tmp_path, title="Library handout")

    entry = next(e for e in json.loads(facade.library()) if e["card_id"] == added["card_id"])

    assert entry["kind"] == "file"
    assert entry["title"] == "Library handout"
    assert entry["viewed_percent"] is None  # nothing to read in order, so no bar
