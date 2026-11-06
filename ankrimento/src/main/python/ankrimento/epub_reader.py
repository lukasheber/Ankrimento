"""The EPUB reader: desktop's EPUB page script, driven as desktop drives it.

Desktop shows each extracted chapter in a Qt web view and injects a page
script (``_build_page_script`` in ``frontend/epub_dock.py``) that reports
through ``console.log`` messages. That module imports Qt, so the script
builder is compiled here from upstream's source on its own, with the few
module names it uses supplied below. The host app loads chapter files in a
WebView, injects :func:`section_script`, and passes each console message to
:func:`handle`.
"""

from __future__ import annotations

import ast
import json
import os
import re
import secrets
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlsplit

from aqt import mw

from ankrimento import reading_pages, runtime

MSG_PREFIX = "__incremento_epub__:"  # upstream's _PYCMD_BRIDGE
MSG_FILL_FIELD = "incremento_epub_fill_field:"
MSG_HL_ADD = "incremento_epub_hl_add:"
MSG_HL_UPDATE = "incremento_epub_hl_update:"
MSG_HL_DEL = "incremento_epub_hl_del:"
MSG_PROGRESS = "incremento_epub_progress:"
MSG_MARK_READ = "incremento_epub_mark_read:"
MSG_SECTION_NAV = "incremento_epub_section_nav:"
MSG_PAGE_JUMP = "incremento_epub_page_jump:"
MSG_OPEN_LINK = "incremento_epub_open_link:"
MSG_HL_COLOR = "incremento_epub_hl_color:"
MSG_HL_STYLE = "incremento_epub_hl_style:"

_ADDON_PKG = "incremento"
_SCRIPT_CONSTANTS = ("_EPUB_HIGHLIGHT_COLORS", "_MAX_EPUB_SELECTION_CHARS", "_PYCMD_BRIDGE")
_LINK_FUNCTIONS = ("_path_is_within_root", "_resolve_epub_reader_link")
_builder: Any = None
_resolver: Any = None


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON_PKG)


def page_script_builder() -> Any:
    """Upstream's ``_build_page_script``, compiled from ``frontend/epub_dock.py`` alone."""
    global _builder
    if _builder is not None:
        return _builder
    from incremento.backend.highlight_colors import normalize_highlight_color
    from incremento.backend.i18n import get_locale, t

    path = runtime.current().addon_dir / "frontend" / "epub_dock.py"
    tree = ast.parse(path.read_text(encoding="utf8"), filename=str(path))
    namespace: dict[str, Any] = {
        "json": json,
        "t": t,
        "get_locale": get_locale,
        "normalize_highlight_color": normalize_highlight_color,
        # Mirrors frontend/epub_dock.py:configured_highlight_when_extracting.
        "configured_highlight_when_extracting": lambda config=None: bool(
            (config or _config()).get("highlight_when_extracting", True)
        ),
        "_current_sections": [],
    }
    function = None
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1:
            target = node.targets[0]
            if isinstance(target, ast.Name) and target.id in _SCRIPT_CONSTANTS:
                namespace[target.id] = ast.literal_eval(node.value)
        elif isinstance(node, ast.FunctionDef) and node.name == "_build_page_script":
            function = node
    missing = [name for name in _SCRIPT_CONSTANTS if name not in namespace]
    if function is None or missing:
        raise RuntimeError(f"upstream EPUB page script not found (missing {missing or '_build_page_script'})")
    module = ast.Module(body=[function], type_ignores=[])
    exec(compile(module, str(path), "exec"), namespace)  # noqa: S102 - upstream's own code
    _builder = namespace["_build_page_script"]
    return _builder


