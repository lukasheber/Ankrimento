"""Search ALL: upstream's search across documents, highlights and cards, as results to show natively."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture(autouse=True)
def _keep_config(rt):
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    yield
    mw.addonManager.writeConfig("incremento", saved)


def _search(query: str) -> dict:
    return json.loads(facade.search_all(query))


def _items(results: dict, kind: str) -> list[dict]:
    return [item for section in results["sections"] for group in section["groups"] for item in group["items"] if item["kind"] == kind]


def test_filters_start_as_desktops_defaults(rt):
    filters = json.loads(facade.search_all_filters())

    assert filters["cards"] is True
    assert filters["pdf_highlights"] is True
    # Desktop leaves PDF file text off by default.
    assert filters["pdf_content"] is False


def test_document_text_and_cards_are_found_with_where_to_open_them(rt, tmp_path):
    source = tmp_path / "Botany.pdf"
    source.write_bytes(_MINIMAL_PDF)
    pdf = facade.import_pdf(str(source), "Botany", priority=50.0)
    facade.pdf_index_pages(pdf, json.dumps(["Flowers can be quincuncial in their petals."]))
    note = rt.col.new_note(rt.col.models.by_name("Basic"))
    note["Front"] = "What does quincuncial mean?"
    note["Back"] = "Arranged like the five on a die."
    rt.col.add_note(note, rt.col.decks.id("Default"))
    facade.set_search_all_filter("pdf_content", True)

    results = _search("quincuncial")

    pages = _items(results, "pdf")
    assert [(item["card_id"], item["position"]) for item in pages] == [(pdf, 1)]
    assert "quincuncial" in pages[0]["snippet"]
    group_titles = [group["title"] for section in results["sections"] for group in section["groups"]]
    assert "Botany" in group_titles
    cards = _items(results, "card")
    assert [item["card_id"] for item in cards] == [note.cards()[0].id]
    assert "quincuncial" in cards[0]["snippet"]
    rt.col.remove_notes([note.id])


def test_too_short_or_unmatched_queries_have_no_results(rt):
    assert _search("ab")["sections"] == []
    assert _search("nothingmatchesthisatall")["sections"] == []
