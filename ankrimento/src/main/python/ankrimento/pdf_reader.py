"""The PDF reader: upstream's pdf.js viewer (``web/``) driven as desktop drives it.

Desktop hosts ``web/pdf_dock.html`` in a Qt dock (``frontend/pdf_dock.py``).
The viewer reports everything through ``pycmd`` messages and is driven by
``window.incremento*`` functions. Here the host app shows the same page in a
WebView, passes each ``pycmd`` message to :func:`handle`, and evaluates the
JavaScript returned.

``frontend/pdf_dock.py`` imports Qt, so the small helpers it uses to build the
viewer's state are mirrored below; each names its desktop original.
"""

from __future__ import annotations

import json
import os
from typing import Any

from aqt import mw

from ankrimento import reading_pages, runtime

MSG_NAV = "incremento_pdf_nav:"
MSG_ZOOM = "incremento_pdf_zoom:"
MSG_SCROLL = "incremento_pdf_scroll:"
MSG_APPEARANCE = "incremento_pdf_appearance:"
MSG_HL_ADD = "incremento_pdf_hl_add:"
MSG_HL_UPDATE = "incremento_pdf_hl_update:"
MSG_HL_DEL = "incremento_pdf_hl_del:"
MSG_MARK_READ = "incremento_pdf_mark_read:"
MSG_BOOKMARK_ADD = "incremento_pdf_bookmark_add:"
MSG_BOOKMARK_DELETE = "incremento_pdf_bookmark_delete:"
MSG_BOOKMARK_LIST = "incremento_pdf_bookmark_list:"
MSG_LIMIT_OVERRIDE = "incremento_pdf_limit_override:"
MSG_FINISHED = "incremento_pdf_finished:"
MSG_FILL_FIELD = "incremento_fill_field:"
MSG_OPEN_LINK = "incremento_pdf_open_link:"
MSG_GET_PAGE_CARDS = "incremento_get_page_cards:"

_ADDON_PKG = "incremento"


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON_PKG)


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def _js_call(function: str, *args: Any) -> str:
    rendered = ", ".join(json.dumps(arg) for arg in args)
    return f"window.{function} && window.{function}({rendered});"


# ── Mirrors of frontend/pdf_dock.py helpers ──────────────────────────────────


def _highlights_payload(card_id: int) -> list[dict]:
    """Mirrors ``_pdf_highlights_payload`` (without highlight-to-card links yet)."""
    from incremento.backend.pdf_highlights import load_highlights

    addon_dir, profile = _context()
    payload = []
    for row in load_highlights(addon_dir, profile, int(card_id)):
        enriched = dict(row)
        # The viewer paints a highlight with annotation data but no native annotation
        # ("kind") transparent. Upstream's updates add bookkeeping to that data, and only
        # desktop's PDF annotation sync, which the device lacks, adds the kind.
        if not (enriched.get("pdf_annotation") or {}).get("kind"):
            enriched.pop("pdf_annotation", None)
        enriched["linked_note_id"] = 0
        enriched["linked_highlight_count"] = 0
        payload.append(enriched)
    return payload


def _bookmarks_payload(card_id: int) -> list[dict]:
    """Mirrors ``_pdf_bookmarks_payload``."""
    from incremento.backend.reader_bookmarks import list_reader_bookmarks

    addon_dir, profile = _context()
    try:
        return list_reader_bookmarks(addon_dir, profile, int(card_id), "pdf")
    except Exception:
        return []


def _limit_status(card_id: int, *, current_page: int | None = None) -> dict:
    """Mirrors ``_current_pdf_limit_status``."""
    from incremento.backend.pdf_manager import get_pdf_daily_limit_status

    addon_dir, profile = _context()
    try:
        return get_pdf_daily_limit_status(addon_dir, profile, int(card_id), current_page=current_page)
    except Exception:
        return {"enabled": False}


