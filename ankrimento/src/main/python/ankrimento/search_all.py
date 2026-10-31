"""Search ALL: upstream's search across highlights, sources, document text and cards.

Desktop runs ``frontend/search_all_results.run_search`` (no Qt; built to run on
a worker) and shows the HTML it returns in a text browser. The same call runs
here, and its HTML is read back into sections, documents and results, each with
the place it opens (a PDF page, an EPUB section or a card), for a native list.

The filters are desktop's, stored under the same config keys; the
``frontend/search_all.py`` helpers that read them import Qt, so they are
mirrored here.
"""

from __future__ import annotations

from html.parser import HTMLParser
from typing import Any
from urllib.parse import urlparse

from aqt import mw

from ankrimento import runtime

_ADDON_PKG = "incremento"

# Mirrors frontend/search_all.py.
_FILTER_CONFIG_KEYS = {
    "annotations": "search_all_filter_annotations",
    "pdf_highlights": "search_all_filter_pdf_highlights",
    "epub_highlights": "search_all_filter_epub_highlights",
    "pdf_sources": "search_all_filter_pdf_sources",
    "epub_sources": "search_all_filter_epub_sources",
    "pdf_content": "search_all_filter_pdf_content",
    "epub_content": "search_all_filter_epub_content",
    "image_ocr": "search_all_filter_image_ocr",
    "cards": "search_all_filter_cards",
    "current_profile": "search_all_filter_current_profile",
}
_FILTER_DEFAULTS = {
    "annotations": True,
    "pdf_highlights": True,
    "epub_highlights": True,
    "pdf_sources": True,
    "epub_sources": True,
    "pdf_content": False,
    "epub_content": True,
    "image_ocr": True,
    "cards": True,
    "current_profile": True,
}


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON_PKG)


def filters() -> dict[str, bool]:
    """Mirrors ``configured_search_all_filter_enabled`` for every filter."""
    config = _config()
    return {name: bool(config.get(key, _FILTER_DEFAULTS[name])) for name, key in _FILTER_CONFIG_KEYS.items()}


def set_filter(name: str, enabled: bool) -> None:
    """Mirrors ``_set_search_all_filter_enabled``."""
    from incremento.backend.config_service import save_addon_config

    key = _FILTER_CONFIG_KEYS[name]
    config = _config()
    config[key] = bool(enabled)
    save_addon_config(mw.addonManager, _ADDON_PKG, config)


def search(query: str) -> dict[str, Any]:
    """Upstream's Search ALL with the saved filters, as sections of documents and results."""
    from incremento.frontend.search_all_results import run_search

    rt = runtime.current()
    enabled = frozenset(name for name, on in filters().items() if on)
    results = run_search(str(rt.addon_dir), rt.profile, rt.col, str(query or ""), enabled)
    sections = _Results().read(results.html)
    for section in sections:
        for group in section["groups"]:
            for item in group["items"]:
                _resolve(item, rt.col)
    return {"sections": sections}


def _resolve(item: dict[str, Any], col: Any) -> None:
    """Where a result opens: ``inc://pdf/<card>/<page>``, ``inc://epub/<card>/<section>`` or ``inc://card/<note>``."""
    url = urlparse(item.pop("url"))
    parts = [part for part in url.path.split("/") if part]
    item["kind"] = url.netloc
    if url.netloc in {"pdf", "epub"} and len(parts) >= 2:
        item["card_id"], item["position"] = int(parts[0]), int(parts[1])
    elif url.netloc == "card" and parts:
        card_ids = col.card_ids_of_note(int(parts[0]))
        item["card_id"], item["position"] = (int(card_ids[0]) if card_ids else 0), 0
    else:
        item["kind"], item["card_id"], item["position"] = "", 0, 0


class _Results(HTMLParser):
    """Reads run_search's markup: <h3> per section, <b> per document, <li><a href> per result, then its excerpt."""

    def __init__(self) -> None:
        super().__init__()
        self.sections: list[dict] = []
        self._text: list[str] | None = None
        self._target = ""
        self._item: dict | None = None

    def read(self, html: str) -> list[dict]:
        self.feed(html or "")
        self.close()
        return [section for section in self.sections if any(group["items"] for group in section["groups"])]

    def _group(self) -> dict:
        section = self.sections[-1]
        if not section["groups"]:
            section["groups"].append({"title": None, "items": []})
        return section["groups"][-1]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"h3", "b"}:
            self._text, self._target = [], tag
        elif tag == "li" and self.sections:
            self._item = {"url": "", "label": "", "snippet": ""}
            self._group()["items"].append(self._item)
        elif tag == "a" and self._item is not None:
            self._item["url"] = dict(attrs).get("href") or ""
            self._text, self._target = [], "a"
        elif tag == "span" and self._item is not None:
            self._text, self._target = [], "span"

    def handle_endtag(self, tag: str) -> None:
        if tag != self._target or self._text is None:
            if tag == "li":
                self._item = None
            return
        text = " ".join("".join(self._text).split())
        self._text = None
        if tag == "h3":
            self.sections.append({"heading": text, "groups": []})
        elif tag == "b" and self.sections:
            self.sections[-1]["groups"].append({"title": text, "items": []})
        elif tag == "a" and self._item is not None:
            self._item["label"] = text
        elif tag == "span" and self._item is not None:
            self._item["snippet"] = text

    def handle_data(self, data: str) -> None:
        if self._text is not None:
            self._text.append(data)
