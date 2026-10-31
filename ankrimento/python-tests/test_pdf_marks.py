"""A PDF's bookmarks and highlights, listed and changed outside the viewer."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade, pdf_reader
from test_pdf import _MINIMAL_PDF


@pytest.fixture
def pdf(rt, tmp_path):
    source = tmp_path / "Marked.pdf"
    source.write_bytes(_MINIMAL_PDF)
    return facade.import_pdf(str(source), "Marked", priority=50.0)


def _marks(card_id: int) -> dict:
    return json.loads(facade.pdf_marks(card_id))


def _highlight(card_id: int, highlight_id: str, page: int, text: str) -> None:
    """A highlight as the viewer reports one."""
    highlight = {"id": highlight_id, "page": page, "color": "yellow", "text": text, "rects": [[0.1, 0.1, 0.2, 0.05]]}
    pdf_reader.handle(card_id, "incremento_pdf_hl_add:" + json.dumps({"cardId": card_id, "highlight": highlight}))


def test_a_bookmark_is_added_listed_and_deleted_and_the_viewer_follows(rt, pdf):
    script = facade.pdf_add_bookmark(pdf, 3)

    bookmarks = _marks(pdf)["bookmarks"]
    assert [b["page"] for b in bookmarks] == [3]
    assert bookmarks[0]["label"]
    assert "incrementoReceivePdfBookmarks" in script

    script = facade.pdf_delete_bookmark(pdf, bookmarks[0]["id"])
    assert _marks(pdf)["bookmarks"] == []
    assert "incrementoReceivePdfBookmarks" in script


def test_highlights_are_listed_by_page_and_can_be_recoloured_noted_and_deleted(rt, pdf):
    from incremento.backend.pdf_highlights import load_highlights

    _highlight(pdf, "h2", 2, "second page words")
    _highlight(pdf, "h1", 1, "first page words")

    highlights = _marks(pdf)["highlights"]
    assert [(h["id"], h["page"], h["text"], h["color"]) for h in highlights] == [
        ("h1", 1, "first page words", "yellow"),
        ("h2", 2, "second page words", "yellow"),
    ]

    assert "incrementoReceivePdfHighlights" in facade.pdf_highlight_color(pdf, "h1", "green")
    facade.pdf_highlight_note(pdf, "h1", "Why this matters")
    stored = {h["id"]: h for h in load_highlights(str(rt.addon_dir), rt.profile, pdf)}
    assert stored["h1"]["color"] == "green"
    assert stored["h1"]["note"] == "Why this matters"

    assert "incrementoReceivePdfHighlights" in facade.pdf_delete_highlight(pdf, "h2")
    assert [h["id"] for h in _marks(pdf)["highlights"]] == ["h1"]


def test_an_unknown_colour_is_refused(rt, pdf):
    _highlight(pdf, "h3", 1, "words")

    with pytest.raises(Exception):
        facade.pdf_highlight_color(pdf, "h3", "not-a-colour")


def test_a_recoloured_highlight_still_shows_in_the_viewer(rt, pdf):
    _highlight(pdf, "h4", 1, "visible words")

    script = facade.pdf_highlight_color(pdf, "h4", "blue")

    # The viewer paints a highlight with annotation data but no native annotation ("kind")
    # transparent; upstream's update adds a resize revision, and only desktop's PDF annotation
    # sync, which the device lacks, adds the kind.
    payload = json.loads(script[script.index("(") + 1 : script.rindex(")")])
    shown = next(h for h in payload if h["id"] == "h4")
    assert shown["color"] == "blue"
    assert "kind" in shown.get("pdf_annotation", {"kind": "none"})