def page_cards_script(card_id: int, page: int) -> str:
    """Mirrors ``_reconcile_pdf_page_sources`` and the page-cards push."""
    from incremento.backend.db import (
        delete_pdf_card_sources_for_note_ids,
        get_pdf_card_sources,
        get_pdf_page_card_counts,
    )

    addon_dir, profile = _context()
    col = runtime.current().col

    def note_exists(note_id: int) -> bool:
        try:
            col.get_note(note_id)
            return True
        except Exception:
            return False

    cards = get_pdf_card_sources(addon_dir, profile, int(card_id), int(page))
    stale = {int(row.get("note_id", 0) or 0) for row in cards}
    stale = {note_id for note_id in stale if note_id > 0 and not note_exists(note_id)}
    if stale:
        delete_pdf_card_sources_for_note_ids(addon_dir, profile, int(card_id), stale)
        cards = get_pdf_card_sources(addon_dir, profile, int(card_id), int(page))
    live = [row for row in cards if int(row.get("note_id", 0) or 0) > 0]
    counts = get_pdf_page_card_counts(addon_dir, profile, int(card_id))
    return _js_call("incrementoReceivePageCards", {"page": int(page), "cards": live, "pageCounts": counts})


# The highlight the viewer created for the extraction that follows it.
_last_highlight: dict[int, str] = {}


def _appearance_mode(card_id: int) -> str:
    """Mirrors ``resolve_pdf_appearance_mode``."""
    from incremento.backend.config_service import (
        configured_pdf_default_appearance,
        configured_pdf_force_default_appearance,
        normalize_pdf_appearance_mode,
    )
    from incremento.backend.pdf_manager import get_pdf_appearance_mode

    addon_dir, profile = _context()
    cfg = _config()
    default_mode = configured_pdf_default_appearance(cfg)
    if configured_pdf_force_default_appearance(cfg):
        return default_mode
    try:
        saved = get_pdf_appearance_mode(addon_dir, profile, int(card_id))
    except Exception:
        saved = None
    if isinstance(saved, str) and saved.strip().casefold() in {"original", "dark", "night"}:
        return normalize_pdf_appearance_mode(saved)
    return default_mode


# ── Opening a document ───────────────────────────────────────────────────────


def pdf_path(card_id: int) -> str:
    """The managed PDF file of a PDF topic card, or "" when it is missing."""
    from incremento.backend.pdf_manager import pdf_storage_abspath

    addon_dir, profile = _context()
    col = runtime.current().col
    note = col.get_card(int(card_id)).note()
    path = pdf_storage_abspath(note["PDF_Filename"], profile=profile, addon_dir=addon_dir)
    return path if path and os.path.isfile(path) else ""


def start_script(card_id: int, file_url: str, worker_url: str) -> str:
    """JavaScript that opens the card's PDF at its saved position.

    Mirrors the start sequence of ``frontend/pdf_dock.py`` (``incrementoPdfStart``).
    """
    from incremento.backend.config_service import (
        configured_pdf_controls_collapsed,
        configured_pdf_link_preview_size,
        configured_pdf_link_preview_zoom,
        configured_reader_default_highlight_color,
    )
    from incremento.backend.i18n import get_custom_reader_payload, get_locale
    from incremento.backend.pdf_manager import (
        get_page,
        get_read_anchor,
        get_read_page,
        get_scroll_ratio,
        get_zoom,
    )

    addon_dir, profile = _context()
    card_id = int(card_id)
    cfg = _config()
    note = runtime.current().col.get_card(card_id).note()
    filename = str(note["PDF_Filename"])
    page = max(1, int(get_page(addon_dir, profile, card_id) or 1))
    zoom = float(get_zoom(addon_dir, profile, card_id) or 1.0)
    scroll_ratio = float(get_scroll_ratio(addon_dir, profile, card_id) or 0.0)
    read_page = int(get_read_page(addon_dir, profile, card_id) or 0)
    args = [
        card_id,
        filename,
        page,
        zoom,
        scroll_ratio,
        read_page,
        get_read_anchor(addon_dir, profile, card_id),
        "",  # search query
        [],  # search hits
        -1,  # active search hit
        "",  # jump excerpt
        "",  # jump highlight id
        False,  # scroll to read anchor
        _limit_status(card_id, current_page=page),
        bool(cfg.get("highlight_when_extracting", True)),
        bool(cfg.get("pdf_scroll_to_top_on_page_change", True)),
        _bookmarks_payload(card_id),
        get_locale(),
        get_custom_reader_payload(),
        _appearance_mode(card_id),
        bool(cfg.get("pdf_show_highlight_delete_buttons", True)),
        configured_pdf_link_preview_zoom(cfg, profile),
        None,  # page y
        configured_pdf_controls_collapsed(cfg, profile),
        configured_reader_default_highlight_color(cfg, profile),
        configured_pdf_link_preview_size(cfg, profile),
    ]
    return (
        f"window._pdfWorkerSrc = {json.dumps(worker_url)};"
        f"window._pdfFileUrl = {json.dumps(file_url)};"
        "window._incPdfByteRange = null;"
        f"window._incPdfHighlights = {json.dumps(_highlights_payload(card_id))};"
        "window._pdfNativeHighlightsVisible = false;"
        f"window._incPdfBookmarks = {json.dumps(args[16])};"
        f"window.incrementoPdfStart && window.incrementoPdfStart({', '.join(json.dumps(a) for a in args)});"
    )


