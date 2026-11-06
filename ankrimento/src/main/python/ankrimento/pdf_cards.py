"""The PDF viewer's card buttons, answered on the phone.

Desktop opens its Add Card dock, its card preview and its browser; the phone
starts its extract sheet, opens the card in its item reader and searches
AnkiDroid's browser. Which cards and which text are desktop's own.
"""

from __future__ import annotations

from typing import Any

from ankrimento import runtime

_ADDON_PKG = "incremento"


def _note_exists(note_id: int) -> bool:
    """Mirrors ``frontend/pdf_dock.py:_note_exists``."""
    try:
        return int(note_id or 0) > 0 and runtime.current().col.get_note(int(note_id)) is not None
    except Exception:
        return False


def browse_query(note_ids: list[Any]) -> str:
    """The browser search for ``note_ids``, each once and only those still in the collection.
    Mirrors ``frontend/pdf_dock.py:_browse_note_ids_in_browser`` (``nid:`` terms)."""
    ordered: list[int] = []
    for raw in list(note_ids or []):
        try:
            note_id = int(raw or 0)
        except Exception:
            continue
        if note_id > 0 and note_id not in ordered and _note_exists(note_id):
            ordered.append(note_id)
    return " OR ".join(f"nid:{note_id}" for note_id in ordered)


def document_note_ids(card_id: int) -> list[int]:
    """Mirrors ``frontend/pdf_dock.py:_live_pdf_document_source_note_ids``: the document's card notes,
    forgetting those deleted since."""
    from incremento.backend.db import delete_pdf_card_sources_for_note_ids, get_pdf_document_source_note_ids

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    note_ids = get_pdf_document_source_note_ids(addon_dir, profile, int(card_id))
    stale = [note_id for note_id in note_ids if not _note_exists(note_id)]
    if stale:
        delete_pdf_card_sources_for_note_ids(addon_dir, profile, int(card_id), stale)
        note_ids = get_pdf_document_source_note_ids(addon_dir, profile, int(card_id))
    return [note_id for note_id in note_ids if _note_exists(note_id)]


def cards_search(note_ids: list[Any], empty_key: str) -> dict[str, str]:
    """The search for the browser, or desktop's message (``empty_key``) when there is nothing to show."""
    from incremento.backend.i18n import t

    query = browse_query(note_ids)
    return {"query": query, "message": "" if query else t(empty_key)}


def first_card(note_id: int) -> int:
    if not _note_exists(note_id):
        return 0
    card_ids = runtime.current().col.get_note(int(note_id)).card_ids()
    return int(card_ids[0]) if card_ids else 0


def highlight_card(card_id: int, highlight_id: str) -> dict[str, Any]:
    """Mirrors ``frontend/pdf_dock.py:_open_or_create_pdf_highlight_card``: the highlight's card when it
    has one (``card_id``); otherwise a draft with its text and the link back in the configured field
    (``draft``); or desktop's message."""
    from aqt import mw
    from incremento.backend.config_service import load_addon_config
    from incremento.backend.content_safety import external_plain_text_to_anki_html
    from incremento.backend.db import delete_pdf_card_source_for_highlight, get_pdf_card_source_for_highlight
    from incremento.backend.i18n import t
    from incremento.backend.pdf_highlights import load_highlights

    from ankrimento import extract, pdf_reader

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    highlight_id = str(highlight_id or "")
    highlight = next(
        (row for row in load_highlights(addon_dir, profile, int(card_id)) if str(row.get("id") or "") == highlight_id),
        None,
    )
    if not highlight:
        return {"message": t("reader_pdf_highlight_missing")}
    source = get_pdf_card_source_for_highlight(addon_dir, profile, int(card_id), highlight_id)
    note_id = int((source or {}).get("note_id", 0) or 0)
    if note_id > 0 and _note_exists(note_id):
        return {"card_id": first_card(note_id)}
    if source and note_id > 0:
        delete_pdf_card_source_for_highlight(addon_dir, profile, int(card_id), highlight_id)
    excerpt = str(highlight.get("text") or "").strip()
    if not excerpt:
        return {"message": t("reader_pdf_highlight_empty")}
    # Mirrors configured_pdf_highlight_extract_field (frontend/pdf_dock.py): the field, from 1, clamped.
    try:
        field = int(load_addon_config(mw.addonManager, _ADDON_PKG).get("pdf_highlight_extract_field", 1))
    except Exception:
        field = 1
    index = max(1, min(20, field)) - 1
    page = int(highlight.get("page", 1) or 1)
    html = external_plain_text_to_anki_html(excerpt)
    citation = pdf_reader._citation(int(card_id), page, excerpt, highlight_id) if extract.source_link_on("pdf") else ""
    extract.add_html_to_draft(
        int(card_id), index, html + ("<br>" + citation if citation else ""), page=page, highlight_id=highlight_id
    )
    return {"draft": True}


