"""Find in a PDF: page text extracted on the device, indexed and searched by upstream."""

from __future__ import annotations

import json

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


def _import(tmp_path, title: str) -> int:
    source = tmp_path / f"{title}.pdf"
    source.write_bytes(_MINIMAL_PDF)
    return facade.import_pdf(str(source), title, priority=50.0)


def test_the_devices_page_text_makes_the_pdf_searchable_by_page(rt, tmp_path):
    card_id = _import(tmp_path, "Searchable")
    # Without PyMuPDF, upstream's import finds no text and records the PDF as "empty".
    assert facade.pdf_index_state(card_id) == "empty"

    facade.pdf_index_pages(card_id, json.dumps(["Alpha beta", "", "Gamma rays and more gamma"]))

    assert facade.pdf_index_state(card_id) == "current"
    hits = json.loads(facade.pdf_search(card_id, "gamma"))
    assert [hit["page"] for hit in hits] == [3]
    assert "Gamma rays" in hits[0]["snippet"]


def test_upstreams_search_all_finds_the_devices_text(rt, tmp_path):
    from incremento.backend.db import search_pdf_text_index

    card_id = _import(tmp_path, "Everywhere")
    facade.pdf_index_pages(card_id, json.dumps(["Unusual word zygomorphic here"]))

    found = search_pdf_text_index(str(rt.addon_dir), rt.profile, "zygomorphic")

    assert [(cid, page) for cid, page, _ in found] == [(card_id, 1)]


def test_a_pdf_without_text_stays_empty(rt, tmp_path):
    card_id = _import(tmp_path, "Scanned")

    facade.pdf_index_pages(card_id, json.dumps(["", "  "]))

    # Upstream's "empty" state: the host remembers it read this one itself.
    assert facade.pdf_index_state(card_id) == "empty"
    assert json.loads(facade.pdf_search(card_id, "anything")) == []


def test_a_changed_file_needs_its_text_again(rt, tmp_path):
    import os
    from ankrimento import pdf_reader

    card_id = _import(tmp_path, "Edited")
    facade.pdf_index_pages(card_id, json.dumps(["First version"]))
    path = pdf_reader.pdf_path(card_id)
    stat = os.stat(path)
    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns + 5_000_000_000))

    assert facade.pdf_index_state(card_id) == "stale"
