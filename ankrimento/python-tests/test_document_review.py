"""Reviewing the cards made from a document, as desktop's Review All for a PDF or EPUB."""

from __future__ import annotations

import json
import threading

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture
def document(rt, tmp_path):
    """A PDF with an item and a topic extracted from it."""
    path = tmp_path / "Reviewed.pdf"
    path.write_bytes(_MINIMAL_PDF)
    card = facade.import_pdf(str(path), "Reviewed", priority=50.0)
    notes = []
    for text, kind in (("Reviewed item", "item"), ("Reviewed topic", "topic")):
        facade.pdf_message(card, "incremento_fill_field:" + json.dumps({"idx": 0, "text": text}))
        notes.append(json.loads(facade.create_extract(card, json.dumps([text, "back"]), "Basic", kind, 45.0))["note_id"])
    yield card
    rt.col.remove_notes(notes + [rt.col.get_card(card).nid])


def _preview(card: int, **options) -> dict:
    return json.loads(facade.document_review_preview(card, "pdf", 1, json.dumps(options)))


def test_the_preview_counts_the_documents_cards_for_each_choice(rt, document):
    everything = _preview(document)
    assert (everything["selected"], everything["topics"], everything["items"]) == (2, 1, 1)
    assert _preview(document, card_kind="items")["selected"] == 1
    # Desktop's own names for the choices.
    assert [value for value, _label in everything["labels"]["order"]][:2] == ["attached", "media_position"]
    assert all(label for _value, label in everything["labels"]["state"])


def test_review_starts_with_the_chosen_cards_in_desktops_review_deck(rt, document):
    from aqt import mw

    entered = threading.Event()
    previous = mw.host_events
    mw.host_events = lambda event, fields: entered.set() if event == "move_to_state" and fields.get("state") == "review" else None
    try:
        assert facade.document_review_start(document, "pdf", 1, json.dumps({"card_kind": "both"}))
        assert entered.wait(timeout=30)
    finally:
        mw.host_events = previous
    deck_id = rt.col.decks.id_for_name("Incremento PDF Review")
    assert len(rt.col.find_cards(f"did:{deck_id}")) == 2
    facade.reviewer_closed()