def link_resolver() -> Any:
    """Upstream's ``_resolve_epub_reader_link``, compiled from ``frontend/epub_dock.py`` alone."""
    global _resolver
    if _resolver is not None:
        return _resolver
    from incremento.frontend.reader_links import normalize_external_reader_url

    path = runtime.current().addon_dir / "frontend" / "epub_dock.py"
    tree = ast.parse(path.read_text(encoding="utf8"), filename=str(path))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in _LINK_FUNCTIONS]
    if len(functions) != len(_LINK_FUNCTIONS):
        raise RuntimeError("upstream EPUB link resolver not found")
    namespace: dict[str, Any] = {
        "Path": Path,
        "urlsplit": urlsplit,
        "unquote": unquote,
        "normalize_external_reader_url": normalize_external_reader_url,
    }
    exec(compile(ast.Module(body=functions, type_ignores=[]), str(path), "exec"), namespace)  # noqa: S102 - upstream's own code
    _resolver = namespace["_resolve_epub_reader_link"]
    return _resolver


def _metadata(card_id: int) -> tuple[str, dict]:
    from incremento.backend.epub_manager import ensure_epub_extracted, epub_storage_abspath

    addon_dir, profile = _context()
    note = runtime.current().col.get_card(int(card_id)).note()
    filename = str(note["EPUB_Filename"])
    path = epub_storage_abspath(filename, profile=profile)
    if not path or not os.path.isfile(path):
        raise FileNotFoundError(filename)
    return filename, ensure_epub_extracted(path, stored_filename=filename, profile=profile)


def open_section(card_id: int, section_index: int | None = None) -> dict[str, Any]:
    """Where the chapter file is, for the host to load: ``root``, ``path``, section index and count."""
    from incremento.backend.epub_manager import (
        get_epub_extract_dir,
        get_epub_progress,
        get_epub_section_path,
    )

    addon_dir, profile = _context()
    filename, metadata = _metadata(card_id)
    sections = metadata.get("sections") or []
    if section_index is None:
        section_index = get_epub_progress(addon_dir, profile, int(card_id))[0]
    section_index = max(0, min(int(section_index), len(sections) - 1))
    path = get_epub_section_path(addon_dir, filename, section_index, profile=profile, metadata=metadata)
    return {
        "root": get_epub_extract_dir(filename, profile=profile),
        "path": path,
        "section_index": section_index,
        "section_count": len(sections),
        "nonce": secrets.token_hex(8),
    }


def section_script(
    card_id: int,
    section_index: int,
    nonce: str,
    scroll_ratio: float | None = None,
    search_query: str = "",
    link_fragment: str = "",
) -> str:
    """The page script for a loaded chapter; mirrors desktop's ``_on_load_finished``.

    With ``search_query``, the script finds and selects its first match once loaded, as
    desktop opens a search result; with ``link_fragment``, it opens at that anchor, as
    desktop opens a followed link. Links are always tappable (desktop's links toggle on).
    """
    from incremento.backend.config_service import configured_reader_default_highlight_color
    from incremento.backend.epub_appearance import get_appearance
    from incremento.backend.epub_highlights import load_highlights
    from incremento.backend.epub_manager import get_epub_font_scale, get_epub_progress, get_read_anchor

    addon_dir, profile = _context()
    card_id, section_index = int(card_id), int(section_index)
    saved_section, saved_ratio, _ = get_epub_progress(addon_dir, profile, card_id)
    if scroll_ratio is None:
        scroll_ratio = saved_ratio if saved_section == section_index else 0.0
    _, metadata = _metadata(card_id)
    default_color = configured_reader_default_highlight_color(_config(), profile)
    return page_script_builder()(
        card_id=card_id,
        section_index=section_index,
        scroll_ratio=float(scroll_ratio),
        text_scale=get_epub_font_scale(addon_dir, profile, card_id),
        read_anchor=get_read_anchor(addon_dir, profile, card_id),
        focus_offset=-1,
        search_query=str(search_query or ""),
        highlights=load_highlights(addon_dir, profile, card_id, section_index=section_index),
        bridge_nonce=nonce,
        clickable_links=True,
        link_fragment=str(link_fragment or ""),
        highlight_color=default_color,
        default_highlight_color=default_color,
        sections=metadata.get("sections") or [],
        appearance=get_appearance(addon_dir, profile, card_id),
    )


