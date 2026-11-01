"""Cards extracted from a document, linked back to it as desktop links them.

Desktop collects an extract in its Qt Add Card dock and, when the note is
added, applies Incremento's extract options and context
(``frontend/add_card_dock.py``) and records the PDF source
(``frontend/pdf_dock.py``). Those modules import Qt, so the steps are made
here with the same backend calls, in the same order:

1. topic/item classification tags (``add_card_topic_tags``/``add_card_item_tags``)
2. extract priority on every card of the note (``calculate_extract_priority``)
3. knowledge-tree lineage under the source card (``ensure_extract_lineage_cards_in_tree``)
4. the PDF source row (``add_pdf_card_source``), keyed by page and highlight

Not done on mobile: Incremento's hidden metadata fields, which desktop adds
to the note type. Adding fields is a schema change that forces a one-way
full sync, which would break syncing with desktop.
"""

from __future__ import annotations

import html
import re
from dataclasses import dataclass, field
from typing import Any

from aqt import mw

from ankrimento import runtime

_ADDON_PKG = "incremento"


@dataclass
class Draft:
    """Fields extracted from one document, waiting to become a note."""

    fields: dict[int, str] = field(default_factory=dict)
    page: int = 0
    highlight_id: str = ""


@dataclass
class Queued:
    """An extract set aside while reading, added later with the place it came from."""

    fields: list[str]
    note_type: str
    kind: str
    priority: float
    page: int = 0
    highlight_id: str = ""


_drafts: dict[int, Draft] = {}
_queues: dict[int, list[Queued]] = {}


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON_PKG)


def field_html(payload: dict) -> str:
    """Mirrors ``frontend/pdf_dock.py:_pdf_extract_html``: HTML from bounded text runs only."""
    from incremento.backend.content_safety import external_plain_text_to_anki_html

    text = payload.get("text", "")
    fallback = external_plain_text_to_anki_html(text)
    runs = payload.get("runs")
    if not isinstance(text, str) or not isinstance(runs, list) or len(runs) > 8192 or len(text) > 200_000:
        return fallback
    parts, plain_parts, total_chars = [], [], 0
    for run in runs:
        if not isinstance(run, dict) or not isinstance(run.get("text"), str):
            return fallback
        if not isinstance(run.get("bold", False), bool) or not isinstance(run.get("italic", False), bool):
            return fallback
        piece = run["text"]
        total_chars += len(piece)
        if total_chars > len(text):
            return fallback
        plain_parts.append(piece)
        rendered = external_plain_text_to_anki_html(piece)
        if run.get("italic"):
            rendered = f"<em>{rendered}</em>"
        if run.get("bold"):
            rendered = f"<strong>{rendered}</strong>"
        parts.append(rendered)
    if "".join(plain_parts) != text:
        return fallback
    return "".join(parts)


def add_to_draft(source_card_id: int, index: int, payload: dict, *, page: int, highlight_id: str) -> None:
    draft = _drafts.setdefault(int(source_card_id), Draft())
    draft.fields[int(index)] = field_html(payload)
    draft.page = int(page)
    if highlight_id:
        draft.highlight_id = highlight_id


def add_html_to_draft(source_card_id: int, index: int, html: str, *, page: int, highlight_id: str = "") -> None:
    """A field the reader composed itself (e.g. a video link), stored as given."""
    draft = _drafts.setdefault(int(source_card_id), Draft())
    draft.fields[int(index)] = str(html)
    draft.page = int(page)
    if highlight_id:
        draft.highlight_id = highlight_id


def source_link_on(kind: str) -> bool:
    """Mirrors ``should_add_extract_source_link`` in frontend/add_card_dock.py: the extract source
    links setting, per kind ("pdf", "epub", "web"), on unless turned off."""
    raw = _config().get("extract_source_links", True)
    if isinstance(raw, bool):
        return raw
    return bool(raw.get(kind, True)) if isinstance(raw, dict) else True


def add_with_citation(source_card_id: int, index: int, payload: dict, citation: str, *, page: int, highlight_id: str = "") -> None:
    """A filled field with desktop's link back to its source after it (``fill_dock_field``: text, then
    ``<br>`` and the citation), or the text alone when there is no link."""
    if citation:
        add_html_to_draft(source_card_id, index, field_html(payload) + "<br>" + citation, page=page, highlight_id=highlight_id)
    else:
        add_to_draft(source_card_id, index, payload, page=page, highlight_id=highlight_id)


def discard(source_card_id: int) -> None:
    _drafts.pop(int(source_card_id), None)


