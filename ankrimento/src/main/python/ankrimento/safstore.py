"""A Storage Access Framework folder as the sync engine's ``Store``.

``SafExchange`` (Kotlin) does the document-provider calls and hands back plain values: JSON text for listings, bytes
for files, null for what is missing. This adapter keeps the engine's side of the contract: unsafe paths are refused
before any provider call, a bound on every read, and every Kotlin failure becomes a ``StoreError``.

Android-free (no ``java`` import), so desktop tests drive it with a fake. The engine is imported lazily because it only
exists once the runtime has installed the add-on.
"""

from __future__ import annotations

import json
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any


def _engine() -> Any:
    from incremento.backend import sync_store

    return sync_store


class KotlinStore:
    def __init__(self, exchange: Any, clock: Callable[[], float] = time.time) -> None:
        self._exchange = exchange
        self._clock = clock

    def _call(self, rel: str, name: str, *args: Any) -> Any:
        try:
            return getattr(self._exchange, name)(*args)
        except Exception as error:  # a Java exception arrives as a Python one
            raise _engine().StoreError(f"{name} {rel!r}: {error}") from error

    def _entry(self, info: dict[str, Any]) -> Any:
        modified = info.get("modified", -1)
        # A provider that gives no time must not make a new file look ancient: the blob sweep deletes by age.
        mtime = self._clock() if modified is None or modified < 0 else modified / 1000
        return _engine().Entry(str(info["name"]), bool(info["dir"]), int(info["size"]), mtime, False)

    def listdir(self, rel: str) -> list[Any]:
        _engine().clean(rel)
        text = self._call(rel, "list", rel)
        return [self._entry(info) for info in json.loads(str(text))]

    def stat(self, rel: str) -> Any | None:
        _engine().clean(rel)
        text = self._call(rel, "stat", rel)
        return None if text is None else self._entry(json.loads(str(text)))

    def read(self, rel: str, limit: int) -> bytes | None:
        _engine().clean(rel)
        data = self._call(rel, "read", rel, limit)
        if data is None:
            return None
        data = bytes(data)
        if len(data) > limit:
            raise _engine().StoreError(f"{rel} is larger than {limit} bytes")
        return data

    def _file(self, rel: str) -> None:
        if not _engine().clean(rel):
            raise _engine().StoreError(f"unsafe path {rel!r}")

    def write(self, rel: str, data: bytes) -> None:
        self._file(rel)
        self._call(rel, "write", rel, bytes(data))

    def upload(self, local: Path, rel: str) -> None:
        self._file(rel)
        self._call(rel, "upload", str(local), rel)

    def download(self, rel: str, local: Path, limit: int) -> None:
        self._file(rel)
        Path(local).parent.mkdir(parents=True, exist_ok=True)
        self._call(rel, "download", rel, str(local), limit)

    def delete(self, rel: str) -> None:
        self._file(rel)
        self._call(rel, "delete", rel)
