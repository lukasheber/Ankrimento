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
import secrets
from typing import Any

from aqt import mw

from ankrimento import runtime

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

_ADDON_PKG = "incremento"
_SCRIPT_CONSTANTS = ("_EPUB_HIGHLIGHT_COLORS", "_MAX_EPUB_SELECTION_CHARS", "_PYCMD_BRIDGE")
_builder: Any = None


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


def section_script(card_id: int, section_index: int, nonce: str, scroll_ratio: float | None = None) -> str:
    """The page script for a loaded chapter; mirrors desktop's ``_on_load_finished``."""
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
        search_query="",
        highlights=load_highlights(addon_dir, profile, card_id, section_index=section_index),
        bridge_nonce=nonce,
        highlight_color=default_color,
        default_highlight_color=default_color,
        sections=metadata.get("sections") or [],
        appearance=get_appearance(addon_dir, profile, card_id),
    )


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
            extract.add_to_draft(card_id, int(data.get("idx", 0) or 0), {"text": str(data.get("text") or "")},
                                 page=section_index, highlight_id="")
            mw.notify_host("extract_draft", card_id=card_id)
    elif msg.startswith(MSG_OPEN_LINK):
        data = payload(MSG_OPEN_LINK)
        href = str(data.get("href") or "")
        if for_this_card(data) and href.startswith(("http://", "https://")):
            mw.notify_host("open_url", url=href)
    return {}