def calculate_priority(source_card_id: int, cfg: dict[str, Any]) -> float:
    """Mirrors ``frontend/add_card_dock.py:calculate_extract_priority``."""
    from incremento.backend.priority_manager import get_priority

    lower_is_more_important = bool(cfg.get("priority_lower_is_more_important", True))
    if "extract_priority" in cfg:
        fallback = float(cfg["extract_priority"])
    else:
        fallback = 40.0 if lower_is_more_important else 60.0
    try:
        multiplier = float(cfg.get("extract_priority_multiplier", 0.98 if lower_is_more_important else 1.02))
    except Exception:
        multiplier = 0.98 if lower_is_more_important else 1.02
    multiplier = round(max(0.01, min(10.0, multiplier)), 4)
    rt = runtime.current()
    try:
        source = float(get_priority(str(rt.addon_dir), rt.profile, int(source_card_id)))
    except Exception:
        return max(0.0, min(100.0, fallback))
    return max(0.0, min(100.0, source * multiplier))


def draft(source_card_id: int) -> dict[str, Any]:
    """What the "new card" dialog shows for the current draft."""
    from incremento.backend.config_service import configured_extract_default_type

    col = runtime.current().col
    cfg = _config()
    current = _drafts.get(int(source_card_id), Draft())
    note_types = sorted(nt.name for nt in col.models.all_names_and_ids())
    note_type = str(cfg.get("extract_notetype") or "").strip()
    # Desktop's extract note type setting; without it desktop keeps the Add Card window's last type,
    # which the phone remembers per kind of document.
    configured = note_type in note_types
    if not configured:
        note_type = "Basic" if "Basic" in note_types else note_types[0]
    return {
        "fields": [current.fields.get(index, "") for index in range(2)],
        "page": current.page,
        "note_types": note_types,
        "note_type": note_type,
        "note_type_configured": configured,
        "kind": configured_extract_default_type(cfg),
        "priority": round(calculate_priority(source_card_id, cfg), 2),
    }


def _apply_classification(note, kind: str) -> None:
    """Mirrors ``_activate_exclusive_note_tag_set``: add one tag set, drop the other."""
    from incremento.backend.knowledge_tree import configured_item_tags, configured_topic_tags

    topic_tags, item_tags = configured_topic_tags(), configured_item_tags()
    add, remove = (topic_tags, item_tags) if kind == "topic" else (item_tags, topic_tags)
    lowered_remove = {tag.casefold() for tag in remove}
    tags = [tag for tag in note.tags if tag.casefold() not in lowered_remove]
    for tag in add:
        if tag.casefold() not in {existing.casefold() for existing in tags}:
            tags.append(tag)
    note.tags = tags


def create(source_card_id: int, fields: list[str], note_type: str, kind: str, priority: float) -> int:
    """Add the extract as a note linked to its source; returns the note id."""
    current = _drafts.get(int(source_card_id), Draft())
    note_id = _create(source_card_id, fields, note_type, kind, priority, current.page, current.highlight_id)
    discard(source_card_id)
    return note_id


# ── Cards from scratch ───────────────────────────────────────────────────────
#
# Desktop's Add Card dock outside an extract: the card takes the dock's
# priority (50 unless changed) and its item/topic tags, in the chosen deck,
# with no source and no place in the knowledge tree. Upstream's
# ``create_card_for_node`` is not used: it adds Incremento's metadata fields
# to the note type, a schema change that forces a one-way full sync.

_SCRATCH_PRIORITY = 50.0  # add_card_dock._DEFAULT_SCRATCH_PRIORITY


def scratch_defaults() -> dict[str, Any]:
    from incremento.backend.config_service import configured_extract_default_type
    from incremento.backend.knowledge_tree import (
        available_deck_names,
        available_note_type_names,
        default_deck_name,
        default_note_type_name,
    )

    return {
        "fields": ["", ""],
        "note_types": sorted(available_note_type_names()),
        "note_type": default_note_type_name(None),
        "decks": sorted(available_deck_names()),
        "deck": default_deck_name(None),
        "kind": configured_extract_default_type(_config()),
        "priority": _SCRATCH_PRIORITY,
    }


def create_card(fields: list[str], note_type: str, kind: str, priority: float, deck_name: str) -> int:
    """Adds a card from scratch; returns the note id."""
    from incremento.backend.priority_manager import set_priorities

    rt = runtime.current()
    col = rt.col
    model = col.models.by_name(note_type)
    if model is None:
        raise ValueError(f"note type not found: {note_type}")
    note = col.new_note(model)
    for index, value in enumerate(fields[: len(note.fields)]):
        note.fields[index] = value
    if kind in {"topic", "item"}:
        _apply_classification(note, kind)
    deck_id = col.decks.id(str(deck_name or "").strip() or "Default")
    changes = col.add_note(note, deck_id)
    set_priorities(str(rt.addon_dir), rt.profile, [(int(cid), float(priority)) for cid in note.card_ids()])

    from aqt.operations import on_op_finished

    on_op_finished(mw, changes, None)
    return int(note.id)


