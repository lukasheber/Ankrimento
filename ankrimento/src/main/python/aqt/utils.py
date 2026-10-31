"""User messages from ``aqt.utils``, forwarded to the host app."""

from __future__ import annotations

from typing import Any


def _notify(kind: str, text: Any) -> None:
    from aqt import mw

    if mw.host_events is None:
        print(f"[{kind}] {text}")
    else:
        mw.notify_host("message", kind=kind, text=str(text))


def showInfo(text: Any, *args: Any, **kwargs: Any) -> None:
    _notify("info", text)


def showWarning(text: Any, *args: Any, **kwargs: Any) -> None:
    _notify("warning", text)


def tooltip(msg: Any, *args: Any, **kwargs: Any) -> None:
    _notify("tooltip", msg)
