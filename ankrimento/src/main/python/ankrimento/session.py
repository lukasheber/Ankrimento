"""Starting Incremento sessions through upstream's own ``learnFunction``.

Desktop starts a session from its Qt scheduler dialog; ``learnFunction``
accepts any dialog with the same small interface. The headless dialog here
reports the saved scheduler settings, which is what the desktop dialog
produces when the user presses OK without changes.

The dialog's live preview (``frontend/learn_dialog.py``) runs upstream's
``select_session_cards`` with those settings; while they stay the same, the
session uses exactly the previewed cards (``get_preview_override``). The
phone's preview does the same.
"""

from __future__ import annotations

import copy
import json
from typing import Any

# The last preview and the settings it was made with (the dialog's _live_preview_cache and signature).
_preview: dict | None = None
_preview_signature: str | None = None


class HeadlessSchedulerDialog:
    """``SchedulerConfigDialog``'s interface, answering with saved settings."""

    def __init__(self, parent: Any, on_clear_session: Any = None, branch_scope: dict | None = None) -> None:
        self.branch_scope = branch_scope

    def exec(self) -> bool:
        return True

    def save_config(self, *, save_selected_profile: bool = False) -> None:
        # Nothing was edited; the saved settings stay as they are.
        pass

    def to_config(self) -> Any:
        from incremento.backend.scheduler_config import load_scheduler_config

        return load_scheduler_config()

    def get_preview_override(self) -> dict | None:
        # Mirrors _live_preview_cache_is_current: only a preview of these settings (not of a branch).
        if self.branch_scope or _preview is None or _preview_signature != _signature():
            return None
        return copy.deepcopy(_preview)

    def selected_dialog_profile_name(self) -> str | None:
        # The selected preset names the session deck, as in the desktop dialog.
        from ankrimento import presets

        return presets.selected()


def start(branch_scope: dict | None = None) -> None:
    """Start a session; the host is told to open the reviewer via ``move_to_state``."""
    from incremento.backend.session import learnFunction

    learnFunction(branch_scope=branch_scope, dialog_factory=HeadlessSchedulerDialog)
    # As the desktop dialog's cache goes with the dialog, a preview serves one session.
    forget_preview()


def forget_preview() -> None:
    global _preview, _preview_signature
    _preview, _preview_signature = None, None


def _signature() -> str:
    """The settings a session is chosen with: the dialog's saved settings and the selected preset."""
    from aqt import mw
    from incremento.backend.config_service import load_addon_config

    from ankrimento import presets

    dialog = load_addon_config(mw.addonManager, "incremento").get("dialog", {})
    return json.dumps({"dialog": dialog, "preset": presets.selected()}, sort_keys=True, default=str)


def _type_label(value: object) -> str:
    """Mirrors ``session_type_label`` in frontend/learn_dialog.py."""
    from incremento.backend.i18n import t

    key = str(value or "?")
    message_id = {
        "topics": "session_topics", "topic": "session_topics",
        "items": "session_items", "item": "session_items",
        "video": "ui_type_video", "youtube": "ui_type_video",
        "webpage": "ui_type_webpage", "web": "ui_type_webpage",
        "writing": "ui_type_writing", "local_file": "ui_type_local_file",
        "other": "session_other",
    }.get(key)
    return t(message_id) if message_id else key.upper() if key in {"pdf", "epub"} else key


def _mode_label(value: object) -> str:
    """Mirrors ``session_mode_label``."""
    from incremento.backend.i18n import t

    key = str(value or "?")
    message_id = {"priority": "session_priority", "random": "session_random"}.get(key)
    return t(message_id) if message_id else key


def _tag_label(value: object) -> str:
    """Mirrors ``_preview_tag_label``."""
    from incremento.backend.i18n import t
    from incremento.backend.scheduler_config import NO_TAGS_KEY

    if value == NO_TAGS_KEY or not value:
        return t("session_other") if value == NO_TAGS_KEY else t("admin_session_no_tag")
    return str(value)


def preview() -> dict[str, Any]:
    """The cards the next session would get, as the dialog's live preview picks them; kept for the session."""
    global _preview, _preview_signature
    from aqt import mw
    from incremento.backend.config_service import load_addon_config
    from incremento.backend.knowledge_tree import _card_title
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.session_selection import incremento_session_deck_name, select_session_cards
    from incremento.backend.topic_scheduler import resolve_topic_card_classifier

    from ankrimento import presets, runtime

    rt = runtime.current()
    cfg = load_scheduler_config()
    result = select_session_cards(
        cfg,
        str(rt.addon_dir),
        col=rt.col,
        profile=rt.profile,
        topic_classifier=resolve_topic_card_classifier(copy.deepcopy(load_addon_config(mw.addonManager, "incremento")), scheduler_config=cfg),
        session_deck_name=incremento_session_deck_name(presets.selected()),
    )
    # Mirrors _session_preview_data.
    _preview = {
        "selected_ids": list(result.selected_ids),
        "picked_meta": copy.deepcopy(result.picked_meta),
        "session_counts": copy.deepcopy(result.stats.session),
        "session_time": copy.deepcopy(result.stats.session_time),
        "picker_snapshot": copy.deepcopy(result.picker_snapshot),
    }
    _preview_signature = _signature()
    entries = []
    for card_id in result.selected_ids:
        meta = result.picked_meta.get(card_id, {})
        entries.append(
            {
                "card_id": int(card_id),
                "title": _card_title(rt.col.get_card(card_id)),
                "type": _type_label(meta.get("card_type")),
                "mode": _mode_label(meta.get("mode")),
                "tag": _tag_label(meta.get("tag")),
            }
        )
    return {"count": len(entries), "target": int(cfg.session_card_count), "entries": entries}