def reading(card_id: int) -> dict[str, Any]:
    """The per-book settings desktop's EPUB dock applies, and the chapter titles its contents list shows."""
    from incremento.backend.epub_appearance import get_appearance
    from incremento.backend.epub_manager import get_epub_font_scale

    addon_dir, profile = _context()
    _, metadata = _metadata(card_id)
    return {
        "text_scale": get_epub_font_scale(addon_dir, profile, int(card_id)),
        "appearance": get_appearance(addon_dir, profile, int(card_id)),
        "contents": [str(section.get("title") or "") for section in metadata.get("sections") or []],
    }


def handle(card_id: int, section_index: int, msg: str) -> dict[str, Any]:
    """Apply one message (prefix and nonce already removed).

    Returns ``{"navigate": {"section_index": n, "scroll_ratio": r}}`` when the
    host should load another chapter, otherwise ``{}``.
    """
    from incremento.backend import epub_highlights, epub_manager

    addon_dir, profile = _context()
    card_id = int(card_id)

    def payload(prefix: str) -> Any:
        data = json.loads(msg[len(prefix):])
        return data if isinstance(data, dict) else {}

    def for_this_card(data: dict) -> bool:
        return int(data.get("cardId", 0) or 0) == card_id

    if msg.startswith(MSG_PROGRESS):
        data = payload(MSG_PROGRESS)
        if for_this_card(data):
            epub_manager.set_epub_progress(
                addon_dir,
                profile,
                card_id,
                section_index=int(data.get("sectionIndex", section_index) or 0),
                scroll_ratio=float(data.get("scrollRatio", 0.0) or 0.0),
            )
            if "pageIndex" in data:
                reading_pages.record("epub", card_id, int(data.get("pageIndex") or 0) + 1)
    elif msg.startswith(MSG_MARK_READ):
        data = payload(MSG_MARK_READ)
        if for_this_card(data):
            epub_manager.set_read_section_index(
                addon_dir, profile, card_id, int(data.get("sectionIndex", section_index) or 0), data.get("anchor")
            )
    elif msg.startswith(MSG_HL_ADD):
        data = payload(MSG_HL_ADD)
        if for_this_card(data):
            epub_highlights.add_highlight(addon_dir, profile, card_id, data["highlight"])
    elif msg.startswith(MSG_HL_UPDATE):
        data = payload(MSG_HL_UPDATE)
        if for_this_card(data):
            epub_highlights.update_highlight(addon_dir, profile, card_id, data["highlight"])
    elif msg.startswith((MSG_HL_COLOR, MSG_HL_STYLE)):
        # Mirrors _set_current_epub_mark_appearance: a colour or style chosen on the page's own menu.
        is_color = msg.startswith(MSG_HL_COLOR)
        data = payload(MSG_HL_COLOR if is_color else MSG_HL_STYLE)
        highlight_id = str(data.get("id") or "")
        on_page = {row["id"] for row in epub_highlights.load_highlights(addon_dir, profile, card_id, section_index=section_index)}
        if for_this_card(data) and highlight_id in on_page:
            update = epub_highlights.update_highlight_color if is_color else epub_highlights.update_highlight_style
            update(addon_dir, profile, card_id, highlight_id, data.get("color" if is_color else "style"))
    elif msg.startswith(MSG_HL_DEL):
        data = payload(MSG_HL_DEL)
        if for_this_card(data):
            epub_highlights.remove_highlight(addon_dir, profile, card_id, str(data["id"]))
    elif msg.startswith(MSG_SECTION_NAV):
        # Mirrors _jump_section_boundary: next chapter from its start, previous from its end.
        delta = int(payload(MSG_SECTION_NAV).get("delta", 0) or 0)
        if delta:
            return {"navigate": {"section_index": section_index + delta, "scroll_ratio": 0.0 if delta > 0 else 1.0}}
    elif msg.startswith(MSG_PAGE_JUMP):
        data = payload(MSG_PAGE_JUMP)
        if for_this_card(data):
            pages = max(1, int(data.get("sectionPages", 1) or 1))
            ratio = (max(1, int(data.get("sectionPage", 1) or 1)) - 1) / max(1, pages - 1) if pages > 1 else 0.0
            return {"navigate": {"section_index": int(data.get("sectionIndex", 0) or 0), "scroll_ratio": ratio}}
    elif msg.startswith(MSG_FILL_FIELD):
        from ankrimento import extract

        data = payload(MSG_FILL_FIELD)
        if for_this_card(data):
            text = {"text": str(data.get("text") or "")}
            citation = _citation(card_id, section_index, int(data.get("startOffset", -1) or -1)) if extract.source_link_on("epub") else ""
            extract.add_with_citation(card_id, int(data.get("idx", 0) or 0), text, citation, page=section_index)
            mw.notify_host("extract_draft", card_id=card_id)
    elif msg.startswith(MSG_OPEN_LINK):
        data = payload(MSG_OPEN_LINK)
        if for_this_card(data):
            return {"link": open_link(card_id, section_index, str(data.get("href") or ""), data.get("sourceScrollRatio"))}
    return {}


