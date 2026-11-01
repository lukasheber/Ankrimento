"""Advanced session options: the parts of desktop's scheduler dialog beyond its Basic view.

They are stored where the desktop dialog stores them (``dialog`` in Incremento's
config, written by ``_build_current_dict``) and read by upstream's
``load_scheduler_config``. Only the options shown here are written; every other
dialog setting, and the per-row fields desktop keeps (locks, groups), stay as
they are. Locks and groups only link the desktop dialog's sliders; the
scheduler never reads them.
"""

from __future__ import annotations

import copy
from typing import Any

from aqt import mw

from ankrimento import runtime

_ADDON_PKG = "incremento"
# Desktop's content-type priority rows, in its order.
CONTENT_TYPES = ("pdf", "youtube", "webpage")
_SCOPES = ("session", "daily", "lifetime")
# Desktop's phase funnel (_PHASE_META): each phase with its label and hint.
_PHASES = {
    "content_types": ("session_phase_content_types", "session_phase_content_types_hint"),
    "tags": ("session_phase_tags", "session_phase_tags_hint"),
    "type": ("session_phase_card_type", "session_phase_card_type_hint"),
    "mode": ("session_phase_selection_mode", "session_phase_selection_mode_hint"),
}


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON_PKG)


def _no_tags_key() -> str:
    from incremento.backend.scheduler_config import NO_TAGS_KEY

    return NO_TAGS_KEY


def get() -> dict[str, Any]:
    dialog = _config().get("dialog") or {}
    tag_rows = [row for row in dialog.get("tag_rows") or [] if isinstance(row, dict)]
    content_rows = {
        str(row.get("type") or "").lower(): row for row in dialog.get("content_type_rows") or [] if isinstance(row, dict)
    }
    from incremento.backend.i18n import t
    from incremento.backend.scheduler_config import load_scheduler_config

    scheduler = load_scheduler_config()
    return {
        "phases": [
            {
                "id": phase,
                "label": t(_PHASES[phase][0]),
                "hint": t(_PHASES[phase][1]),
                "enabled": scheduler.phases_enabled.get(phase, True),
            }
            for phase in scheduler.phase_order
        ],
        "priority_order": scheduler.priority_order_enabled,
        "topics_filter": scheduler.topics_filter,
        "items_filter": scheduler.items_filter,
        "tags": [
            {"tag": str(row.get("tag")).strip(), "weight": int(row.get("weight") or 0), "order": _order(row.get("order"))}
            for row in tag_rows
            if str(row.get("tag") or "").strip() and row.get("tag") != _no_tags_key()
        ],
        "other_cards": scheduler.include_rest,
        "content_types": [
            {
                "type": kind,
                "enabled": bool(content_rows.get(kind, {}).get("enabled", False)),
                "weight": int(content_rows.get(kind, {}).get("weight") or 0),
                "order": _order(content_rows.get(kind, {}).get("order")),
            }
            for kind in CONTENT_TYPES
        ],
        "media_outside_tags": scheduler.allow_content_tag_fallback,
        "strict": scheduler.enforce_priority,
        "scope": scheduler.scheduler_scope,
    }


def set_options(changes: dict[str, Any]) -> dict[str, Any]:
    """Writes the given options (any subset of :func:`get`'s keys) and returns them all."""
    from incremento.backend.config_service import save_addon_config

    config = _config()
    dialog = copy.deepcopy(config.get("dialog") or {})
    for key in ("topics_filter", "items_filter"):
        if key in changes:
            query = str(changes[key] or "").strip()
            if query:
                # A search Anki can't run would only fail when the next session starts.
                runtime.current().col.find_cards(query)
            dialog[key] = query
    if "tags" in changes:
        dialog["tag_rows"] = _tag_rows(dialog.get("tag_rows") or [], changes["tags"])
    if "other_cards" in changes or "tags" in changes:
        _set_other_cards(dialog, bool(changes.get("other_cards", dialog.get("no_tags_checked", True))))
    if "content_types" in changes:
        dialog["content_type_rows"] = _content_rows(dialog.get("content_type_rows") or [], changes["content_types"])
    if "media_outside_tags" in changes:
        dialog["allow_content_tag_fallback"] = bool(changes["media_outside_tags"])
    if "strict" in changes:
        dialog["enforce_priority"] = bool(changes["strict"])
    if "scope" in changes:
        scope = str(changes["scope"])
        if scope not in _SCOPES:
            raise ValueError(f"Unknown scheduler scope {scope}")
        dialog["scheduler_scope"] = scope
    if "phase_order" in changes:
        from incremento.backend.scheduler_config import _normalized_phase_order

        dialog["phase_order"] = _normalized_phase_order(changes["phase_order"])
    if "phases_enabled" in changes:
        enabled = dict(dialog.get("phases_enabled") or {})
        enabled.update({str(k): bool(v) for k, v in dict(changes["phases_enabled"]).items() if k in _PHASES})
        dialog["phases_enabled"] = enabled
    if "priority_order" in changes:
        dialog["priority_order_enabled"] = bool(changes["priority_order"])
    if {"tags", "content_types", "priority_order"} & changes.keys():
        dialog["priority_order_entries"] = _priority_order_entries(dialog)
    config["dialog"] = dialog
    save_addon_config(mw.addonManager, _ADDON_PKG, config)
    return get()