# ── Messages from the viewer ─────────────────────────────────────────────────


def handle(card_id: int, msg: str) -> list[str]:
    """Apply one ``pycmd`` message from the viewer; return JavaScript to run."""
    from incremento.backend import pdf_highlights, pdf_manager, reader_bookmarks

    addon_dir, profile = _context()
    card_id = int(card_id)

    def payload(prefix: str) -> Any:
        return json.loads(msg[len(prefix):])

    if msg.startswith(MSG_NAV):
        # Mirrors the _MSG_NAV branch, including the daily page limit.
        _, raw_card, raw_page = msg.split(":")
        if int(raw_card) != card_id:
            return []
        page = int(raw_page)
        current = pdf_manager.get_page(addon_dir, profile, card_id)
        status = _limit_status(card_id, current_page=current)
        allowed_max = status.get("allowed_max_page")
        if (
            status.get("enabled")
            and status.get("enforcement_mode") in {"soft_lock", "hard_stop"}
            and not status.get("override_enabled")
            and allowed_max is not None
            and page > current
            and page > int(allowed_max)
        ):
            return [_js_call("incrementoReceivePdfLimitStatus", status)]
        pdf_manager.set_page(addon_dir, profile, card_id, page)
        reading_pages.record("pdf", card_id, page)
        return [_js_call("incrementoReceivePdfLimitStatus", _limit_status(card_id, current_page=page))]
    if msg.startswith(MSG_ZOOM):
        _, raw_card, raw_zoom = msg.split(":")
        zoom = float(raw_zoom)
        if int(raw_card) == card_id and 0 < zoom <= 20:
            pdf_manager.set_zoom(addon_dir, profile, card_id, zoom)
        return []
    if msg.startswith(MSG_SCROLL):
        data = payload(MSG_SCROLL)
        if int(data.get("cardId", 0) or 0) == card_id:
            pdf_manager.set_scroll_ratio(addon_dir, profile, card_id, float(data.get("scrollRatio", 0.0) or 0.0))
        return []
    if msg.startswith(MSG_APPEARANCE):
        data = payload(MSG_APPEARANCE)
        mode = str(data.get("mode") or "").strip().casefold()
        if int(data.get("cardId", 0) or 0) == card_id and mode in {"original", "dark", "night"}:
            pdf_manager.set_pdf_appearance_mode(addon_dir, profile, card_id, mode)
        return []
    if msg.startswith(MSG_HL_ADD):
        data = payload(MSG_HL_ADD)
        if int(data["cardId"]) == card_id:
            pdf_highlights.add_highlight(addon_dir, profile, card_id, data["highlight"])
            _last_highlight[card_id] = str(data["highlight"].get("id") or "")
        return []
    if msg.startswith(MSG_HL_UPDATE):
        data = payload(MSG_HL_UPDATE)
        if int(data["cardId"]) == card_id:
            pdf_highlights.update_highlight(addon_dir, profile, card_id, data["highlight"])
        return []
    if msg.startswith(MSG_HL_DEL):
        from incremento.backend.db import delete_pdf_card_source_for_highlight

        data = payload(MSG_HL_DEL)
        if int(data["cardId"]) == card_id:
            highlight_id = str(data["id"] or "")
            pdf_highlights.remove_highlight(addon_dir, profile, card_id, highlight_id)
            delete_pdf_card_source_for_highlight(addon_dir, profile, card_id, highlight_id)
        return []
    if msg.startswith(MSG_MARK_READ):
        data = payload(MSG_MARK_READ)
        if int(data.get("cardId", 0) or 0) != card_id:
            return []
        read_page = int(data.get("readPage", 0) or 0)
        anchor = data.get("anchor")
        status = _limit_status(card_id, current_page=max(1, read_page or 1))
        allowed_max = status.get("allowed_max_page")
        blocked = bool(
            status.get("enabled")
            and status.get("enforcement_mode") in {"soft_lock", "hard_stop"}
            and not status.get("override_enabled")
            and allowed_max is not None
            and read_page > int(allowed_max)
        )
        if not blocked:
            pdf_manager.set_read_page(
                addon_dir, profile, card_id, read_page, anchor if isinstance(anchor, dict) else None
            )
        return [_js_call("incrementoReceivePdfLimitStatus", status)]
    if msg.startswith(MSG_BOOKMARK_ADD):
        data = payload(MSG_BOOKMARK_ADD)
        if int(data.get("cardId", 0) or 0) == card_id:
            reader_bookmarks.add_reader_bookmark(
                addon_dir, profile, card_id, "pdf", {"page": int(data.get("page", 1) or 1)}
            )
        return [_js_call("incrementoReceivePdfBookmarks", _bookmarks_payload(card_id))]
    if msg.startswith(MSG_BOOKMARK_DELETE):
        data = payload(MSG_BOOKMARK_DELETE)
        if int(data.get("cardId", 0) or 0) == card_id:
            reader_bookmarks.delete_reader_bookmark(
                addon_dir, profile, card_id, "pdf", str(data.get("id") or "")
            )
        return [_js_call("incrementoReceivePdfBookmarks", _bookmarks_payload(card_id))]
    if msg.startswith(MSG_BOOKMARK_LIST):
        return [_js_call("incrementoReceivePdfBookmarks", _bookmarks_payload(card_id))]
    if msg.startswith(MSG_LIMIT_OVERRIDE):
        from incremento.backend.i18n import t

        if int(msg[len(MSG_LIMIT_OVERRIDE):]) != card_id:
            return []
        status = pdf_manager.set_pdf_daily_limit_override(
            addon_dir,
            profile,
            card_id,
            enabled=True,
            current_page=pdf_manager.get_page(addon_dir, profile, card_id),
        )
        mw.notify_host("message", kind="tooltip", text=t("reader_pdf_limit_overridden"))
        return [_js_call("incrementoReceivePdfLimitStatus", status)]
    if msg.startswith(MSG_FINISHED):
        # Mirrors the _MSG_FINISHED branch: suspend the document card.
        from incremento.backend.i18n import t

        if int(msg[len(MSG_FINISHED):]) == card_id:
            changes = runtime.current().col.sched.suspend_cards([card_id])
            from aqt.operations import on_op_finished

            on_op_finished(mw, changes, None)
            mw.notify_host("message", kind="tooltip", text=t("reader_pdf_card_suspended"))
            mw.notify_host("pdf_close", card_id=card_id)
        return []
    if msg.startswith(MSG_FILL_FIELD):
        # Desktop fills its Add Card dock; here the draft feeds the host's dialog.
        from ankrimento import extract

        data = payload(MSG_FILL_FIELD)
        extract.add_to_draft(
            card_id,
            int(data.get("idx", 0) or 0),
            data,
            page=pdf_manager.get_page(addon_dir, profile, card_id),
            highlight_id=_last_highlight.pop(card_id, ""),
        )
        mw.notify_host("extract_draft", card_id=card_id)
        return []
    if msg.startswith(MSG_GET_PAGE_CARDS):
        _, raw_card, raw_page = msg.split(":")
        if int(raw_card) != card_id:
            return []
        return [page_cards_script(card_id, int(raw_page))]
    if msg.startswith(MSG_OPEN_LINK):
        data = payload(MSG_OPEN_LINK)
        if int(data.get("cardId", 0) or 0) == card_id:
            mw.notify_host("open_url", url=str(data.get("url") or ""))
        return []
    # Desktop-only features (annotation sync, snapshots, linked references,
    # due-card review from the reader) are not handled yet.
    return []


