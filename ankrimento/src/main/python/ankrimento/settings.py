"""The Incremento settings the mobile app shows, read and saved as desktop's Settings dialog does.

Values live in upstream's config. Only the settings listed here can be
changed from the phone, each with its type; ranges, tag syntax and the like
are left to upstream's ``normalize_config``, which runs on every save.
"""

from __future__ import annotations

from typing import Any

from aqt import mw

_ADDON = "incremento"

# key -> type: "bool", "number", "int", "text", or a tuple of allowed choices.
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
    # Focus timer
    "timer_completion_beep": "bool",
}


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON)


def values() -> dict[str, Any]:
    config = _config()
    return {key: config.get(key) for key in SETTINGS}


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
    raise ValueError(f"{key} must be {kind}")


def set_value(key: str, value: Any) -> Any:
    """Saves one setting; returns the value upstream kept after normalizing it."""
    from incremento.backend.config_service import save_addon_config

    config = _config()
    config[key] = _checked(key, value)
    saved = save_addon_config(mw.addonManager, _ADDON, config)
    return saved.get(key)
