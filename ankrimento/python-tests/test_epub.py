"""Importing an EPUB and reading it through upstream's page script messages."""

from __future__ import annotations

import json
import zipfile

import pytest

from ankrimento import facade

_CHAPTER = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head>
<body><h1>{title}</h1><p>{text}</p></body></html>"""


def _write_epub(path) -> None:
    opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test-book</dc:identifier>
<dc:title>Test Book</dc:title><dc:language>en</dc:language></metadata>
<manifest>
<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    with zipfile.ZipFile(path, "w") as epub:
        epub.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        epub.writestr("META-INF/container.xml", container)
        epub.writestr("OEBPS/content.opf", opf)
        epub.writestr("OEBPS/c1.xhtml", _CHAPTER.format(title="Chapter One", text="Spaced repetition works."))
        epub.writestr("OEBPS/c2.xhtml", _CHAPTER.format(title="Chapter Two", text="Incremental reading scales."))


@pytest.fixture(scope="module")
def epub_card(rt, tmp_path_factory):
    path = tmp_path_factory.mktemp("epub") / "Test Book.epub"
    _write_epub(path)
    return facade.import_epub(str(path), "Test Book", priority=30.0)


def test_import_and_open_the_saved_chapter(rt, epub_card):
    assert facade.card_reader(epub_card) == "epub"
    opened = json.loads(facade.epub_open(epub_card))
    assert opened["section_count"] == 2
    assert opened["section_index"] == 0
    assert opened["path"].startswith(opened["root"])
    assert "Chapter One" in open(opened["path"], encoding="utf8").read()


def test_upstream_page_script_is_built_with_the_nonce(rt, epub_card):
    script = facade.epub_script(epub_card, 0, "abc123")
    assert '"__incremento_epub__:" + "abc123" + \':\'' in script
    assert "incrementoFillEpubField" in script


def test_progress_read_marker_and_chapter_navigation(rt, epub_card):
    from incremento.backend.epub_manager import get_epub_progress, get_read_section_index

    addon_dir, profile = str(rt.addon_dir), rt.profile
    facade.epub_message(epub_card, 1, "incremento_epub_progress:" + json.dumps(
        {"cardId": epub_card, "sectionIndex": 1, "scrollRatio": 0.5}))
    assert get_epub_progress(addon_dir, profile, epub_card)[:2] == (1, 0.5)
    facade.epub_message(epub_card, 1, "incremento_epub_mark_read:" + json.dumps(
        {"cardId": epub_card, "sectionIndex": 1, "anchor": None}))
    assert get_read_section_index(addon_dir, profile, epub_card) == 1
    assert json.loads(facade.epub_open(epub_card))["section_index"] == 1  # reopens at the saved chapter

    nav = json.loads(facade.epub_message(epub_card, 1, "incremento_epub_section_nav:" + json.dumps({"delta": -1})))
    assert nav == {"navigate": {"section_index": 0, "scroll_ratio": 1.0}}


def test_extract_from_an_epub_records_the_chapter(rt, epub_card):
    facade.epub_message(epub_card, 1, "incremento_epub_fill_field:" + json.dumps(
        {"cardId": epub_card, "idx": 0, "text": "Incremental reading scales.", "startOffset": 7}))
    draft = json.loads(facade.extract_draft(epub_card))
    # Desktop's link back to the chapter and the selection's place (epub_citation), named by the chapter.
    assert draft["fields"][0].startswith("Incremental reading scales.<br><a ")
    assert f"incremento_open_epub:{epub_card}:1:7:" in draft["fields"][0]
    assert ">Chapter Two</a>" in draft["fields"][0]
    created = json.loads(facade.create_extract(epub_card, json.dumps(draft["fields"]), "Basic", "item", 29.4))

    from incremento.backend.db import get_connection

    row = get_connection(str(rt.addon_dir), rt.profile).execute(
        "SELECT section_index FROM epub_card_sources WHERE epub_card_id = ? AND note_id = ?",
        (epub_card, created["note_id"]),
    ).fetchone()
    assert row == (1,)


def test_reading_settings_are_upstreams_per_book_values(rt, epub_card):
    reading = json.loads(facade.epub_reading(epub_card))
    assert reading == {"text_scale": 1.0, "appearance": "original", "contents": ["Chapter One", "Chapter Two"]}

    assert facade.epub_set_text_scale(epub_card, 1.35) == pytest.approx(1.35)
    assert facade.epub_set_text_scale(epub_card, 9.0) == pytest.approx(2.2)  # upstream's limit
    facade.epub_set_appearance(epub_card, "night")
    reading = json.loads(facade.epub_reading(epub_card))
    assert reading["text_scale"] == pytest.approx(2.2)
    assert reading["appearance"] == "night"
    # Desktop builds the page script from the same values.
    assert '"textScale": 2.2' in facade.epub_script(epub_card, 0, "n")
    with pytest.raises(ValueError):
        facade.epub_set_appearance(epub_card, "sepia")
    facade.epub_set_text_scale(epub_card, 1.0)
    facade.epub_set_appearance(epub_card, "original")