# ── Links ────────────────────────────────────────────────────────────────────
#
# Desktop resolves a tapped link with ``_resolve_epub_reader_link`` and opens the
# chapter at its anchor, keeping where it was for its jump-back button. A phone
# shows a footnote over the page instead of leaving it, so the note's text is
# read here from the target chapter.

_BLOCKS = {"p", "li", "aside", "div", "dd", "dt", "blockquote", "section", "td", "body", "footer"}
_INLINE = {"a", "span", "sup", "sub", "small", "em", "strong", "b", "i"}
_HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6"}
_VOID = {"br", "img", "hr", "meta", "link", "input", "col", "area", "wbr", "source"}
_MAX_NOTE_CHARS = 1500


def open_link(card_id: int, section_index: int, href: str, from_ratio: Any = None) -> dict[str, Any]:
    """Where a tapped link leads: ``external`` (a web URL), ``internal`` (section, fragment and the
    note's text when it is a footnote), or ``blocked`` with upstream's message."""
    from incremento.backend.epub_manager import get_epub_extract_dir, get_epub_section_path
    from incremento.backend.i18n import t

    addon_dir, profile = _context()
    filename, metadata = _metadata(card_id)
    count = len(metadata.get("sections") or [])
    paths = [get_epub_section_path(addon_dir, filename, index, profile=profile, metadata=metadata) for index in range(count)]
    current = max(0, min(int(section_index), count - 1))
    try:
        resolved = link_resolver()(
            href,
            content_root=get_epub_extract_dir(filename, profile=profile),
            current_section_path=paths[current],
            section_paths=paths,
        )
    except Exception:  # noqa: BLE001 - as desktop, any failure blocks the link
        resolved = None
    if resolved is None:
        return {"kind": "blocked", "message": t("reader_epub_link_blocked")}
    if resolved["kind"] == "external":
        return {"kind": "external", "url": resolved["url"]}
    target, fragment = int(resolved["section_index"]), str(resolved.get("fragment") or "")
    try:
        ratio = max(0.0, min(float(from_ratio), 1.0))
    except (TypeError, ValueError):
        ratio = 0.0
    return {
        "kind": "internal",
        "section_index": target,
        "fragment": fragment,
        "note": _note_text(paths[target], fragment) if fragment else "",
        "from_ratio": ratio,
    }


class _Element:
    def __init__(self, tag: str, parent: _Element | None) -> None:
        self.tag, self.parent, self.parts = tag, parent, []

    def text(self) -> str:
        return "".join(part if isinstance(part, str) else part.text() for part in self.parts)


