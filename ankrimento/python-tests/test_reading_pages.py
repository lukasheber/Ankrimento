"""Pages read count towards today's statistics, as desktop's readers record them."""

from __future__ import annotations

import json

from ankrimento import facade, pdf_reader
from test_epub import _write_epub
from test_pdf import _MINIMAL_PDF


def _pages_today() -> int:
    return json.loads(facade.statistics(1))["history"][-1]["pages"]


def test_a_pdf_page_turn_counts_once_today(rt, tmp_path):
    source = tmp_path / "Counted.pdf"
    source.write_bytes(_MINIMAL_PDF)
    card_id = facade.import_pdf(str(source), "Counted", priority=50.0)
    before = _pages_today()

    pdf_reader.handle(card_id, f"incremento_pdf_nav:{card_id}:1")
    pdf_reader.handle(card_id, f"incremento_pdf_nav:{card_id}:1")

    # Desktop's nav branch records each page (record_pdf_page_read), once a day.
    assert _pages_today() == before + 1


def test_an_epub_page_reported_counts_once_today(rt, tmp_path):
    path = tmp_path / "Counted.epub"
    _write_epub(path)
    card_id = facade.import_epub(str(path), "Counted", priority=50.0)
    before = _pages_today()
    progress = "incremento_epub_progress:" + json.dumps({"cardId": card_id, "sectionIndex": 0, "scrollRatio": 0.2, "pageIndex": 3})

    facade.epub_message(card_id, 0, progress)
    facade.epub_message(card_id, 0, progress)

    # Desktop's progress branch records the page (record_epub_page_read), once a day.
    assert _pages_today() == before + 1
