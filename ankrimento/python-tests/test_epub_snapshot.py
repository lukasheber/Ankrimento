"""An EPUB snapshot: a region of the chapter kept as an image in a new card, with the link back."""

from __future__ import annotations

import base64
import json

import pytest

from ankrimento import facade
from test_epub import _write_epub
from test_pdf_snapshot import _PNG


@pytest.fixture
def book(rt, tmp_path):
    path = tmp_path / "Snap Book.epub"
    _write_epub(path)
    card_id = facade.import_epub(str(path), "Snap Book", priority=30.0)
    yield card_id
    facade.discard_extract(card_id)


def test_a_region_becomes_the_cards_image_with_the_link_to_its_chapter(rt, book):
    image = "data:image/png;base64," + base64.b64encode(_PNG).decode()
    facade.epub_snapshot(book, 1, image)
    draft = json.loads(facade.extract_draft(book))
    front = draft["fields"][0]
    assert front.startswith('<img src="') and ".png" in front
    name = front.split('"')[1]
    assert rt.col.media.have(name)
    # Desktop's link back: the chapter it was taken from.
    assert "incremento_open_epub:" in front


def test_a_broken_image_is_explained_not_added(rt, book):
    events = []
    from aqt import mw

    previous = mw.host_events
    mw.host_events = lambda event, fields: events.append((event, fields))
    try:
        facade.epub_snapshot(book, 0, "data:image/png;base64,not-base64!!")
    finally:
        mw.host_events = previous
    assert json.loads(facade.extract_draft(book))["fields"] == ["", ""]
    assert events and events[-1][0] == "message"
