"""Importing a PDF and reading it through the viewer's own messages."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ankrimento import facade

# A one-page PDF, small enough to keep inline.
_MINIMAL_PDF = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
    b"trailer<</Root 1 0 R>>\n%%EOF\n"
)


@pytest.fixture(scope="module")
def pdf_card(rt, tmp_path_factory):
    source = tmp_path_factory.mktemp("pdf") / "Reading Notes.pdf"
    source.write_bytes(_MINIMAL_PDF)
    card_id = facade.import_pdf(str(source), "Reading Notes", priority=20.0)
    return card_id


def test_import_creates_a_pdf_topic_with_the_desktop_layout(rt, pdf_card):
    card = rt.col.get_card(pdf_card)
    note = card.note()
    assert note.note_type()["name"] == "Incremento PDF"
    assert rt.col.decks.name(card.did) == "Topics"
    path = Path(facade.pdf_path(pdf_card))
    assert path.parent == rt.addon_dir / "user_files" / rt.profile / "pdfs"
    assert path.read_bytes() == _MINIMAL_PDF
    assert facade.get_priority(pdf_card) == 20.0
    assert facade.card_reader(pdf_card) == "pdf"
    assert facade.card_title(pdf_card) == "Reading Notes"
    assert json.loads(facade.answer_buttons(pdf_card))["mode"] == "topic"


def test_start_script_opens_the_viewer_at_the_saved_page(rt, pdf_card):
    script = facade.pdf_start_script(pdf_card, "https://host/pdf", "https://host/worker.js")
    assert "window._pdfFileUrl = \"https://host/pdf\"" in script
    assert f"window.incrementoPdfStart({pdf_card}, " in script


def test_navigation_zoom_scroll_and_read_marker_are_saved(rt, pdf_card):
    from incremento.backend import pdf_manager

    addon_dir, profile = str(rt.addon_dir), rt.profile
    facade.pdf_message(pdf_card, f"incremento_pdf_nav:{pdf_card}:3")
    facade.pdf_message(pdf_card, f"incremento_pdf_zoom:{pdf_card}:1.5")
    facade.pdf_message(pdf_card, "incremento_pdf_scroll:" + json.dumps({"cardId": pdf_card, "scrollRatio": 0.25}))
    facade.pdf_message(pdf_card, "incremento_pdf_mark_read:" + json.dumps({"cardId": pdf_card, "readPage": 2}))
    assert pdf_manager.get_page(addon_dir, profile, pdf_card) == 3
    assert pdf_manager.get_zoom(addon_dir, profile, pdf_card) == 1.5
    assert pdf_manager.get_scroll_ratio(addon_dir, profile, pdf_card) == 0.25
    assert pdf_manager.get_read_page(addon_dir, profile, pdf_card) == 2

    script = facade.pdf_start_script(pdf_card, "u", "w")
    assert f"window.incrementoPdfStart({pdf_card}, \"" in script
    assert ", 3, 1.5, 0.25, 2, " in script


def test_highlights_round_trip(rt, pdf_card):
    from incremento.backend.pdf_highlights import load_highlights

    highlight = {"id": "h1", "page": 1, "color": "yellow", "text": "key idea",
                 "rects": [{"x": 0.1, "y": 0.1, "w": 0.2, "h": 0.05}]}
    facade.pdf_message(pdf_card, "incremento_pdf_hl_add:" + json.dumps({"cardId": pdf_card, "highlight": highlight}))
    assert [h["text"] for h in load_highlights(str(rt.addon_dir), rt.profile, pdf_card)] == ["key idea"]
    assert "key idea" in facade.pdf_start_script(pdf_card, "u", "w")

    facade.pdf_message(pdf_card, "incremento_pdf_hl_del:" + json.dumps({"cardId": pdf_card, "id": "h1"}))
    assert load_highlights(str(rt.addon_dir), rt.profile, pdf_card) == []


def test_bookmarks_are_pushed_back_to_the_viewer(rt, pdf_card):
    js = facade.pdf_message(pdf_card, "incremento_pdf_bookmark_add:" + json.dumps({"cardId": pdf_card, "page": 1}))
    assert js.startswith("window.incrementoReceivePdfBookmarks")
    bookmarks = json.loads(js[js.index("(", js.index("&&")) + 1 : js.rindex(")")])
    assert [b["location"]["page"] for b in bookmarks] == [1]


def test_finished_suspends_the_document(rt, pdf_card):
    facade.pdf_message(pdf_card, f"incremento_pdf_finished:{pdf_card}")
    assert rt.col.get_card(pdf_card).queue == -1


@pytest.fixture(scope="module")
def extract_source(rt, tmp_path_factory):
    source = tmp_path_factory.mktemp("pdf-extract") / "Extract Source.pdf"
    source.write_bytes(_MINIMAL_PDF)
    return facade.import_pdf(str(source), "Extract Source", priority=50.0)


def test_extract_creates_a_linked_card(rt, extract_source):
    from incremento.backend.priority_manager import get_priority

    card_id = extract_source
    facade.pdf_message(card_id, f"incremento_pdf_nav:{card_id}:1")
    highlight = {"id": "hx", "page": 1, "color": "yellow", "text": "Spaced repetition",
                 "rects": [{"x": 0.1, "y": 0.2, "w": 0.3, "h": 0.05}]}
    facade.pdf_message(card_id, "incremento_pdf_hl_add:" + json.dumps({"cardId": card_id, "highlight": highlight}))
    facade.pdf_message(card_id, "incremento_fill_field:" + json.dumps(
        {"idx": 0, "text": "Spaced repetition", "runs": [{"text": "Spaced ", "bold": False, "italic": False},
                                                         {"text": "repetition", "bold": True, "italic": False}]}))
    facade.pdf_message(card_id, "incremento_fill_field:" + json.dumps({"idx": 1, "text": "<b>not html</b>"}))

    draft = json.loads(facade.extract_draft(card_id))
    # Each filled field ends with desktop's link back to the page (pdf_citation), as desktop's fill adds it.
    front, back = draft["fields"]
    assert front.startswith("Spaced <strong>repetition</strong><br><a ")
    assert back.startswith("&lt;b&gt;not html&lt;/b&gt;<br><a ")
    from incremento.frontend.reader_links import reader_anchor_command_from_html

    command = reader_anchor_command_from_html(front[front.index("<a "):])
    assert command.startswith("incremento_open_pdf_ref:")
    link = json.loads(command[len("incremento_open_pdf_ref:"):])
    assert (link["card_id"], link["page"], link["highlight_id"], link["excerpt"]) == (card_id, 1, "hx", "Spaced repetition")
    assert draft["note_type"] == "Basic"
    assert draft["kind"] == "topic"  # the phone's default for a new extract (upstream's is an item)
    assert draft["priority"] == pytest.approx(49.0)  # source 50 x desktop's default 0.98

    created = json.loads(facade.create_extract(card_id, json.dumps(draft["fields"]), "Basic", "item", 49.0))
    note = rt.col.get_note(created["note_id"])
    assert note.fields == draft["fields"]
    new_card = note.cards()[0]
    assert new_card.did == rt.col.get_card(card_id).did
    assert get_priority(str(rt.addon_dir), rt.profile, new_card.id) == pytest.approx(49.0)

    from incremento.backend.db import get_connection

    conn = get_connection(str(rt.addon_dir), rt.profile)
    assert conn.execute(
        "SELECT page, highlight_id FROM pdf_card_sources WHERE pdf_card_id = ? AND note_id = ?",
        (card_id, note.id),
    ).fetchone() == (1, "hx")
    assert conn.execute(
        "SELECT parent_card_id FROM knowledge_tree_nodes WHERE card_id = ?", (new_card.id,)
    ).fetchone() == (card_id,)
    assert str(note.id) in created["script"]
    assert json.loads(facade.extract_draft(card_id))["fields"] == ["", ""]  # draft consumed


def test_extract_links_follow_desktops_setting(rt, extract_source):
    import copy

    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    mw.addonManager.writeConfig("incremento", {**copy.deepcopy(saved), "extract_source_links": {"pdf": False}})
    try:
        facade.pdf_message(extract_source, "incremento_fill_field:" + json.dumps({"idx": 0, "text": "No link"}))
        assert json.loads(facade.extract_draft(extract_source))["fields"][0] == "No link"
    finally:
        mw.addonManager.writeConfig("incremento", saved)
        facade.discard_extract(extract_source)


def test_the_draft_says_whether_desktops_extract_note_type_is_set(rt, extract_source):
    """Without desktop's setting, desktop uses the Add Card window's last type: the phone remembers it per kind of document."""
    import copy

    from aqt import mw

    assert json.loads(facade.extract_draft(extract_source))["note_type_configured"] is False
    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    mw.addonManager.writeConfig("incremento", {**copy.deepcopy(saved), "extract_notetype": "Basic"})
    try:
        draft = json.loads(facade.extract_draft(extract_source))
        assert (draft["note_type"], draft["note_type_configured"]) == ("Basic", True)
    finally:
        mw.addonManager.writeConfig("incremento", saved)
