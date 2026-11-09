"""Save a web page for offline reading: its website appearance, or an editable reading copy."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade

_PAGE = """<!doctype html><html><head><title>Spacing and memory</title>
<style>h1 { color: #336699 } .note { border: 1px solid #ccc }</style></head>
<body><main><h1>Spacing and memory</h1>
<p class="note">Reviews spread over time are remembered longer than reviews packed together.</p>
<p>A second paragraph, so the reading copy has something to keep.</p></main></body></html>"""


def _import(fmt: str) -> dict:
    return json.loads(facade.import_web_snapshot("https://example.org/spacing", fmt, "Topics", 50.0, _PAGE, ""))


def test_a_website_snapshot_is_an_epub_document_marked_html(rt):
    saved = _import("html")

    note = rt.col.get_card(saved["card_id"]).note()
    assert facade.card_reader(saved["card_id"]) == "epub"
    assert note["Incremento_Source_Type"] == "HTML"
    assert saved["title"] == "Spacing and memory"
    assert saved["format"] == "html"


def test_a_reading_copy_is_a_markdown_document(rt):
    saved = _import("markdown")

    note = rt.col.get_card(saved["card_id"]).note()
    assert note["Incremento_Source_Type"] == "Markdown"
    assert saved["format"] == "markdown"


def test_the_snapshot_keeps_the_pages_layout_when_it_is_read(rt):
    saved = _import("html")

    script = facade.epub_script(saved["card_id"], 0, "abc123")

    assert '"preserveLayout": true' in script or "preserveLayout: true" in script or "preserveLayout:true" in script


def test_a_reading_copy_is_reflowed_like_any_book(rt):
    saved = _import("markdown")

    script = facade.epub_script(saved["card_id"], 0, "abc123")

    assert "preserveLayout\": false" in script or "preserveLayout: false" in script or "preserveLayout:false" in script


def test_the_snapshot_records_where_and_when_it_was_saved(rt):
    saved = _import("html")

    note = rt.col.get_card(saved["card_id"]).note()
    assert "https://example.org/spacing" in str(note["Incremento_Source_Link"])


def test_an_unknown_format_is_refused(rt):
    with pytest.raises(ValueError):
        facade.import_web_snapshot("https://example.org/x", "pdf", "Topics", 50.0, _PAGE, "")
