"""The Incremento settings the mobile app shows, read and saved as desktop's Settings dialog does.

Values live in upstream's config. Only the settings listed here can be
changed from the phone, each with its type; ranges, tag syntax and the like
are left to upstream's ``normalize_config``, which runs on every save.
"""

from __future__ import annotations

from typing import Any

from aqt import mw

_ADDON = "incremento"

# key -> type: "bool", "number", "int", "text", a tuple of allowed choices, or "set": several of a
# list of choices (see ``choices``).
SETTINGS: dict[str, Any] = {
    # Review
    "use_fail_pass_on_items": "bool",
    "topic_more_adjustment_percent": "number",
    "topic_less_adjustment_percent": "number",
    "topic_maximum_interval_days": "int",
    "topic_done_tag": "text",
    "topic_postpone_enabled": "bool",
    "topic_postpone_minutes": "int",
    "learning_leech_enabled": "bool",
    "learning_leech_threshold": "int",
    "learning_leech_tag": "text",
    # Extraction and priority
    "extract_default_type": ("item", "topic"),
    "extract_copy_source_tags": "bool",
    "highlight_when_extracting": "bool",
    "priority_lower_is_more_important": "bool",
    # Reading
    "pdf_default_appearance": ("original", "dark", "night"),
    "pdf_force_default_appearance": "bool",
    "pdf_show_highlight_delete_buttons": "bool",
    "pdf_scroll_to_top_on_page_change": "bool",
    # Writing
    "writing_backups_enabled": "bool",
    "writing_word_count_mode": ("simple", "word_like"),
    "writing_backup_tiers": "set",
    # Focus timer
    "timer_completion_beep": "bool",
}


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON)


def choices(key: str) -> list[str]:
    """A "set" setting's choices, in desktop's order: the writing backup intervals of its writing backups."""
    from incremento.backend import writing_manager

    if key != "writing_backup_tiers":
        raise ValueError(f"{key} has no set of choices")
    return [tier for tier, _seconds, _label in writing_manager._BACKUP_SLOTS]


def values() -> dict[str, Any]:
    from incremento.backend.writing_manager import normalize_writing_backup_tiers

    config = _config()
    found = {key: config.get(key) for key in SETTINGS}
    # Desktop's default intervals when none were chosen, as its writing backups use them.
    found["writing_backup_tiers"] = list(normalize_writing_backup_tiers(config.get("writing_backup_tiers")))
    return found


def _checked(key: str, value: Any) -> Any:
    kind = SETTINGS.get(key)
    if kind is None:
        raise ValueError(f"not a mobile setting: {key}")
    if isinstance(kind, tuple):
        if value not in kind:
            raise ValueError(f"{key} must be one of {kind}")
        return value
    if kind == "bool" and isinstance(value, bool):
        return value
    if kind in ("number", "int") and isinstance(value, (int, float)) and not isinstance(value, bool):
        return int(value) if kind == "int" else float(value)
    if kind == "text" and isinstance(value, str):
        return value
    if kind == "set" and isinstance(value, list) and all(isinstance(item, str) for item in value):
        allowed = choices(key)
        if any(item not in allowed for item in value):
            raise ValueError(f"{key} takes only {allowed}")
        # Desktop's dialog lists the checked boxes in its own order.
        return [item for item in allowed if item in value]
    raise ValueError(f"{key} must be {kind}")


def set_value(key: str, value: Any) -> Any:
    """Saves one setting; returns the value upstream kept after normalizing it."""
    from incremento.backend.config_service import save_addon_config

    config = _config()
    config[key] = _checked(key, value)
    saved = save_addon_config(mw.addonManager, _ADDON, config)
    return saved.get(key)


def review_buttons() -> dict[str, bool]:
    """Desktop's review buttons: the whole group, then Done, Postpone and Extract, shown or hidden."""
    from incremento.backend.config_service import (
        configured_reviewer_button_group_visible,
        configured_reviewer_button_visibility,
    )

    config = _config()
    return {"group": configured_reviewer_button_group_visible(config), **configured_reviewer_button_visibility(config)}


def set_review_button(name: str, visible: bool) -> None:
    """Shows or hides one review button ("done", "postpone", "extract") or the whole group ("group")."""
    from aqt import mw
    from incremento.backend.config_service import save_addon_config

    config = _config()
    if name == "group":
        config["reviewer_button_group_visible"] = bool(visible)
    elif name in ("done", "postpone", "extract"):
        config["reviewer_button_visibility"] = {**(config.get("reviewer_button_visibility") or {}), name: bool(visible)}
    else:
        raise ValueError(f"Unknown review button {name}")
    save_addon_config(mw.addonManager, "incremento", config)
