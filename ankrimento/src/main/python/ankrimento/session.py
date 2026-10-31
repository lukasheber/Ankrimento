"""Starting Incremento sessions through upstream's own ``learnFunction``.

Desktop starts a session from its Qt scheduler dialog; ``learnFunction``
accepts any dialog with the same small interface. The headless dialog here
reports the saved scheduler settings, which is what the desktop dialog
produces when the user presses OK without changes.
"""

from __future__ import annotations

from typing import Any


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
        return None

    def selected_dialog_profile_name(self) -> str | None:
        # The selected preset names the session deck, as in the desktop dialog.
        from ankrimento import presets

        return presets.selected()


def start(branch_scope: dict | None = None) -> None:
    """Start a session; the host is told to open the reviewer via ``move_to_state``."""
    from incremento.backend.session import learnFunction

    learnFunction(branch_scope=branch_scope, dialog_factory=HeadlessSchedulerDialog)
