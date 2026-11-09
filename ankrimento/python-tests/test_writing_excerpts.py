"""Send to Writing: a passage from a PDF or EPUB, quoted into a writing topic with a link back to its source."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_epub import _write_epub
from test_pdf import _MINIMAL_PDF


@pytest.fixture(scope="module")
def pdf_card(rt, tmp_path_factory):
    source = tmp_path_factory.mktemp("excerpt-pdf") / "Source Paper.pdf"
    source.write_bytes(_MINIMAL_PDF)
    return facade.import_pdf(str(source), "Source Paper", priority=50.0)


@pytest.fixture(scope="module")
def epub_card(rt, tmp_path_factory):
    path = tmp_path_factory.mktemp("excerpt-epub") / "Source Book.epub"
    _write_epub(path)
    return facade.import_epub(str(path), "Source Book", priority=50.0)


def _send(source: dict, writing: int) -> dict:
    return json.loads(facade.send_to_writing(json.dumps(source), writing))


def _text(writing: int) -> str:
    return json.loads(facade.writing_open(writing))["text"]


def test_a_pdf_passage_is_quoted_with_desktops_markers_and_a_link_back(rt, pdf_card):
    from incremento.backend.writing_links import marker

    writing = facade.create_writing("Excerpt target")

    sent = _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "Spacing beats cramming."}, writing)

    text = _text(writing)
    anchor = sent["anchor"]
    assert f"{marker(anchor, start=True)}\nSpacing beats cramming.\n{marker(anchor)}" in text
    links = json.loads(facade.writing_excerpts(writing))
    assert [link["anchor"] for link in links] == [anchor]
    assert links[0]["source_title"] == "Source Paper"
    assert links[0]["kind"] == "pdf" and links[0]["page"] == 1
    assert links[0]["quote"] == "Spacing beats cramming."


def test_the_quote_is_added_after_what_is_already_written(rt, pdf_card):
    writing = facade.create_writing("Append target")
    facade.writing_save(writing, "My own first line")

    _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "Quoted."}, writing)

    assert _text(writing).startswith("My own first line\n\n")


def test_an_epub_passage_keeps_its_chapter_and_place(rt, epub_card):
    writing = facade.create_writing("Epub target")

    _send({"kind": "epub", "card_id": epub_card, "section": 1, "offset": 4, "quote": "Incremental reading scales."}, writing)

    link = json.loads(facade.writing_excerpts(writing))[0]
    assert (link["kind"], link["section"], link["offset"]) == ("epub", 1, 4)


def test_a_link_is_active_only_while_its_markers_are_in_the_text(rt, pdf_card):
    writing = facade.create_writing("Marker target")
    anchor = _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "Keep or drop."}, writing)["anchor"]
    assert [link["anchor"] for link in json.loads(facade.writing_excerpts(writing))] == [anchor]

    facade.writing_save(writing, "I deleted the quote")

    assert json.loads(facade.writing_excerpts(writing)) == []


def test_the_excerpt_markers_are_not_counted_as_words_after_sending(rt, pdf_card):
    from ankrimento import writing as phone_writing

    target = facade.create_writing("Count target")
    before = phone_writing.count_words(_text(target), "simple")

    _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "Three quoted words"}, target)

    assert phone_writing.count_words(_text(target), "simple") == before + 3


def test_the_passages_sent_from_a_page_are_listed_at_their_source(rt, pdf_card):
    writing = facade.create_writing("Source side target")
    _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "Seen from the source."}, writing)

    here = json.loads(facade.source_excerpts(pdf_card, "pdf", 1))

    assert any(item["writing_card_id"] == writing and item["writing_title"] == "Source side target" for item in here)
    assert json.loads(facade.source_excerpts(pdf_card, "pdf", 2)) == []


def test_a_quote_is_required_and_the_target_must_be_a_writing_topic(rt, pdf_card):
    writing = facade.create_writing("Needs a quote")

    with pytest.raises(ValueError):
        _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "   "}, writing)
    with pytest.raises(ValueError):
        _send({"kind": "pdf", "card_id": pdf_card, "page": 1, "quote": "Into a PDF?"}, pdf_card)