# ── Find in document ──────────────────────────────────────────────────────────
#
# Desktop extracts each page's text with PyMuPDF (search_indexer), which the
# device doesn't have; the viewer's own pdf.js extracts it instead and hands it
# to upstream's indexer as its extractor, so the index and its state are
# recorded exactly as desktop records them (and Search ALL finds the text).


def index_state(card_id: int) -> str:
    """The PDF's text index: "current", "empty" (no text found), "stale" (missing or older than the file) or "missing" (no file).

    Upstream's import records "empty" when it has no extractor, as on the device, so
    the host keeps track of which "empty" PDFs the viewer has actually read.
    """
    import time

    from incremento.backend.db import get_connection
    from incremento.backend.search_indexer import _signature, _state_is_current

    path = pdf_path(card_id)
    if not path:
        return "missing"
    addon_dir, profile = _context()
    row = get_connection(addon_dir, profile).execute(
        "SELECT status, source_mtime_ns, source_size, indexed_at FROM document_index_state WHERE kind='pdf' AND card_id=?",
        (int(card_id),),
    ).fetchone()
    mtime_ns, size_bytes = _signature(path)
    # index_pdf_documents' own freshness check.
    if not _state_is_current(row, mtime_ns=mtime_ns, size_bytes=size_bytes, now=int(time.time())):
        return "stale"
    return "empty" if row[0] == "empty" else "current"