def _highlight_text_html(card_id: int, highlight: dict) -> str:
    """A highlight's text as a field, with desktop's link back to it (``_pdf_highlight_bulk_generated_text``)."""
    from incremento.backend.content_safety import external_plain_text_to_anki_html

    from ankrimento import extract, pdf_reader

    excerpt = str(highlight.get("text") or "").strip()
    page = int(highlight.get("page", 1) or 1)
    html = external_plain_text_to_anki_html(excerpt)
    citation = pdf_reader._citation(int(card_id), page, excerpt, str(highlight.get("id") or "")) if extract.source_link_on("pdf") else ""
    return html + ("<br>" + citation if citation else "")


def _unlinked_highlights(card_id: int) -> list[dict]:
    """Mirrors ``frontend/pdf_dock.py:_missing_pdf_highlight_card_rows``: text highlights without a card."""
    from incremento.backend.db import get_pdf_card_source_for_highlight
    from incremento.backend.pdf_highlights import load_highlights

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    rows = []
    for highlight in load_highlights(addon_dir, profile, int(card_id)):
        highlight_id = str(highlight.get("id") or "").strip()
        if not highlight_id or not str(highlight.get("text") or "").strip():
            continue
        source = get_pdf_card_source_for_highlight(addon_dir, profile, int(card_id), highlight_id)
        if _note_exists(int((source or {}).get("note_id", 0) or 0)):
            continue
        rows.append(highlight)
    return sorted(rows, key=lambda row: (int(row.get("page", 0) or 0), str(row.get("id") or "")))


def _field_index() -> int:
    """Mirrors ``configured_pdf_highlight_extract_field`` (frontend/pdf_dock.py), from 0."""
    from aqt import mw
    from incremento.backend.config_service import load_addon_config

    try:
        field = int(load_addon_config(mw.addonManager, _ADDON_PKG).get("pdf_highlight_extract_field", 1))
    except Exception:
        field = 1
    return max(1, min(20, field)) - 1


def bulk_plan(card_id: int) -> dict[str, Any]:
    """Desktop's "cards from highlights" dialog, as data: the highlights without a card, the note types
    with the configured field, the extract defaults."""
    from incremento.backend.i18n import t

    from ankrimento import extract

    rows = [
        {"id": str(h["id"]), "page": int(h.get("page", 0) or 0), "text": str(h.get("text") or "").strip()}
        for h in _unlinked_highlights(card_id)
    ]
    defaults = extract.draft(int(card_id))
    index = _field_index()
    models = runtime.current().col.models.all()
    note_types = extract.card_note_types(m["name"] for m in models if len(m["flds"]) > index and m.get("type", 0) == 0)
    note_type = defaults["note_type"] if defaults["note_type"] in note_types else (note_types[0] if note_types else "")
    return {
        "rows": rows,
        "message": "" if rows else t("reader_pdf_no_unlinked_highlights"),
        "note_types": note_types,
        "note_type": note_type,
        "kind": defaults["kind"],
        "priority": defaults["priority"],
    }


def bulk_create(card_id: int, highlight_ids: list[str], note_type: str, kind: str) -> dict[str, Any]:
    """Mirrors ``frontend/pdf_dock.py:create_pdf_highlight_batch_notes``: one card per chosen highlight
    still without one, linked to it; the phone's extracts are made as its single ones are."""
    from incremento.backend.i18n import t

    from ankrimento import extract

    index = _field_index()
    priority = float(extract.draft(int(card_id))["priority"])
    created = skipped = failed = 0
    errors: list[str] = []
    unlinked = {str(h["id"]): h for h in _unlinked_highlights(card_id)}
    for position, highlight_id in enumerate(highlight_ids, start=1):
        highlight = unlinked.get(str(highlight_id))
        if highlight is None:
            skipped += 1
            continue
        fields = [""] * (index + 1)
        fields[index] = _highlight_text_html(card_id, highlight)
        try:
            extract._create(int(card_id), fields, note_type, kind, priority, int(highlight.get("page", 1) or 1), str(highlight_id))
            created += 1
        except Exception as error:  # noqa: BLE001 - reported per row, as desktop does
            failed += 1
            errors.append(t("reader_pdf_batch_row_error", index=position, highlight_id=highlight_id, error=error))
    if failed:
        message = t("reader_pdf_batch_result", created=created, skipped=skipped, failed=failed, errors="\n".join(errors))
    else:
        message = t("reader_pdf_batch_success", created=created, skipped=skipped)
    # Mirrors _refresh_pdf_highlight_card_links: the page's cards and the highlights' card links.
    from incremento.backend.pdf_manager import get_page

    from ankrimento import pdf_reader

    rt = runtime.current()
    page = int(get_page(str(rt.addon_dir), rt.profile, int(card_id)) or 1)
    script = pdf_reader.page_cards_script(int(card_id), page) + pdf_reader._highlights_script(int(card_id))
    return {"created": created, "skipped": skipped, "failed": failed, "message": message, "script": script}


def new_card(card_id: int) -> None:
    """The viewer's Add Card: desktop opens its Add Card dock with the PDF as the source; here the extract
    sheet starts on the page being read, or shows the card already started."""
    from incremento.backend.pdf_manager import get_page

    from ankrimento import extract

    if int(card_id) in extract._drafts:
        return
    rt = runtime.current()
    extract._drafts[int(card_id)] = extract.Draft(page=int(get_page(str(rt.addon_dir), rt.profile, int(card_id)) or 1))
