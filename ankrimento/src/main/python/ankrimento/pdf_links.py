"""A PDF page's links to other PDF pages, as desktop keeps them.

Mirrors ``frontend/pdf_page_links.py`` (Qt): the links of a page from either end
(``backend/document_links.py``), where each opens, removing one, and adding one
with desktop's checks (``frontend/document_link_ui.link_pdf``). Desktop opens a
link in its dock or a reference window and picks a destination in its own
dialogs; the phone opens readers and its own sheets, asked for through the host.
"""

from __future__ import annotations

import json
from typing import Any

from ankrimento import runtime


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def links(card_id: int, page: int) -> list[dict[str, Any]]:
    """The page's links as the viewer lists them: id, direction, the other end's page and title."""
    from incremento.backend.document_links import list_pdf_page_links
    from incremento.backend.i18n import t
    from incremento.backend.pdf_reference import resolve_pdf_reference

    addon_dir, profile = _context()
    col = runtime.current().col
    visible = []
    for row in list_pdf_page_links(addon_dir, profile, int(card_id), int(page)):
        try:
            snapshot = resolve_pdf_reference(addon_dir, profile, col, row["target_card_id"])
            title, available = snapshot["title"], True
        except ValueError:
            title, available = t("reader_reference_unavailable"), False
        visible.append(
            {key: row[key] for key in ("id", "direction", "target_page")} | {"title": title[:240], "available": available}
        )
    return visible


def target(card_id: int, page: int, link_id: int, direction: str) -> dict[str, Any]:
    """Where the link opens: its other end's card, page and place on the page.

    Raises ``ValueError`` as desktop's jump does: the link or its document gone,
    or the page beyond the document's daily limit.
    """
    from incremento.backend.document_links import list_pdf_page_links
    from incremento.backend.pdf_reference import resolve_pdf_reference

    addon_dir, profile = _context()
    rows = list_pdf_page_links(addon_dir, profile, int(card_id), int(page))
    link = next((row for row in rows if row["id"] == int(link_id) and row["direction"] == direction), None)
    if link is None:
        raise ValueError("Connection unavailable")
    snapshot = resolve_pdf_reference(addon_dir, profile, runtime.current().col, link["target_card_id"])
    if link["target_page"] > limit(snapshot["card_id"]):
        raise ValueError("Destination page is locked")
    return {
        "card_id": snapshot["card_id"],
        "page": link["target_page"],
        "scroll": link["target_scroll"],
        "page_y": link.get("target_y"),
        # The exact words at the other end, which the viewer reveals (desktop's passage links); {} for a page link.
        "passage": link.get("target_passage") or {},
    }


def remove(card_id: int, page: int, link_id: int) -> bool:
    from incremento.backend.document_links import remove_pdf_page_link

    addon_dir, profile = _context()
    return bool(remove_pdf_page_link(addon_dir, profile, int(card_id), int(page), int(link_id)))


def limit(card_id: int) -> int:
    """The last page a link may point to: the document's daily limit when it is locked (``maximum_reference_page``)."""
    from incremento.backend.pdf_manager import get_pdf_daily_limit_status
    from incremento.backend.pdf_reader_state import maximum_reference_page

    addon_dir, profile = _context()
    status = get_pdf_daily_limit_status(addon_dir, profile, int(card_id), count_current_page=False, persist_usage=False)
    return int(maximum_reference_page(status))


def add(
    source_card_id: int,
    source_page: int,
    source_y: float | None,
    target_card_id: int,
    target_page: int,
    target_scroll: float,
    target_y: float | None,
) -> int:
    """Links the source page to the destination, as ``document_link_ui.link_pdf`` saves it; returns the link's id."""
    from incremento.backend.document_links import add_document_link
    from incremento.backend.pdf_reference import resolve_pdf_reference

    addon_dir, profile = _context()
    col = runtime.current().col
    # Both ends must still be there, as desktop checks once its picker closes.
    resolve_pdf_reference(addon_dir, profile, col, int(source_card_id))
    resolve_pdf_reference(addon_dir, profile, col, int(target_card_id))
    if int(target_page) > limit(int(target_card_id)):
        raise ValueError("Destination page is locked")
    return int(
        add_document_link(
            addon_dir,
            profile,
            source_card_id=int(source_card_id),
            source_kind="pdf",
            source_page=int(source_page),
            target_card_id=int(target_card_id),
            target_page=int(target_page),
            target_scroll=float(target_scroll),
            target_y=None if target_y is None else float(target_y),
            source_y=None if source_y is None else float(source_y),
        )
    )


def viewer_message(card_id: int, payload: Any) -> list[str]:
    """The viewer's own page-links panel (``PdfPageLinksBridge.handle``); returns JavaScript to run."""
    from aqt import mw
    from incremento.backend.i18n import t

    request_keys = ("cardId", "page", "request", "generation")
    if (
        not isinstance(payload, dict)
        or payload.get("cardId") != int(card_id)
        or any(type(payload.get(key)) is not int or not 1 <= payload[key] <= 2**53 - 1 for key in request_keys)
    ):
        return []
    page, operation = payload["page"], payload.get("operation")
    point = payload.get("page_y")
    if point is not None and (type(point) not in (int, float) or not 0 <= point <= 1):
        point = None
    request = {key: payload[key] for key in request_keys}
    try:
        if operation == "list":
            rows = links(card_id, page)
            return [f"window.incrementoReceivePdfPageLinks && window.incrementoReceivePdfPageLinks({json.dumps({**request, 'links': rows})});"]
        if operation == "remove":
            if remove(card_id, page, int(payload.get("id") or 0)):
                return ["window.incrementoRefreshPdfPageLinks && window.incrementoRefreshPdfPageLinks();"]
            return []
        if operation == "jump":
            # Desktop's "beside" opens a reference window; the phone opens the reader either way.
            there = target(card_id, page, int(payload.get("id") or 0), str(payload.get("direction")))
            mw.notify_host("pdf_open", source_card_id=int(card_id), **there)
            return []
        if operation in ("create", "manage"):
            # Desktop's manage dialog lists and links the page's connections: the phone's links sheet does.
            event = "pdf_page_link_create" if operation == "create" else "pdf_page_links_show"
            fields = {"card_id": int(card_id), "page": page}
            if operation == "create":
                fields["page_y"] = point
            mw.notify_host(event, **fields)
            return []
    except ValueError:
        if operation == "list":
            return [f"window.incrementoReceivePdfPageLinks && window.incrementoReceivePdfPageLinks({json.dumps({**request, 'links': [], 'error': True})});"]
    # Recordings linked to a page are desktop's alone.
    mw.notify_host("message", kind="tooltip", text=t("reader_reference_unavailable"))
    return []
