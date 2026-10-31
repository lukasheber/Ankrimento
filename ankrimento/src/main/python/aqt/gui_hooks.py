"""Hook registry with the ``aqt.gui_hooks`` interface.

Upstream code appends callbacks to hooks such as ``reviewer_did_answer_card``.
Any hook name resolves to a :class:`Hook`, created on first access; the host
app fires the ones it supports through :func:`ankrimento.runtime` calls.
"""

from __future__ import annotations

import traceback
from collections.abc import Callable
from typing import Any


class Hook:
    def __init__(self, name: str) -> None:
        self.name = name
        self._hooks: list[Callable[..., Any]] = []

    def append(self, callback: Callable[..., Any]) -> None:
        self._hooks.append(callback)

    def remove(self, callback: Callable[..., Any]) -> None:
        if callback in self._hooks:
            self._hooks.remove(callback)

    def count(self) -> int:
        return len(self._hooks)

    def __call__(self, *args: Any) -> None:
        for callback in list(self._hooks):
            try:
                callback(*args)
            except Exception:
                # Desktop hooks also isolate failures; one add-on callback
                # must not stop the others.
                traceback.print_exc()


_hooks: dict[str, Hook] = {}


def __getattr__(name: str) -> Hook:
    if name.startswith("__"):
        raise AttributeError(name)
    hook = _hooks.get(name)
    if hook is None:
        hook = _hooks[name] = Hook(name)
    return hook
