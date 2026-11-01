"""An EPUB's highlights, listed and changed outside the page."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_epub import _write_epub


@pytest.fixture
def book(rt, tmp_path):
    path = tmp_path / "Highlighted.epub"
    _write_epub(path)
    return facade.import_epub(str(path), "Highlighted", priority=50.0)


def _highlight(card_id: int, highlight_id: str, section: int, text: str) -> None:
    """A highlight as the page script reports one."""
    highlight = {"id": highlight_id, "sectionIndex": section, "color": "yellow", "text": text, "startOffset": 0, "endOffset": len(text)}
    facade.epub_message(card_id, section, "incremento_epub_hl_add:" + json.dumps({"cardId": card_id, "highlight": highlight}))


def _highlights(card_id: int) -> list[dict]:
    return json.loads(facade.epub_marks(card_id))["highlights"]


def test_highlights_are_listed_in_book_order_with_their_chapter(rt, book):
    _highlight(book, "e2", 1, "Incremental reading")
    _highlight(book, "e1", 0, "Spaced repetition")

    assert [(h["id"], h["section"], h["title"], h["text"]) for h in _highlights(book)] == [
        ("e1", 0, "Chapter One", "Spaced repetition"),
        ("e2", 1, "Chapter Two", "Incremental reading"),
    ]


def test_a_highlight_is_recoloured_noted_and_deleted_and_the_page_follows(rt, book):
    _highlight(book, "e3", 0, "Spaced repetition")

    assert "incrementoUpdateEpubHighlightColor" in facade.epub_highlight_color(book, "e3", "purple")
    assert "incrementoUpdateEpubHighlightNote" in facade.epub_highlight_note(book, "e3", "Core idea")
    shown = _highlights(book)[0]
    assert (shown["color"], shown["note"]) == ("purple", "Core idea")

    script = facade.epub_delete_highlight(book, "e3", 0)
    assert "incrementoReplaceEpubHighlights" in script
    assert _highlights(book) == []
