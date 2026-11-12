"""Entry point for Kotlin, through Chaquopy. Android-only."""

from __future__ import annotations

import base64
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

from java import dynamic_proxy
from java.lang import Runnable

from ankrimento import runtime


class _Host:
    """Adapts the Kotlin ``AnkrimentoHost`` to the ``rsbridge.Host`` protocol."""

    def __init__(self, kotlin_host: Any) -> None:
        self._kotlin_host = kotlin_host

    def command(self, service: int, method: int, data: bytes) -> Any:
        return self._kotlin_host.command(service, method, data)

    def db_command(self, data: bytes) -> Any:
        return self._kotlin_host.dbCommand(data)


def _budget(seconds: float) -> float | None:
    """Kotlin passes a negative number for no limit."""
    return None if seconds < 0 else seconds


def sync_hub(code: str, publish_only: bool = False, budget_seconds: float = -1.0) -> str:
    """Sync Incremento's data through the hub paired with ``code`` (see ``facade.engine_sync_hub``)."""
    from ankrimento import facade

    return facade.engine_sync_hub(code, publish_only, _budget(budget_seconds))


class _Closure(dynamic_proxy(Runnable)):  # type: ignore[misc]
    def __init__(self, closure: Callable[[], None]) -> None:
        super().__init__()
        self._closure = closure

    def run(self) -> None:
        self._closure()


def _encode(value: Any) -> Any:
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    raise TypeError(f"cannot send {type(value).__name__} to the host")


def warm_up() -> None:
    """Import the slow, collection-independent modules ahead of :func:`start`.

    Safe before the host bridge exists: none of these import ``anki._backend``.
    """
    import bs4  # noqa: F401
    import markdown  # noqa: F401
    from anki import backend_pb2, collection_pb2, decks_pb2, scheduler_pb2  # noqa: F401


def start(
    kotlin_host: Any,
    collection_path: str,
    addons_dir: str,
    profile: str,
    build_id: str,
    host_language: str = "en",
    sync_enabled: bool = True,
) -> None:
    import ankrimento_addon_source

    source = Path(ankrimento_addon_source.__file__).parent

    def dispatch(closure: Callable[[], None]) -> None:
        kotlin_host.runOnMain(_Closure(closure))

    def host_events(event: str, fields: dict[str, Any]) -> None:
        kotlin_host.onEvent(event, json.dumps(fields, default=_encode))

    runtime.start(
        _Host(kotlin_host),
        collection_path=collection_path,
        addons_dir=addons_dir,
        profile=profile,
        addon_source_dir=str(source / "incremento"),
        sync_addon_source_dir=str(source / "incremento_sync"),
        sync_enabled=sync_enabled,
        build_id=build_id,
        main_dispatcher=dispatch,
        host_events=host_events,
        host_language=host_language,
    )
