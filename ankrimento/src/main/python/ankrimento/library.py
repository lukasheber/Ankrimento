"""The library: Incremento's documents, as desktop's Document Bookshelf lists them.

Each entry has what the bookshelf shows for a document: its kind, title,
priority, cover (the image named in the PDF or EPUB cover field, from the
collection's media) and where reading stopped, read from upstream's own progress
tables (``pdf_progress``, ``epub_progress``, ``video_progress``,
``web_progress``). Done topics (suspended) are flagged, not left out.
"""

from __future__ import annotations

from typing import Any

from ankrimento import runtime

KINDS = {
    "Incremento PDF": "pdf",
    "Incremento EPUB": "epub",
    "Incremento Web": "web",
    "Incremento Video": "video",
    "Incremento Writing": "writing",
    "Incremento Local File": "file",
}

_QUEUE_SUSPENDED = -1


def _cover(col: Any, note: Any, kind: str) -> str:
    """The cover image file desktop's bookshelf shows, or ""."""
    import os

    from incremento.backend.epub_manager import EPUB_COVER_FIELD
    from incremento.backend.pdf_manager import PDF_COVER_FIELD

    field = {"pdf": PDF_COVER_FIELD, "epub": EPUB_COVER_FIELD}.get(kind)
    if field is None or field not in note.keys():
        return ""
    name = str(note[field] or "").strip()
    path = os.path.join(col.media.dir(), name)
    return path if name and "/" not in name and "\\" not in name and os.path.isfile(path) else ""


def entries() -> list[dict[str, Any]]:
    from incremento.backend.db import get_connection
    from incremento.backend.knowledge_tree import _card_title
    from incremento.backend.priority_manager import get_priority
    from incremento.backend.video_manager import fmt_time

    rt = runtime.current()
    col = rt.col
    addon_dir, profile = str(rt.addon_dir), rt.profile
    search = " OR ".join(f'"note:{name}"' for name in KINDS)
    db = get_connection(addon_dir, profile)

    def progress(table: str, columns: str, card_id: int):
        return db.execute(f"SELECT {columns} FROM {table} WHERE card_id = ?", (card_id,)).fetchone()

    from ankrimento import coverage

    result = []
    card_ids = list(col.find_cards(search))
    figures = coverage.library_figures(card_ids)
    for card_id in card_ids:
        card = col.get_card(card_id)
        note = card.note()
        kind = KINDS.get(note.note_type()["name"])
        if kind is None:
            continue
        position, page, chapter = "", 0, 0
        if kind == "pdf":
            row = progress("pdf_progress", "page", card_id)
            page = int(row[0]) if row else 0
            position = f"p. {page}" if row else ""
        elif kind == "epub":
            row = progress("epub_progress", "section_index", card_id)
            chapter = int(row[0]) + 1 if row else 0
            position = f"ch. {chapter}" if row else ""
        elif kind == "video":
            row = progress("video_progress", "position", card_id)
            position = fmt_time(float(row[0])) if row and row[0] else ""
        elif kind == "web":
            row = progress("web_progress", "scroll_ratio", card_id)
            position = f"{round(float(row[0]) * 100)}%" if row and row[0] else ""
        result.append(
            {
                "card_id": int(card_id),
                # Desktop's "Last added" sorts by note id, which encodes the creation time.
                "note_id": int(card.nid),
                "kind": kind,
                "title": _card_title(card),
                "priority": float(get_priority(addon_dir, profile, int(card_id))),
                "position": position,
                # The page or chapter reached, for the app to word in its language (0: none).
                "page": page,
                "chapter": chapter,
                # Desktop's bookshelf figures for a PDF or EPUB; None for any other kind.
                "viewed_percent": figures.get(int(card_id), {}).get("viewed_percent"),
                "marked_percent": figures.get(int(card_id), {}).get("marked_percent"),
                "coverage_label": coverage.label(figures.get(int(card_id))),
                "done": card.queue == _QUEUE_SUSPENDED,
                "cover": _cover(col, note, kind),
            }
        )
    return result