def index_pages(card_id: int, pages: list[str]) -> None:
    """Indexes the page texts the viewer extracted, through upstream's indexer."""
    from incremento.backend.search_indexer import index_pdf_documents

    path = pdf_path(card_id)
    if not path:
        return
    addon_dir, profile = _context()
    index_pdf_documents(
        addon_dir,
        profile,
        [(int(card_id), path)],
        extractor=lambda *args, **kwargs: [str(text or "") for text in pages],
        force=True,
    )


def _search_snippet(text: str, query: str, max_len: int = 180) -> str:
    """Mirrors ``_pdf_search_snippet``."""
    import re

    plain = " ".join(str(text or "").split())
    if not plain:
        return ""
    lowered = plain.lower()
    needle = str(query or "").strip().lower()
    idx = lowered.find(needle) if needle else -1
    if idx < 0:
        tokens = [tok for tok in re.findall(r"\w+", needle) if len(tok) >= 2]
        for token in tokens:
            idx = lowered.find(token)
            if idx >= 0:
                break
    if idx < 0:
        return plain[:max_len]
    start = max(0, idx - (max_len // 3))
    end = min(len(plain), start + max_len)
    snippet = plain[start:end]
    if start > 0:
        snippet = "..." + snippet
    if end < len(plain):
        snippet += "..."
    return snippet


def search(card_id: int, query: str, limit: int = 250) -> list[dict]:
    """Mirrors ``current_card_pdf_search_hits``: the pages matching ``query``, in page order."""
    from incremento.backend.db import search_pdf_text_index_for_card

    normalized = str(query or "").strip()
    if not normalized:
        return []
    addon_dir, profile = _context()
    rows = search_pdf_text_index_for_card(addon_dir, profile, int(card_id), normalized, limit=limit)
    return sorted(
        (
            {
                "page": int(page),
                "snippet": _search_snippet(text or "", normalized),
                "excerpt": _search_snippet(text or "", normalized, max_len=260),
            }
            for page, text in rows
        ),
        key=lambda hit: hit["page"],
    )


# ── Bookmarks and highlights outside the viewer ──────────────────────────────
#
# Desktop edits them in the viewer's own panel. The device lists them in a
# sheet; each change is made as the viewer's code makes it (its pycmd branch
# or, for a recolour, its recolorHighlight), then the viewer is sent the new
# list as desktop sends it.


def marks(card_id: int) -> dict:
    """The PDF's bookmarks (page, label, comment) and highlights (page, text, colour, note), in page order."""
    bookmarks = [
        {
            "id": b["id"],
            "page": int((b.get("location") or {}).get("page") or 1),
            "label": b.get("label") or "",
            "comment": b.get("comment_text") or "",
        }
        for b in _bookmarks_payload(card_id)
    ]
    highlights = [
        {
            "id": str(h.get("id") or ""),
            "page": int(h.get("page") or 1),
            "text": str(h.get("text") or ""),
            "color": str(h.get("color") or "yellow"),
            "note": str(h.get("note") or ""),
            "style": _annotation_style(h) or "highlight",
        }
        for h in _highlights_payload(card_id)
        if h.get("color") != "snapshot"
    ]
    return {
        "bookmarks": sorted(bookmarks, key=lambda b: b["page"]),
        "highlights": sorted(highlights, key=lambda h: (h["page"], h["id"])),
    }


def add_bookmark(card_id: int, page: int) -> str:
    """Mirrors the _MSG_BOOKMARK_ADD branch."""
    return "".join(handle(card_id, MSG_BOOKMARK_ADD + json.dumps({"cardId": int(card_id), "page": int(page)})))


def delete_bookmark(card_id: int, bookmark_id: str) -> str:
    """Mirrors the _MSG_BOOKMARK_DELETE branch."""
    return "".join(handle(card_id, MSG_BOOKMARK_DELETE + json.dumps({"cardId": int(card_id), "id": str(bookmark_id)})))


_STYLE_KINDS = {
    "highlight": "Highlight", "underline": "Underline", "squiggly": "Squiggly",
    "dashed": "Underline", "dotted": "Underline", "strikeout": "StrikeOut",
}


def _annotation_style(mark: dict) -> str | None:
    """Mirrors the viewer's ``annotationStyle``: None for marks that take no style (snapshots, other native kinds)."""
    if mark.get("color") == "snapshot":
        return None
    native = mark.get("pdf_annotation") or {}
    kind = native.get("kind")
    if kind and kind not in _STYLE_KINDS.values():
        return None
    style = native.get("incremento_style")
    if style in _STYLE_KINDS and _STYLE_KINDS[style] == kind:
        return style
    if kind:
        return next(key for key, value in _STYLE_KINDS.items() if value == kind)
    return mark.get("style") if mark.get("style") in _STYLE_KINDS else "highlight"


def highlight_style(card_id: int, highlight_id: str, style: str) -> str:
    """Mirrors the viewer's ``restyleHighlight``: upstream stores the style with its native annotation kind."""
    from incremento.backend import pdf_highlights

    addon_dir, profile = _context()
    original = next((h for h in pdf_highlights.load_highlights(addon_dir, profile, int(card_id)) if h["id"] == highlight_id), None)
    if original is None or style not in _STYLE_KINDS or _annotation_style(original) is None:
        return ""
    pdf_highlights.update_highlight(addon_dir, profile, int(card_id), {**original, "style": style})
    return _highlights_script(card_id)


def bookmark_comment(card_id: int, bookmark_id: str, comment: str) -> str:
    """A bookmark's comment, kept with upstream's reader bookmarks (desktop edits them on videos)."""
    from incremento.backend.reader_bookmarks import update_reader_bookmark_comment

    addon_dir, profile = _context()
    update_reader_bookmark_comment(addon_dir, profile, int(card_id), "pdf", str(bookmark_id), str(comment or ""))
    return _js_call("incrementoReceivePdfBookmarks", _bookmarks_payload(card_id))


def _highlights_script(card_id: int) -> str:
    return _js_call("incrementoReceivePdfHighlights", _highlights_payload(card_id))


def highlight_color(card_id: int, highlight_id: str, color: str) -> str:
    """Mirrors the viewer's ``recolorHighlight``: the colour, and a native annotation's colour with it."""
    from incremento.backend import pdf_highlights
    from incremento.backend.highlight_colors import COLORS, normalize_highlight_color

    addon_dir, profile = _context()
    value = normalize_highlight_color(color)
    original = next((h for h in pdf_highlights.load_highlights(addon_dir, profile, int(card_id)) if h["id"] == highlight_id), None)
    if original is None or original.get("color") == "snapshot":
        return ""
    highlight = {**original, "color": value}
    if original.get("pdf_annotation") and value in COLORS:
        highlight["pdf_annotation"] = {**original["pdf_annotation"], "color": list(COLORS[value][0])}
    pdf_highlights.update_highlight(addon_dir, profile, int(card_id), highlight)
    return _highlights_script(card_id)


def highlight_note(card_id: int, highlight_id: str, note: str) -> str:
    """Desktop's highlight note editor: saves the note and updates it in the viewer."""
    from incremento.backend import pdf_highlights

    addon_dir, profile = _context()
    updated = pdf_highlights.update_highlight_note(addon_dir, profile, int(card_id), str(highlight_id), str(note or ""))
    if not updated:
        return ""
    return _js_call(
        "incrementoUpdatePdfHighlightNote", str(highlight_id), updated.get("note", ""), updated.get("note_html", "")
    )


def delete_highlight(card_id: int, highlight_id: str) -> str:
    """Mirrors the _MSG_HL_DEL branch, then sends the viewer the remaining highlights."""
    handle(card_id, MSG_HL_DEL + json.dumps({"cardId": int(card_id), "id": str(highlight_id)}))
    return _highlights_script(card_id)
