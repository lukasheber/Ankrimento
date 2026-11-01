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


_PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


def test_an_articles_images_come_with_it(rt, monkeypatch):
    """Upstream turns images into links; the phone downloads them, so upstream's import stores them."""
    import os

    import incremento.backend.webpage_markdown as webpage_markdown

    from ankrimento import web_images

    article = dict(_ARTICLE, title="Illustrated reading")
    article["markdown_document"] += (
        "\n[Image: A diagram](<https://example.org/diagram.png>)\n\n[Image: Gone](<https://example.org/gone.png>)\n"
    )
    monkeypatch.setattr(webpage_markdown, "fetch_webpage_markdown", lambda url, **_: dict(article))

    def download(url: str) -> bytes:
        if url.endswith("gone.png"):
            raise OSError("404")
        return _PNG

    monkeypatch.setattr(web_images, "_download", download)
    added = json.loads(facade.import_web_document("https://example.org/illustrated"))

    opened = json.loads(facade.epub_open(added["card_id"], 0))
    stored = [name for _, _, names in os.walk(opened["root"]) for name in names]
    assert any(name.endswith(".png") for name in stored)
    text = "".join(open(os.path.join(d, n), encoding="utf8", errors="ignore").read() for d, _, ns in os.walk(opened["root"]) for n in ns if n.endswith((".xhtml", ".html")))
    assert "<img" in text
    # One that could not be downloaded stays a link to it, as upstream wrote it.
    assert "https://example.org/gone.png" in text


def test_image_links_become_local_images_within_upstreams_limits(tmp_path, monkeypatch):
    from ankrimento import web_images

    monkeypatch.setattr(web_images, "_download", lambda url: _PNG)
    markdown = "Text [Image: One](<https://example.org/1.png>) and [Image: One](<https://example.org/1.png>)"
    local = web_images.localize(markdown, tmp_path)
    assert local == "Text ![One](web-images/image-1.png) and ![One](web-images/image-1.png)"
    assert (tmp_path / "web-images" / "image-1.png").read_bytes() == _PNG