# ── The queue ────────────────────────────────────────────────────────────────
#
# The phone's "Add later": each extract keeps its fields, choices and source
# place, and the draft is free for the next selection. Adding the queue makes
# each note exactly as ``create`` would have when it was queued.


def queue(source_card_id: int, fields: list[str], note_type: str, kind: str, priority: float) -> int:
    """Sets the draft aside as chosen; returns how many are waiting."""
    current = _drafts.get(int(source_card_id), Draft())
    waiting = _queues.setdefault(int(source_card_id), [])
    waiting.append(Queued(list(fields), str(note_type), str(kind), float(priority), current.page, current.highlight_id))
    discard(source_card_id)
    return len(waiting)


def queued(source_card_id: int) -> list[dict[str, Any]]:
    return [
        {"fields": q.fields, "note_type": q.note_type, "kind": q.kind, "priority": q.priority, "page": q.page}
        for q in _queues.get(int(source_card_id), [])
    ]


def drop_queued(source_card_id: int, index: int) -> None:
    waiting = _queues.get(int(source_card_id), [])
    if 0 <= int(index) < len(waiting):
        del waiting[int(index)]


def discard_queue(source_card_id: int) -> None:
    _queues.pop(int(source_card_id), None)


def add_queued(source_card_id: int) -> list[int]:
    """Adds every queued extract in order; returns the PDF pages they came from (for the page's marks)."""
    waiting = _queues.pop(int(source_card_id), [])
    pages = []
    for index, q in enumerate(waiting):
        try:
            _create(source_card_id, q.fields, q.note_type, q.kind, q.priority, q.page, q.highlight_id)
        except Exception:
            # Keep what was not added, to be tried again.
            _queues[int(source_card_id)] = waiting[index:]
            raise
        pages.append(q.page)
    return pages


def _create(
    source_card_id: int, fields: list[str], note_type: str, kind: str, priority: float, page: int, highlight_id: str
) -> int:
    from incremento.backend.db import add_pdf_card_source
    from incremento.backend.knowledge_tree import (
        NODE_KIND_ITEM,
        NODE_KIND_TOPIC,
        ensure_extract_lineage_cards_in_tree,
    )
    from incremento.backend.priority_manager import set_priorities

    rt = runtime.current()
    col = rt.col
    addon_dir, profile = str(rt.addon_dir), rt.profile
    source_card = col.get_card(int(source_card_id))
    model = col.models.by_name(note_type)
    if model is None:
        raise ValueError(f"note type not found: {note_type}")

    note = col.new_note(model)
    for index, value in enumerate(fields[: len(note.fields)]):
        note.fields[index] = value
    if kind in {"topic", "item"}:
        _apply_classification(note, kind)
    if _config().get("extract_copy_source_tags", False):
        note.tags = list(dict.fromkeys(list(note.tags) + list(source_card.note().tags)))
    deck_id = source_card.odid or source_card.did
    changes = col.add_note(note, deck_id)

    card_ids = [int(cid) for cid in note.card_ids()]
    set_priorities(addon_dir, profile, [(cid, float(priority)) for cid in card_ids])
    ensure_extract_lineage_cards_in_tree(
        addon_dir,
        profile,
        source_card_id=int(source_card_id),
        created_card_ids=card_ids,
        created_node_kind=NODE_KIND_TOPIC if kind == "topic" else NODE_KIND_ITEM,
    )

    source_note = source_card.note()
    plain = [re.sub(r"<[^>]+>", "", html.unescape(value)).strip()[:120] for value in note.fields[:2]]
    excerpt = " / ".join(part for part in plain if part)[:200]
    if "EPUB_Filename" in source_note:
        from incremento.backend.db import add_epub_card_source

        # For EPUB drafts the "page" is the chapter (section) index.
        add_epub_card_source(addon_dir, profile, int(source_card_id), page, int(note.id), excerpt)
    elif "PDF_Filename" in source_note:
        add_pdf_card_source(
            addon_dir,
            profile,
            int(source_card_id),
            page or 1,
            int(note.id),
            excerpt,
            str(source_note["PDF_Filename"]),
            highlight_id=highlight_id,
        )

    from aqt.operations import on_op_finished

    on_op_finished(mw, changes, None)
    return int(note.id)
