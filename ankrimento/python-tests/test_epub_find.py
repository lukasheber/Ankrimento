"""Find in an EPUB: upstream's per-section text index, and the page script finding the words."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_epub import _write_epub


@pytest.fixture(scope="module")
def book(rt, tmp_path_factory):
    path = tmp_path_factory.mktemp("epub-find") / "Findable.epub"
    _write_epub(path)
    return facade.import_epub(str(path), "Findable", priority=50.0)


def test_matching_sections_are_found_with_their_titles(rt, book):
    hits = json.loads(facade.epub_search(book, "incremental"))

    assert [(hit["section"], hit["title"]) for hit in hits] == [(1, "Chapter Two")]
    assert "Incremental reading" in hits[0]["snippet"]


def test_the_page_script_finds_the_words_when_asked(rt, book):
    opened = json.loads(facade.epub_open(book, 1))

    script = facade.epub_script(book, 1, opened["nonce"], 0.0, "incremental")

    # Desktop's page state: the script selects the first match when the chapter loads.
    assert '"searchQuery": "incremental"' in script or '"searchQuery":"incremental"' in script
