"""Advanced session options: the parts of desktop's scheduler dialog beyond its Basic view.

They are stored where the desktop dialog stores them (``dialog`` in Incremento's
config, written by ``_build_current_dict``) and read by upstream's
``load_scheduler_config``. Only the options shown here are written; every other
dialog setting, and the per-row fields desktop keeps (locks, groups, order),
stay as they are.
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
    from incremento.backend.scheduler_config import load_scheduler_config

    scheduler = load_scheduler_config()
    return {
        "topics_filter": scheduler.topics_filter,
        "items_filter": scheduler.items_filter,
        "tags": [
            {"tag": str(row.get("tag")).strip(), "weight": int(row.get("weight") or 0)}
            for row in tag_rows
            if str(row.get("tag") or "").strip() and row.get("tag") != _no_tags_key()
        ],
        "other_cards": scheduler.include_rest,
        "content_types": [
            {
                "type": kind,
                "enabled": bool(content_rows.get(kind, {}).get("enabled", False)),
                "weight": int(content_rows.get(kind, {}).get("weight") or 0),
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
        rows.append(row)
    return rows
