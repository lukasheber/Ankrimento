"""Links inside an EPUB: resolved by upstream's resolver; footnotes read for a note sheet."""

from __future__ import annotations

import json
import zipfile

import pytest

from ankrimento import facade

_PAGE = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head><body>{body}</body></html>"""

_TEXT = """<h1 id="top">Chapter One</h1>
<p>Spaced repetition<a epub:type="noteref" id="r1" href="notes.xhtml#n1">1</a> works.<a id="r2" href="notes.xhtml#n2"><sup>2</sup></a>
<a id="r3" href="notes.xhtml#n3">3</a></p>
<p><a href="c2.xhtml">On to chapter two</a>, <a href="#later">see below</a>, <a href="https://example.org/memory">a site</a>,
<a href="javascript:alert(1)">a script</a>.</p>
<p id="later">Later in the chapter.</p>"""

_NOTES = """<h1 id="notes-title">Notes</h1>
<aside epub:type="footnote" id="n1"><p><a href="c1.xhtml#r1">1</a> The first   note.</p></aside>
<p class="nota" id="n2"><a href="c1.xhtml#r2">2</a> The second note.</p>
<p class="nota"><a id="n3" href="c1.xhtml#r3">3</a> The third note.</p>"""


def _write_epub(path) -> None:
    opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">linked-book</dc:identifier>
<dc:title>Linked Book</dc:title><dc:language>en</dc:language></metadata>
<manifest>
<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
<item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="notes"/></spine></package>"""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    with zipfile.ZipFile(path, "w") as epub:
        epub.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        epub.writestr("META-INF/container.xml", container)
        epub.writestr("OEBPS/content.opf", opf)
        epub.writestr("OEBPS/c1.xhtml", _PAGE.format(title="Chapter One", body=_TEXT))
        epub.writestr("OEBPS/c2.xhtml", _PAGE.format(title="Chapter Two", body="<h1>Chapter Two</h1><p>Incremental reading.</p>"))
        epub.writestr("OEBPS/notes.xhtml", _PAGE.format(title="Notes", body=_NOTES))


@pytest.fixture(scope="module")
def book(rt, tmp_path_factory):
    path = tmp_path_factory.mktemp("epub-links") / "Linked Book.epub"
    _write_epub(path)
    return facade.import_epub(str(path), "Linked Book", priority=40.0)


def _tap(card_id: int, href: str, ratio: float = 0.25) -> dict:
    """The page script's message for a tapped link, as the device hands it on."""
    message = "incremento_epub_open_link:" + json.dumps({"cardId": card_id, "href": href, "sourceScrollRatio": ratio})
    return json.loads(facade.epub_message(card_id, 0, message))["link"]


def test_a_link_to_another_chapter_opens_it_and_remembers_where_it_was_tapped(rt, book):
    assert _tap(book, "c2.xhtml", 0.25) == {
        "kind": "internal", "section_index": 1, "fragment": "", "note": "", "from_ratio": 0.25,
    }


def test_a_footnote_brings_its_text(rt, book):
    link = _tap(book, "notes.xhtml#n1")
    assert (link["section_index"], link["fragment"], link["note"]) == (2, "n1", "1 The first note.")


def test_a_footnote_whose_id_is_on_its_paragraph_or_its_number(rt, book):
    assert _tap(book, "notes.xhtml#n2")["note"] == "2 The second note."
    assert _tap(book, "notes.xhtml#n3")["note"] == "3 The third note."


def test_a_link_to_a_heading_or_a_place_in_the_chapter_is_followed_not_shown_as_a_note(rt, book):
    assert _tap(book, "notes.xhtml#notes-title")["note"] == ""
    later = _tap(book, "#later")
    assert (later["section_index"], later["fragment"]) == (0, "later")
    # A short passage shows over the page like a note (endnotes often sit in the same chapter file).
    assert later["note"] == "Later in the chapter."


def test_web_links_open_outside_and_others_are_refused(rt, book):
    assert _tap(book, "https://example.org/memory") == {"kind": "external", "url": "https://example.org/memory"}
    assert _tap(book, "javascript:alert(1)")["kind"] == "blocked"


def test_links_are_tappable_and_a_followed_link_opens_at_its_place(rt, book):
    script = facade.epub_script(book, 2, "abc123", 0.0, "", "n2")
    assert '"clickableLinks": true' in script
    assert '"linkFragment": "n2"' in script
