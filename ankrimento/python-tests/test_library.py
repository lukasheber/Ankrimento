"""The library: every Incremento document, with what desktop's bookshelf knows of it."""

from __future__ import annotations

import json

from ankrimento import facade


def test_library_lists_documents_with_kind_title_priority_and_position(rt):
    video = json.loads(facade.import_link("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Library talk"))["card_id"]
    facade.video_progress(video, 125.0, 0.0)
    web = json.loads(facade.import_link("https://example.org/library-essay", "Library essay"))["card_id"]
    writing = facade.create_writing("Library notes")

    entries = {entry["card_id"]: entry for entry in json.loads(facade.library())}

    assert entries[video]["kind"] == "video"
    assert entries[video]["title"] == "Library talk"
    assert entries[video]["position"] == "2:05"
    assert entries[web]["kind"] == "web"
    assert entries[writing]["kind"] == "writing"
    assert entries[writing]["title"] == "Library notes"
    assert all(entry["priority"] == 50.0 for entry in (entries[video], entries[web], entries[writing]))
    assert not entries[video]["done"]
    # Only documents: ordinary topics and items are not in the library.
    assert {entry["kind"] for entry in entries.values()} <= {"pdf", "epub", "web", "video", "writing"}


def test_a_document_with_a_cover_image_gives_its_file(rt, tmp_path):
    from test_pdf import _MINIMAL_PDF

    source = tmp_path / "Covered book.pdf"
    source.write_bytes(_MINIMAL_PDF)
    card_id = facade.import_pdf(str(source), "Covered book", priority=50.0)
    note = rt.col.get_card(card_id).note()
    note["PDF_Cover_Image"] = rt.col.media.write_data("library-cover.png", b"\x89PNG\r\n\x1a\n")
    rt.col.update_note(note)

    entries = {entry["card_id"]: entry for entry in json.loads(facade.library())}

    # Desktop's bookshelf shows the cover named in the cover field, from the collection's media.
    cover = entries[card_id]["cover"]
    assert cover.endswith("library-cover.png")
    import os

    assert os.path.isfile(cover)


def test_reading_positions_come_as_numbers_for_the_app_to_word(rt, tmp_path):
    """"p. 12" and "ch. 3" are words: the app writes them in its own language."""
    from test_epub import _write_epub

    path = tmp_path / "Positioned.epub"
    _write_epub(path)
    book = facade.import_epub(str(path), "Positioned", priority=50.0)
    facade.epub_message(book, 1, "incremento_epub_progress:" + json.dumps({"cardId": book, "sectionIndex": 1, "scrollRatio": 0.0}))

    entry = next(entry for entry in json.loads(facade.library()) if entry["card_id"] == book)
    assert (entry["chapter"], entry["page"]) == (2, 0)
