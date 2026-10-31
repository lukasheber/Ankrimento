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


class SafStore:
    """Adapts the Kotlin ``SafStore`` (Storage Access Framework) to ``syncfolder.Store``."""

    def __init__(self, kotlin_store: Any) -> None:
        self._store = kotlin_store

    def read_text(self, rel: str) -> str | None:
        text = self._store.readText(rel)
        return None if text is None else str(text)

    def write_text(self, rel: str, text: str) -> None:
        self._store.writeText(rel, text)

    def upload(self, local: Path, rel: str) -> None:
        self._store.upload(str(local), rel)

    def download(self, rel: str, local: Path) -> None:
        self._store.download(rel, str(local))

    def size(self, rel: str) -> int | None:
        size = int(self._store.size(rel))
        return None if size < 0 else size

    def delete(self, rel: str) -> None:
        self._store.delete(rel)


def sync(kotlin_store: Any, device_name: str, action: str) -> str:
    from ankrimento import facade

    return facade.sync(SafStore(kotlin_store), device_name, action)


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


def start(
    kotlin_host: Any,
    collection_path: str,
    addons_dir: str,
    profile: str,
    build_id: str,
) -> None:
    import ankrimento_addon_source

    def dispatch(closure: Callable[[], None]) -> None:
        kotlin_host.runOnMain(_Closure(closure))

    def host_events(event: str, fields: dict[str, Any]) -> None:
        kotlin_host.onEvent(event, json.dumps(fields, default=_encode))

    runtime.start(
        _Host(kotlin_host),
        collection_path=collection_path,
        addons_dir=addons_dir,
        profile=profile,
        addon_source_dir=str(Path(ankrimento_addon_source.__file__).parent / "incremento"),
        build_id=build_id,
        main_dispatcher=dispatch,
        host_events=host_events,
    )