class _Page(HTMLParser):
    """A chapter as a light tree, with the element carrying each id or name."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = self.open = _Element("", None)
        self.ids: dict[str, _Element] = {}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        element = _Element(tag.lower(), self.open)
        self.open.parts.append(element)
        for name, value in attrs:
            if name in ("id", "name") and value:
                self.ids.setdefault(value, element)
        if element.tag not in _VOID:
            self.open = element

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        if tag.lower() not in _VOID:
            self.open = self.open.parent or self.root

    def handle_endtag(self, tag: str) -> None:
        tag, element = tag.lower(), self.open
        while element is not None and element.tag != tag:
            element = element.parent
        if element is not None and element.parent is not None:
            self.open = element.parent

    def handle_data(self, data: str) -> None:
        self.open.parts.append(data)


def _note_text(path: str, fragment: str) -> str:
    """The text of the note at ``fragment``: its block when the id is on a number or a link
    inside it; "" for a heading, a whole chapter or anything longer than a note."""
    try:
        page = _Page()
        page.feed(Path(path).read_text(encoding="utf8", errors="replace"))
    except (OSError, ValueError):
        return ""
    element = page.ids.get(fragment)
    if element is None or element.tag in _HEADINGS:
        return ""
    if element.tag in _INLINE or len(element.text().strip()) <= 3:
        while element is not None and element.tag not in _BLOCKS:
            element = element.parent
    if element is None or element.tag == "body":
        return ""
    text = re.sub(r"\s+", " ", element.text()).strip()
    return text if 0 < len(text) <= _MAX_NOTE_CHARS else ""


def _citation(card_id: int, section_index: int, start_offset: int) -> str:
    """Mirrors ``epub_citation`` and ``epub_anchor_link_html`` in frontend/epub_dock.py: a link to the
    chapter and the selection's place, named by the chapter."""
    from incremento.backend.epub_manager import get_epub_progress
    from incremento.backend.i18n import t
    from incremento.frontend.reader_links import make_reader_anchor_html

    addon_dir, profile = _context()
    _, metadata = _metadata(card_id)
    sections = metadata.get("sections") or []
    section = sections[section_index] if 0 <= section_index < len(sections) else {}
    title = str((section or {}).get("title") or "").strip() or t("reader_section_number", number=section_index + 1)
    saved_section, saved_ratio, _ = get_epub_progress(addon_dir, profile, int(card_id))
    ratio = max(0.0, min(float(saved_ratio or 0.0), 1.0)) if saved_section == section_index else 0.0
    ratio_text = f"{ratio:.6f}".rstrip("0").rstrip(".") or "0"
    command = f"incremento_open_epub:{int(card_id)}:{int(section_index)}:{max(-1, int(start_offset))}:{ratio_text}"
    return make_reader_anchor_html(command, title)


def search(card_id: int, query: str, limit: int = 120) -> list[dict[str, Any]]:
    """The sections matching ``query`` in section order, with their titles and an excerpt.

    Upstream's search of its per-section text index, which its import fills.
    """
    from incremento.backend.db import search_epub_text_index_for_card

    from ankrimento.pdf_reader import _search_snippet

    normalized = str(query or "").strip()
    if not normalized:
        return []
    addon_dir, profile = _context()
    rows = search_epub_text_index_for_card(addon_dir, profile, int(card_id), normalized, limit=limit)
    return sorted(
        ({"section": int(section), "title": str(title or ""), "snippet": _search_snippet(text or "", normalized)} for section, title, text in rows),
        key=lambda hit: hit["section"],
    )


# ── Highlights outside the page ──────────────────────────────────────────────
#
# Desktop edits them from the page's own highlight menu. The device lists them
# in a sheet; each change is made with upstream's EPUB highlight functions, as
# desktop's dock makes it, then the page is updated through its own hooks.


