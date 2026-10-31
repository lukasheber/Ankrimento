"""Session presets: desktop's named scheduler settings (``scheduler_presets``).

Desktop manages them in its scheduler dialog (``frontend/learn_dialog.py``,
which imports Qt); the dialog's preset handling is mirrored here, each
function naming its desktop original. The ``dialog`` settings are the current
ones; ``dialog.selected_profile`` names the preset they were loaded from,
which also names the session deck (``Incremento Session (<preset>)``).

One addition: desktop lists every session deck, but the device hides them
behind its Incremento card, so a preset's session deck follows a rename and
goes (its cards back home) when the preset is deleted.
"""

from __future__ import annotations

import copy
from typing import Any

from aqt import mw

from ankrimento import runtime

_ADDON_PKG = "incremento"


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, _ADDON_PKG)


def _save(config: dict[str, Any]) -> None:
    from incremento.backend.config_service import save_addon_config

    save_addon_config(mw.addonManager, _ADDON_PKG, config)


def _with_presets(config: dict[str, Any], presets: dict[str, dict]) -> None:
    # Desktop writes both names: "profiles" is the old one, kept in step.
    config["scheduler_presets"] = presets
    config["profiles"] = copy.deepcopy(presets)


def selected() -> str | None:
    """The preset the current settings came from; mirrors ``_normalize_selected_scheduler_profile``."""
    config = _config()
    name = str((config.get("dialog") or {}).get("selected_profile") or "").strip()
    return name if name and name in (config.get("scheduler_presets") or {}) else None


def listing() -> dict[str, Any]:
    return {"selected": selected(), "names": sorted(_config().get("scheduler_presets") or {})}


def _current_settings(config: dict[str, Any]) -> dict:
    """Mirrors ``_build_current_dict(include_selected_profile=False)``: the current settings as a preset."""
    data = copy.deepcopy(config.get("dialog") or {})
    data.pop("selected_profile", None)
    return data


def load(name: str | None) -> None:
    """Mirrors ``_load_profile`` (and choosing "Current settings", which keeps the values)."""
    config = _config()
    dialog = dict(config.get("dialog") or {})
    presets = config.get("scheduler_presets") or {}
    if name is None:
        dialog.pop("selected_profile", None)
    else:
        if name not in presets:
            raise ValueError(f"No session preset named {name}")
        # As _initial_scheduler_dialog_state: the preset over the current settings.
        dialog.update(copy.deepcopy(presets[name]))
        dialog["selected_profile"] = name
    config["dialog"] = dialog
    _save(config)


def add(name: str) -> None:
    """Mirrors ``_add_profile``: a new preset from the current settings, then selected."""
    name = name.strip()
    config = _config()
    presets = dict(config.get("scheduler_presets") or {})
    if not name:
        raise ValueError("A session preset needs a name")
    if name in presets:
        raise ValueError(f"A session preset named {name} already exists")
    presets[name] = _current_settings(config)
    _with_presets(config, presets)
    config["dialog"] = {**(config.get("dialog") or {}), "selected_profile": name}
    _save(config)


def save() -> None:
    """Mirrors ``_save_profile``: the selected preset takes the current settings."""
    name = selected()
    if name is None:
        raise ValueError("No session preset is selected")
    config = _config()
    presets = dict(config.get("scheduler_presets") or {})
    presets[name] = _current_settings(config)
    _with_presets(config, presets)
    _save(config)


def rename(old_name: str, new_name: str) -> None:
    """Mirrors ``_rename_profile`` / ``_rename_named_scheduler_profile``, keeping the order and selection."""
    new_name = new_name.strip()
    config = _config()
    presets = config.get("scheduler_presets") or {}
    if old_name not in presets:
        raise ValueError(f"No session preset named {old_name}")
    if not new_name or new_name == old_name:
        return
    if new_name in presets:
        raise ValueError(f"A session preset named {new_name} already exists")
    _with_presets(config, {(new_name if name == old_name else name): data for name, data in presets.items()})
    dialog = dict(config.get("dialog") or {})
    if dialog.get("selected_profile") == old_name:
        dialog["selected_profile"] = new_name
    config["dialog"] = dialog
    _save(config)
    deck_id = _session_deck_id(old_name)
    if deck_id is not None:
        from incremento.backend.session_selection import incremento_session_deck_name

        runtime.current().col.decks.rename(deck_id, incremento_session_deck_name(new_name))


def delete(name: str) -> None:
    """Mirrors ``_delete_profile``: when it was selected, the current settings stay without a preset."""
    config = _config()
    presets = dict(config.get("scheduler_presets") or {})
    presets.pop(name, None)
    _with_presets(config, presets)
    dialog = dict(config.get("dialog") or {})
    if dialog.get("selected_profile") == name:
        dialog.pop("selected_profile", None)
    config["dialog"] = dialog
    _save(config)
    deck_id = _session_deck_id(name)
    if deck_id is not None:
        col = runtime.current().col
        col.sched.empty_filtered_deck(deck_id)
        col.decks.remove([deck_id])


def _session_deck_id(preset: str) -> int | None:
    from incremento.backend.session_selection import incremento_session_deck_name

    col = runtime.current().col
    deck_id = col.decks.id_for_name(incremento_session_deck_name(preset))
    return deck_id if deck_id and col.decks.is_filtered(deck_id) else None
