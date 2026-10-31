"""The library: Incremento's documents, as desktop's Document Bookshelf lists them.

Each entry has what the bookshelf shows for a document: its kind, title,
priority and where reading stopped, read from upstream's own progress
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
}

_QUEUE_SUSPENDED = -1


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

    result = []
    for card_id in col.find_cards(search):
        card = col.get_card(card_id)
        kind = KINDS.get(card.note().note_type()["name"])
        if kind is None:
            continue
        position = ""
        if kind == "pdf":
            row = progress("pdf_progress", "page", card_id)
            position = f"p. {row[0]}" if row else ""
        elif kind == "epub":
            row = progress("epub_progress", "section_index", card_id)
            position = f"ch. {int(row[0]) + 1}" if row else ""
        elif kind == "video":
            row = progress("video_progress", "position", card_id)
            position = fmt_time(float(row[0])) if row and row[0] else ""
        elif kind == "web":
            row = progress("web_progress", "scroll_ratio", card_id)
            position = f"{round(float(row[0]) * 100)}%" if row and row[0] else ""
        result.append(
            {
                "card_id": int(card_id),
                "kind": kind,
                "title": _card_title(card),
                "priority": float(get_priority(addon_dir, profile, int(card_id))),
                "position": position,
                "done": card.queue == _QUEUE_SUSPENDED,
            }
        )
    return result
