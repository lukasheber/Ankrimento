"""PDF snapshots: a region of the page as an image in the extract, as desktop's snapshot does."""

from __future__ import annotations

import base64
import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF

# A 1x1 PNG.
_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


@pytest.fixture
def pdf(rt, tmp_path):
    path = tmp_path / "Snapshot source.pdf"
    path.write_bytes(_MINIMAL_PDF)
    card_id = facade.import_pdf(str(path), "Snapshot source", priority=50.0)
    yield card_id
    facade.discard_extract(card_id)
    rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_a_snapshot_becomes_an_image_in_the_extract_with_its_page_link(rt, pdf):
    from aqt import mw

    events = []
    previous = mw.host_events
    mw.host_events = lambda event, fields: events.append(event)
    try:
        image = "data:image/png;base64," + base64.b64encode(_PNG).decode()
        facade.pdf_message(pdf, "incremento_pdf_snapshot:" + json.dumps({"cardId": pdf, "page": 1, "image": image}))
    finally:
        mw.host_events = previous
    assert "extract_draft" in events
    front = json.loads(facade.extract_draft(pdf))["fields"][0]
    assert front.startswith('<img src="') and ".png" in front
    name = front.split('"')[1]
    assert rt.col.media.have(name)
    # Desktop's link back to the page follows the image.
    assert "incremento" in front.split(">", 1)[1]


def test_an_oversized_or_broken_snapshot_is_refused(rt, pdf):
    from aqt import mw

    messages = []
    previous = mw.host_events
    mw.host_events = lambda event, fields: messages.append((event, fields))
    try:
        facade.pdf_message(pdf, "incremento_pdf_snapshot:" + json.dumps({"cardId": pdf, "page": 1, "image": "not base64!"}))
    finally:
        mw.host_events = previous
    assert any(event == "message" for event, _fields in messages)
    assert json.loads(facade.extract_draft(pdf))["fields"][0] == ""