def _js_call(function: str, *args: Any) -> str:
    rendered = ", ".join(json.dumps(arg) for arg in args)
    return f"window.{function} && window.{function}({rendered});"


def marks(card_id: int) -> dict[str, Any]:
    """The book's highlights in book order, each with its chapter's title, colour and note."""
    from incremento.backend.epub_highlights import load_highlights

    addon_dir, profile = _context()
    _, metadata = _metadata(card_id)
    titles = [str(section.get("title") or "") for section in metadata.get("sections") or []]
    return {
        "highlights": [
            {
                "id": str(h["id"]),
                "section": int(h["sectionIndex"]),
                "title": titles[h["sectionIndex"]] if 0 <= h["sectionIndex"] < len(titles) else "",
                "text": str(h.get("text") or ""),
                "color": str(h.get("color") or "yellow"),
                "note": str(h.get("note") or ""),
                "style": str(h.get("style") or "highlight"),
            }
            for h in load_highlights(addon_dir, profile, int(card_id))
        ]
    }


def highlight_color(card_id: int, highlight_id: str, color: str) -> str:
    from incremento.backend.epub_highlights import update_highlight_color

    addon_dir, profile = _context()
    if not update_highlight_color(addon_dir, profile, int(card_id), str(highlight_id), str(color)):
        return ""
    return _js_call("incrementoUpdateEpubHighlightColor", str(highlight_id), str(color))


def highlight_style(card_id: int, highlight_id: str, style: str) -> str:
    from incremento.backend.epub_highlights import update_highlight_style

    addon_dir, profile = _context()
    if not update_highlight_style(addon_dir, profile, int(card_id), str(highlight_id), str(style)):
        return ""
    return _js_call("incrementoUpdateEpubHighlightStyle", str(highlight_id), str(style))


def highlight_note(card_id: int, highlight_id: str, note: str) -> str:
    from incremento.backend.epub_highlights import update_highlight_note

    addon_dir, profile = _context()
    updated = update_highlight_note(addon_dir, profile, int(card_id), str(highlight_id), str(note or ""))
    if not updated:
        return ""
    return _js_call("incrementoUpdateEpubHighlightNote", str(highlight_id), updated.get("note", ""), updated.get("note_html", ""))


def delete_highlight(card_id: int, highlight_id: str, section_index: int) -> str:
    """Removes a highlight as the page's delete does, then gives the page the chapter's remaining ones."""
    from incremento.backend.epub_highlights import load_highlights, remove_highlight

    addon_dir, profile = _context()
    remove_highlight(addon_dir, profile, int(card_id), str(highlight_id))
    rows = load_highlights(addon_dir, profile, int(card_id), section_index=int(section_index))
    return _js_call("incrementoReplaceEpubHighlights", rows)


def snapshot(card_id: int, section_index: int, image: str) -> None:
    """Mirrors ``frontend/epub_dock.py:_handle_epub_snapshot``: the region's image saved as media and put
    in a new card with the link back to its chapter. Desktop asks which field takes it; the phone puts it
    in the front, as the extract sheet edits the fields after (as its PDF snapshots do)."""
    from html import escape

    from aqt import mw
    from incremento.backend.i18n import t

    from ankrimento import extract
    from ankrimento.pdf_reader import snapshot_media

    try:
        name = snapshot_media(str(image or ""))
        html = f'<img src="{escape(name, quote=True)}">'
        citation = _citation(int(card_id), int(section_index), -1) if extract.source_link_on("epub") else ""
        extract.add_html_to_draft(int(card_id), 0, html + ("<br>" + citation if citation else ""), page=int(section_index))
        mw.notify_host("extract_draft", card_id=int(card_id))
    except Exception as error:  # noqa: BLE001 - shown, as desktop's showInfo does
        mw.notify_host("message", kind="info", text=t("reader_epub_snapshot_failed", error=error))