def _tag_rows(existing: list, tags: list[dict]) -> list[dict]:
    """The new tag rows, each keeping desktop's other fields from its old row (new ones in group "tags")."""
    old = {str(row.get("tag")): row for row in existing if isinstance(row, dict)}
    rows = []
    for entry in tags:
        tag = str(entry.get("tag") or "").strip()
        if not tag or tag == _no_tags_key() or any(row["tag"] == tag for row in rows):
            continue
        row = dict(old.get(tag) or {"locked": False, "group": "tags", "order": None})
        row["tag"] = tag
        row["weight"] = max(0, min(100, int(entry.get("weight") or 0)))
        if "order" in entry:
            row["order"] = _order(entry.get("order"))
        rows.append(row)
    # Desktop's Other row, if it has one, stays last.
    rows += [row for row in existing if isinstance(row, dict) and row.get("tag") == _no_tags_key()]
    return rows


def _set_other_cards(dialog: dict, include: bool) -> None:
    """Mirrors desktop's Other slider: ``no_tags_checked``, and an Other row's weight when it has one."""
    dialog["no_tags_checked"] = include
    tagged = sum(int(row.get("weight") or 0) for row in dialog.get("tag_rows") or [] if row.get("tag") != _no_tags_key())
    for row in dialog.get("tag_rows") or []:
        if row.get("tag") == _no_tags_key():
            row["weight"] = max(1, 100 - tagged) if include else 0


def _content_rows(existing: list, entries: list[dict]) -> list[dict]:
    old = {str(row.get("type") or "").lower(): row for row in existing if isinstance(row, dict)}
    given = {str(entry.get("type") or "").lower(): entry for entry in entries}
    rows = []
    for kind in CONTENT_TYPES:
        row = dict(old.get(kind) or {"type": kind, "enabled": False, "weight": 0, "order": None})
        if kind in given:
            row["enabled"] = bool(given[kind].get("enabled", False))
            row["weight"] = max(0, min(100, int(given[kind].get("weight") or 0)))
            if "order" in given[kind]:
                row["order"] = _order(given[kind].get("order"))
        rows.append(row)
    return rows


def _order(value: Any) -> int | None:
    """Mirrors the dialog's ``_parse_order_value``: a positive whole number, or none."""
    try:
        order = int(str(value if value is not None else "").strip())
    except ValueError:
        return None
    return order if order > 0 else None


def _priority_order_entries(dialog: dict) -> list[dict]:
    """Mirrors ``_current_priority_order_entries``: the rows' Order values, tags then content types."""
    entries: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for row in dialog.get("tag_rows") or []:
        tag = str(row.get("tag") or "")
        order = _order(row.get("order"))
        if tag == _no_tags_key() or order is None or ("tag", tag.casefold()) in seen:
            continue
        seen.add(("tag", tag.casefold()))
        entries.append({"kind": "tag", "value": tag, "order": order})
    for row in dialog.get("content_type_rows") or []:
        kind = str(row.get("type") or "").lower()
        order = _order(row.get("order"))
        if order is None or ("content_type", kind) in seen:
            continue
        seen.add(("content_type", kind))
        entries.append({"kind": "content_type", "value": kind, "order": order})
    return entries
