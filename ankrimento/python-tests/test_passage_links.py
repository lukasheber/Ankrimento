"""Following a passage link between documents: open the other end with its passage revealed, and a way back."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_epub import _write_epub

PASSAGE = {"quote": "the exact explanation", "rects": [[0.1, 0.2, 0.3, 0.05]]}


def _pdf(tmp_path_factory, name: str, pages: int = 3) -> int:
    from pypdf import PdfWriter

    source = tmp_path_factory.mktemp("passage-pdf") / f"{name}.pdf"
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(200, 200)
    with open(source, "wb") as handle:
        writer.write(handle)
    return facade.import_pdf(str(source), name, priority=50.0)


@pytest.fixture(scope="module")
def pdfs(rt, tmp_path_factory):
    from incremento.backend.document_links import add_document_link

    source, target = _pdf(tmp_path_factory, "Passage source"), _pdf(tmp_path_factory, "Passage target")
    link = add_document_link(
        str(rt.addon_dir), rt.profile, source_card_id=source, source_kind="pdf", source_page=1, target_card_id=target,
        target_page=2, target_scroll=0.25, target_passage=PASSAGE, source_passage={"quote": "where it is asked"},
    )
    return source, target, link


@pytest.fixture(scope="module")
def books(rt, tmp_path_factory):
    from incremento.backend.epub_connections import add_connection

    ids = []
    for name in ("Linked book A", "Linked book B"):
        path = tmp_path_factory.mktemp("passage-epub") / f"{name}.epub"
        _write_epub(path)
        ids.append(facade.import_epub(str(path), name, priority=50.0))
    for card_id in ids:
        facade.epub_open(card_id)  # extracts the book, as opening it does
    link = add_connection(
        str(rt.addon_dir), rt.profile,
        {"card_id": ids[0], "section": 0, "offset": 3, "scroll": 0.1, "passage": {"quote": "Spaced repetition", "start": 0, "end": 17}},
        {"card_id": ids[1], "section": 1, "offset": 5, "scroll": 0.5, "passage": {"quote": "Incremental reading", "start": 0, "end": 19}},
        quote="Spaced repetition",
    )
    return ids[0], ids[1], link


# --- PDF -----------------------------------------------------------------------------------------------------------

def test_a_pdf_links_target_carries_the_passage_at_its_other_end(rt, pdfs):
    source, target, link = pdfs

    place = json.loads(facade.pdf_page_link_target(source, 1, link, "outgoing"))

    assert (place["card_id"], place["page"]) == (target, 2)
    assert place["passage"] == PASSAGE


def test_followed_back_from_the_target_the_passage_is_the_sources(rt, pdfs):
    source, target, link = pdfs

    place = json.loads(facade.pdf_page_link_target(target, 2, link, "incoming"))

    assert (place["card_id"], place["page"]) == (source, 1)
    assert place["passage"] == {"quote": "where it is asked"}


def test_the_viewer_is_started_at_the_page_with_the_passage_to_reveal_and_a_way_back(rt, pdfs):
    _, target, _ = pdfs

    script = facade.pdf_jump_script(target, "https://pdf/file", "https://pdf/worker", 2, 0.25, -1.0, json.dumps(PASSAGE), True)

    start = script[script.index("window.incrementoPdfStart("):]
    arguments = json.loads("[" + start[start.index("(") + 1:start.rindex(");")] + "]")
    assert arguments[2] == 2  # the page
    assert arguments[-1] == PASSAGE  # desktop's last start argument: the passage to reveal
    assert "window._incDocumentBackAvailable = true;" in script
    assert "incrementoSetDocumentBackAvailable" in script


def test_a_jump_does_not_move_where_reading_was_left(rt, pdfs):
    from incremento.backend.pdf_manager import get_page

    _, target, _ = pdfs
    before = get_page(str(rt.addon_dir), rt.profile, target)

    facade.pdf_jump_script(target, "https://pdf/file", "https://pdf/worker", 3, 0.0, -1.0, "", True)

    assert get_page(str(rt.addon_dir), rt.profile, target) == before


def test_an_ordinary_start_has_no_passage_and_no_way_back(rt, pdfs):
    _, target, _ = pdfs

    script = facade.pdf_start_script(target, "https://pdf/file", "https://pdf/worker")

    assert "window._incDocumentBackAvailable = false;" in script


def test_a_passage_that_is_not_one_is_refused(rt, pdfs):
    _, target, _ = pdfs

    with pytest.raises(ValueError):
        facade.pdf_jump_script(target, "https://pdf/file", "https://pdf/worker", 2, 0.0, -1.0, json.dumps({"evil": 1}), True)


# --- EPUB ----------------------------------------------------------------------------------------------------------

def test_a_chapters_connections_are_listed_from_either_end(rt, books):
    first, second, link = books

    outgoing = json.loads(facade.epub_connections(first, 0))
    incoming = json.loads(facade.epub_connections(second, 1))

    assert [(row["id"], row["direction"], row["title"], row["section"]) for row in outgoing] == [(link, "outgoing", "Linked book B", 1)]
    assert [(row["id"], row["direction"], row["title"], row["section"]) for row in incoming] == [(link, "incoming", "Linked book A", 0)]
    assert outgoing[0]["quote"] == "Incremental reading"  # the words at the other end
    assert outgoing[0]["available"] is True
    assert json.loads(facade.epub_connections(first, 1)) == []


def test_a_connections_target_is_the_other_books_place_and_passage(rt, books):
    first, second, link = books

    place = json.loads(facade.epub_connection_target(first, 0, link))

    assert (place["card_id"], place["section"], place["scroll"]) == (second, 1, 0.5)
    assert place["passage"]["quote"] == "Incremental reading"


def test_the_page_script_is_told_which_passage_to_reveal(rt, books):
    _, second, _ = books
    passage = {"quote": "Incremental reading", "start": 0, "end": 19}

    script = facade.epub_script(second, 1, "abc123", 0.5, "", "", json.dumps(passage))

    assert '"quote": "Incremental reading"' in script


def test_a_passage_the_page_cannot_find_is_said_in_desktops_words(rt, books):
    from incremento.backend.i18n import t

    _, second, _ = books

    reply = json.loads(facade.epub_message(second, 1, "incremento_epub_passage_status:" + json.dumps({"cardId": second, "matched": False})))

    assert reply == {"toast": t("reader_passage_unavailable")}


def test_a_connection_to_a_book_that_is_gone_is_listed_as_unavailable(rt, books, tmp_path):
    from incremento.backend.epub_connections import add_connection

    first, _, _ = books
    path = tmp_path / "Doomed book.epub"
    _write_epub(path)
    doomed = facade.import_epub(str(path), "Doomed book", priority=50.0)
    facade.epub_open(doomed)
    link = add_connection(str(rt.addon_dir), rt.profile, {"card_id": first, "section": 1, "offset": 0, "scroll": 0.0},
                          {"card_id": doomed, "section": 0, "offset": 0, "scroll": 0.0})
    rt.col.remove_notes([rt.col.get_card(doomed).nid])

    rows = json.loads(facade.epub_connections(first, 1))

    assert [(row["id"], row["available"]) for row in rows] == [(link, False)]
    with pytest.raises(ValueError):
        facade.epub_connection_target(first, 1, link)


def test_a_connection_can_be_removed_from_either_end(rt, books):
    from incremento.backend.epub_connections import add_connection

    first, second, _ = books
    link = add_connection(str(rt.addon_dir), rt.profile, {"card_id": first, "section": 0, "offset": 9, "scroll": 0.0},
                          {"card_id": second, "section": 0, "offset": 9, "scroll": 0.0})

    assert facade.epub_connection_remove(second, 0, link) is True
    assert all(row["id"] != link for row in json.loads(facade.epub_connections(first, 0)))


def test_a_book_extracted_by_an_older_version_is_refreshed_not_called_unavailable(rt, books):
    from pathlib import Path

    from incremento.backend.epub_reference import resolve_epub_reference

    first, second, link = books
    root = Path(resolve_epub_reference(str(rt.addon_dir), rt.profile, rt.col, second)["root"])
    metadata = json.loads((root / "metadata.json").read_text(encoding="utf8"))
    metadata["sanitizer_version"] = "older"
    (root / "metadata.json").write_text(json.dumps(metadata), encoding="utf8")

    rows = json.loads(facade.epub_connections(first, 0))

    assert [(row["id"], row["available"], row["title"]) for row in rows if row["id"] == link] == [(link, True, "Linked book B")]
    assert json.loads(facade.epub_connection_target(first, 0, link))["card_id"] == second
