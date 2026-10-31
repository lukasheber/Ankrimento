"""A web page imported as a clean document (upstream's Markdown document path), and titles for links."""

from __future__ import annotations

import json

from ankrimento import facade, links

_ARTICLE = {
    "title": "Incremental reading",
    "url": "https://example.org/ir",
    "markdown": "Incremental reading is a learning method.\n\n## History\n\nIt was introduced in SuperMemo.",
    "markdown_document": (
        "# Incremental reading\n\nSource: https://example.org/ir\n\n"
        "Incremental reading is a learning method.\n\n## History\n\nIt was introduced in SuperMemo.\n"
    ),
}


def test_a_web_page_becomes_a_reflowable_document_read_like_an_epub(rt, monkeypatch):
    import incremento.backend.webpage_markdown as webpage_markdown

    monkeypatch.setattr(webpage_markdown, "fetch_webpage_markdown", lambda url, **_: dict(_ARTICLE))
    added = json.loads(facade.import_web_document("https://example.org/ir"))

    assert added["title"] == "Incremental reading"
    assert facade.card_reader(added["card_id"]) == "epub"
    assert facade.card_title(added["card_id"]) == "Incremental reading"
    reading = json.loads(facade.epub_reading(added["card_id"]))
    assert reading["contents"]  # sections from the article's headings


def test_links_get_their_page_or_video_title(monkeypatch):
    monkeypatch.setattr(links, "_fetch", lambda url: '{"title": "Me at the zoo"}' if "oembed" in url else "<title>An essay</title>")
    assert links.title("https://www.youtube.com/watch?v=jNQXAC9IVRw") == "Me at the zoo"
    assert links.title("https://example.org/essay") == "An essay"

    def offline(url):
        raise OSError("offline")

    monkeypatch.setattr(links, "_fetch", offline)
    assert links.title("https://example.org/essay") == ""
