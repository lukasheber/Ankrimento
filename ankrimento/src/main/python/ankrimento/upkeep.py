"""Desktop's upkeep on the phone: the support bundle and card format updates.

Desktop's Tools menu (``__init__.py``, Qt) builds the bundle with
``backend/diagnostics.build_support_bundle`` and updates Incremento's note types
with ``backend/note_type_updates``; both are called here as desktop calls them.
Desktop's clean-up of unused files and rows is its own menu's logic, not the
backend's, and stays on desktop.
"""

from __future__ import annotations

import copy
import json
import platform
from typing import Any

from aqt import mw

from ankrimento import runtime


def support_bundle(path: str) -> dict[str, int]:
    """Mirrors ``exportSupportBundleFunction``: redacted settings and recent diagnostics, as a ZIP at ``path``."""
    from incremento.backend import diagnostics
    from incremento.backend.config_service import load_addon_config

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    config = copy.deepcopy(load_addon_config(mw.addonManager, "incremento"))
    try:
        default_config = json.loads((rt.addon_dir / "config.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        default_config = {}
    environment = {"platform": "android", "python": platform.python_version()}
    runtime_state = {"ui_state": str(getattr(mw, "state", "unknown") or "unknown")}
    return diagnostics.build_support_bundle(
        path,
        addon_dir=addon_dir,
        profile=profile,
        recorder=diagnostics.DiagnosticRecorder(addon_dir, profile),
        config=config,
        default_config=default_config,
        environment=environment,
        runtime_state=runtime_state,
    )


def card_format_updates() -> list[dict[str, Any]]:
    """Incremento note types whose fields or templates are older than this version's (nothing is changed)."""
    from incremento.backend.note_type_updates import detect_incremento_note_type_updates

    return [
        {"note_type": update.note_type, "changes": list(update.changes)}
        for update in detect_incremento_note_type_updates(runtime.current().col)
    ]


def apply_card_format_updates() -> list[str]:
    """Applies the updates still pending, as desktop does after consent; returns the note types changed."""
    from incremento.backend.note_type_updates import apply_incremento_note_type_updates, detect_incremento_note_type_updates

    col = runtime.current().col
    return list(apply_incremento_note_type_updates(col, detect_incremento_note_type_updates(col)))
