"""Reading coverage: what was viewed against what was marked read, recorded as desktop records it."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_epub import _write_epub


@pytest.fixture(scope="module")
def pdf_card(rt, tmp_path_factory):
    from pypdf import PdfWriter

    source = tmp_path_factory.mktemp("coverage-pdf") / "Coverage Notes.pdf"
    writer = PdfWriter()
    for _ in range(4):
        writer.add_blank_page(200, 200)
    with open(source, "wb") as handle:
        writer.write(handle)
    return facade.import_pdf(str(source), "Coverage Notes", priority=20.0)


@pytest.fixture(scope="module")
def epub_card(rt, tmp_path_factory):
    path = tmp_path_factory.mktemp("coverage-epub") / "Coverage Book.epub"
    _write_epub(path)
    return facade.import_epub(str(path), "Coverage Book", priority=30.0)


def _view_pdf(card_id: int, page: int, total: int = 4) -> str:
    return facade.pdf_message(card_id, "incremento_reader_coverage:" + json.dumps(
        {"cardId": card_id, "position": 0, "start": page - 1, "end": page, "totalPages": total}))


def _summary(card_id: int) -> dict:
    return json.loads(facade.coverage_summary(card_id))


def test_a_viewed_pdf_page_is_recorded_and_the_viewer_gets_the_figures(rt, pdf_card):
    js = _view_pdf(pdf_card, 2)

    assert "incrementoReceiveCoverage" in js
    figures = _summary(pdf_card)
    assert figures["viewed_percent"] == 25.0  # one of four pages
    assert figures["marked_percent"] == 0.0


def test_viewing_the_same_page_again_counts_once(rt, pdf_card):
    _view_pdf(pdf_card, 3)
    _view_pdf(pdf_card, 3)

    assert _summary(pdf_card)["viewed_percent"] == 50.0  # pages 2 and 3


def test_a_message_for_another_document_is_ignored(rt, pdf_card):
    before = _summary(pdf_card)

    facade.pdf_message(pdf_card, "incremento_reader_coverage:" + json.dumps(
        {"cardId": pdf_card + 1, "position": 0, "start": 0, "end": 1, "totalPages": 4}))

    assert _summary(pdf_card) == before


def test_marking_a_pdf_page_read_is_separate_from_viewing_it(rt, pdf_card):
    figures = json.loads(facade.coverage_mark(pdf_card, "mark", 0, 3, 4))  # page 4, never viewed

    assert figures["marked_percent"] == 25.0
    assert figures["viewed_percent"] >= 75.0  # marking counts as viewing
    assert figures["nonlinear_read"] is True


def test_unmarking_removes_only_the_reading_mark(rt, pdf_card):
    figures = json.loads(facade.coverage_mark(pdf_card, "unmark", 0, 3, 4))

    assert figures["marked_percent"] == 0.0
    assert figures["viewed_percent"] >= 75.0


def test_the_label_is_desktops_wording(rt, pdf_card):
    from incremento.backend.i18n import t

    figures = _summary(pdf_card)

    viewed = f"{figures['viewed_percent']:.1f}".rstrip("0").rstrip(".")
    assert figures["label"] == t("reader_coverage_summary", viewed=viewed, marked="0")


def test_the_epub_page_script_reports_coverage_now(rt, epub_card):
    script = facade.epub_script(epub_card, 0, "abc123")

    assert "incremento_reader_coverage:" in script
    assert "trackCoverage: true" in script or '"trackCoverage": true' in script or "trackCoverage:true" in script


def test_a_viewed_epub_passage_is_recorded_for_its_chapter(rt, epub_card):
    reply = json.loads(facade.epub_message(epub_card, 0, "incremento_reader_coverage:" + json.dumps(
        {"cardId": epub_card, "position": 0, "start": 0, "end": 10})))

    assert reply["coverage"]["viewed_percent"] > 0
    assert _summary(epub_card)["viewed_percent"] == reply["coverage"]["viewed_percent"]


def test_an_epub_report_for_a_chapter_that_is_not_open_is_ignored(rt, epub_card):
    before = _summary(epub_card)

    reply = json.loads(facade.epub_message(epub_card, 0, "incremento_reader_coverage:" + json.dumps(
        {"cardId": epub_card, "position": 1, "start": 0, "end": 10})))

    assert reply == {}
    assert _summary(epub_card) == before


def test_marking_a_whole_chapter_read(rt, epub_card):
    figures = json.loads(facade.coverage_mark(epub_card, "mark", 1, 0, 0))  # end 0: the whole chapter

    assert figures["marked_percent"] > 0
    assert figures["nonlinear_read"] is True


def test_a_document_that_is_neither_pdf_nor_epub_has_no_figures(rt):
    video = json.loads(facade.import_link("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "No coverage"))["card_id"]

    assert _summary(video) == {}
