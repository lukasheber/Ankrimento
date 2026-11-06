"""The PDF viewer's card buttons: a card from a highlight, and the document's or page's cards."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF
from test_pdf_marks import _highlight


@pytest.fixture
def pdf(rt, tmp_path):
    source = tmp_path / "Carded.pdf"
    source.write_bytes(_MINIMAL_PDF)
    card_id = facade.import_pdf(str(source), "Carded", priority=50.0)
    yield card_id
    facade.discard_extract(card_id)


def _make_card(rt, pdf: int, highlight_id: str) -> int:
    """The highlight's card, made as the phone's sheet makes it; returns its note id."""
    assert json.loads(facade.pdf_highlight_card(pdf, highlight_id)) == {"draft": True}
    draft = json.loads(facade.extract_draft(pdf))
    result = json.loads(facade.create_extract(pdf, json.dumps(draft["fields"]), draft["note_type"], "item", 40.0))
    return int(result["note_id"])


def test_a_highlight_without_a_card_starts_one_with_its_text_and_a_link_back(rt, pdf):
    _highlight(pdf, "h1", 1, "Spacing beats cramming")

    assert json.loads(facade.pdf_highlight_card(pdf, "h1")) == {"draft": True}

    draft = json.loads(facade.extract_draft(pdf))
    assert draft["fields"][0].startswith("Spacing beats cramming")
    # Desktop's citation: the page, reopened at the highlight.
    assert "incremento_open_pdf_ref" in draft["fields"][0]
    assert "h1" in draft["fields"][0]


def test_a_highlight_with_a_card_opens_that_card(rt, pdf):
    _highlight(pdf, "h1", 1, "Spacing beats cramming")
    note_id = _make_card(rt, pdf, "h1")
    try:
        opened = json.loads(facade.pdf_highlight_card(pdf, "h1"))
        assert opened == {"card_id": rt.col.get_note(note_id).card_ids()[0]}
    finally:
        rt.col.remove_notes([note_id])
    # Its card deleted, the highlight starts a new one.
    assert json.loads(facade.pdf_highlight_card(pdf, "h1")) == {"draft": True}


def test_an_unknown_or_empty_highlight_is_explained(rt, pdf):
    assert json.loads(facade.pdf_highlight_card(pdf, "missing")) == {"message": "That PDF highlight could not be found."}
    _highlight(pdf, "blank", 1, "   ")
    assert json.loads(facade.pdf_highlight_card(pdf, "blank")) == {"message": "This highlight has no text to prefill."}


def test_the_documents_cards_are_one_browser_search(rt, pdf):
    assert json.loads(facade.pdf_document_cards(pdf)) == {"query": "", "message": "No cards created from this PDF yet."}
    _highlight(pdf, "h1", 1, "Spacing beats cramming")
    note_id = _make_card(rt, pdf, "h1")
    try:
        assert json.loads(facade.pdf_document_cards(pdf)) == {"query": f"nid:{note_id}", "message": ""}
    finally:
        rt.col.remove_notes([note_id])


def test_a_pages_cards_leave_out_deleted_notes_and_repeats(rt, pdf):
    _highlight(pdf, "h1", 1, "Spacing beats cramming")
    note_id = _make_card(rt, pdf, "h1")
    try:
        page = json.loads(facade.pdf_note_cards(json.dumps([note_id, note_id, 999999999]), "page"))
        assert page == {"query": f"nid:{note_id}", "message": ""}
        assert json.loads(facade.pdf_note_cards("[]", "page")) == {"query": "", "message": "No cards created on this page yet."}
        assert facade.note_first_card(note_id) == rt.col.get_note(note_id).card_ids()[0]
        assert facade.note_first_card(999999999) == 0
    finally:
        rt.col.remove_notes([note_id])


def test_cards_from_many_highlights_list_only_those_without_one_and_make_one_each(rt, pdf):
    _highlight(pdf, "h1", 1, "First idea")
    _highlight(pdf, "h2", 2, "Second idea")
    _highlight(pdf, "blank", 2, "  ")
    linked = _make_card(rt, pdf, "h1")
    made: list[int] = [linked]
    try:
        plan = json.loads(facade.pdf_highlight_cards_plan(pdf))
        assert [(row["id"], row["page"], row["text"]) for row in plan["rows"]] == [("h2", 2, "Second idea")]
        assert plan["note_type"] in plan["note_types"]

        summary = json.loads(facade.pdf_highlight_cards_create(pdf, json.dumps(["h2"]), plan["note_type"], "item"))
        assert summary["created"] == 1 and summary["failed"] == 0
        assert summary["message"]
        assert "incrementoReceivePdfHighlights" in summary["script"]
        created = json.loads(facade.pdf_highlight_card(pdf, "h2"))["card_id"]
        made.append(rt.col.get_card(created).nid)
        assert rt.col.get_card(created).note().fields[0].startswith("Second idea")
        # Nothing left without a card.
        assert json.loads(facade.pdf_highlight_cards_plan(pdf))["rows"] == []
    finally:
        rt.col.remove_notes(made)


def test_with_every_highlight_carded_the_plan_says_so(rt, pdf):
    plan = json.loads(facade.pdf_highlight_cards_plan(pdf))
    assert plan["rows"] == [] and plan["message"] == "No unlinked text highlights remain for this PDF."


def test_add_card_starts_an_empty_card_from_the_page_being_read(rt, pdf):
    from incremento.backend.pdf_manager import set_page

    set_page(str(rt.addon_dir), rt.profile, pdf, 3)
    facade.pdf_new_card(pdf)
    draft = json.loads(facade.extract_draft(pdf))
    assert draft["fields"] == ["", ""] and draft["page"] == 3
    # A draft already started is kept, not replaced.
    _highlight(pdf, "h1", 1, "Kept words")
    facade.pdf_highlight_card(pdf, "h1")
    facade.pdf_new_card(pdf)
    assert json.loads(facade.extract_draft(pdf))["fields"][0].startswith("Kept words")
