"""An EPUB chapter's connections to passages in other books (or its own), as desktop keeps them.

Desktop makes a connection from a reader's context menu with both books open side by side, shows it in the chapter and
follows it with a Jump Back (``frontend/epub_dock.py``, Qt). A phone holds one book at a time, so it lists the chapter's
connections from either end (``backend/epub_connections.py``), opens the other end with its passage revealed, and
returns with Back. Making one stays on desktop.
"""

from __future__ import annotations

from typing import Any

from ankrimento import runtime


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def _rows(card_id: int, section: int) -> list[dict[str, Any]]:
    from incremento.backend.epub_connections import list_connections

    addon_dir, profile = _context()
    return list_connections(addon_dir, profile, int(card_id), int(section))


def _resolve(card_id: int) -> dict[str, Any]:
    """Desktop's resolver, after the phone's own refresh of a book extracted by an older version."""
    from incremento.backend.epub_reference import resolve_epub_reference

    addon_dir, profile = _context()
    col = runtime.current().col
    try:
        return resolve_epub_reference(addon_dir, profile, col, int(card_id))
    except ValueError:
        from ankrimento import epub_reader

        try:
            epub_reader._metadata(int(card_id))  # extracts or refreshes the book, as opening it does
        except Exception as error:
            raise ValueError("EPUB reference unavailable") from error
        return resolve_epub_reference(addon_dir, profile, col, int(card_id))


def links(card_id: int, section: int) -> list[dict[str, Any]]:
    """The chapter's connections: id, direction, the other end's book, chapter and words, and whether it is still there."""
    visible = []
    for row in _rows(card_id, section):
        there = row["target"]
        try:
            snapshot = _resolve(there["card_id"])
            sections = snapshot["sections"]
            available = 0 <= there["section"] < len(sections)
            title = snapshot["title"]
            chapter = str(sections[there["section"]].get("title") or "") if available else ""
        except ValueError:
            title, chapter, available = "", "", False  # the app says so in its own words
        visible.append(
            {
                "id": row["id"],
                "direction": row["direction"],
                "title": title[:240],
                "section": there["section"],
                "chapter": chapter[:240],
                # The words at the other end when the link has them, otherwise the words it was made from.
                "quote": (there.get("passage") or {}).get("quote") or row["quote"],
                "available": available,
            }
        )
    return visible


def target(card_id: int, section: int, link_id: int) -> dict[str, Any]:
    """Where a connection opens: the other end's book, chapter, place and passage. ``ValueError`` when it is gone."""
    from incremento.backend.epub_reference import validate_location

    row = next((row for row in _rows(card_id, section) if row["id"] == int(link_id)), None)
    if row is None:
        raise ValueError("Connection unavailable")
    there = row["target"]
    snapshot = _resolve(there["card_id"])
    validate_location(snapshot, there)
    return {
        "card_id": snapshot["card_id"],
        "section": there["section"],
        "offset": there["offset"],
        "scroll": there["scroll"],
        "passage": there.get("passage") or {},
    }


def remove(card_id: int, section: int, link_id: int) -> bool:
    from incremento.backend.epub_connections import remove_connection

    addon_dir, profile = _context()
    return bool(remove_connection(addon_dir, profile, int(card_id), int(section), int(link_id)))
