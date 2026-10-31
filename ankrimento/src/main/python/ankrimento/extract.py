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


_drafts: dict[int, Draft] = {}


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


def add_html_to_draft(source_card_id: int, index: int, html: str, *, page: int) -> None:
    """A field the reader composed itself (e.g. a video link), stored as given."""
    draft = _drafts.setdefault(int(source_card_id), Draft())
    draft.fields[int(index)] = str(html)
    draft.page = int(page)


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
    if note_type not in note_types:
        note_type = "Basic" if "Basic" in note_types else note_types[0]
    return {
        "fields": [current.fields.get(index, "") for index in range(2)],
        "page": current.page,
        "note_types": note_types,
        "note_type": note_type,
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
    current = _drafts.get(int(source_card_id), Draft())
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
        add_epub_card_source(addon_dir, profile, int(source_card_id), current.page, int(note.id), excerpt)
    elif "PDF_Filename" in source_note:
        add_pdf_card_source(
            addon_dir,
            profile,
            int(source_card_id),
            current.page or 1,
            int(note.id),
            excerpt,
            str(source_note["PDF_Filename"]),
            highlight_id=current.highlight_id,
        )

    from aqt.operations import on_op_finished

    on_op_finished(mw, changes, None)
    discard(source_card_id)
    return int(note.id)
