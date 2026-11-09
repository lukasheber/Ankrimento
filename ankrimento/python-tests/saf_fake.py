"""A stand-in for the Kotlin ``SafExchange``: the same calls and the same ways of failing, in memory.

Chaquopy hands Python whatever the Kotlin method returns (a String, a ``ByteArray``, null) and turns a thrown
``IOException`` into a Python exception, so the fake returns JSON strings and ``bytes`` and raises ``OSError``.
"""

from __future__ import annotations

import json
from pathlib import Path


class FakeSafExchange:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.modified: dict[str, int] = {}
        self.calls: list[tuple] = []
        self.clock_ms = 1_700_000_000_000
        self.reports_modified = True

    def _tick(self) -> int:
        self.clock_ms += 1000
        return self.clock_ms

    def list(self, rel: str) -> str:
        self.calls.append(("list", rel))
        prefix = rel + "/" if rel else ""
        children: dict[str, dict] = {}
        for path, data in self.files.items():
            if not path.startswith(prefix):
                continue
            name, _, rest = path[len(prefix):].partition("/")
            if rest:
                children.setdefault(name, {"name": name, "dir": True, "size": 0, "modified": -1})
            else:
                children[name] = {"name": name, "dir": False, "size": len(data),
                                  "modified": self.modified[path] if self.reports_modified else -1}
        return json.dumps(list(children.values()))

    def stat(self, rel: str) -> str | None:
        self.calls.append(("stat", rel))
        if rel in self.files:
            return json.dumps({"name": rel.rsplit("/", 1)[-1], "dir": False, "size": len(self.files[rel]),
                               "modified": self.modified[rel] if self.reports_modified else -1})
        if any(path.startswith(rel + "/") for path in self.files):
            return json.dumps({"name": rel.rsplit("/", 1)[-1], "dir": True, "size": 0, "modified": -1})
        return None

    def read(self, rel: str, limit: int) -> bytes | None:
        self.calls.append(("read", rel, limit))
        data = self.files.get(rel)
        if data is not None and len(data) > limit:
            raise OSError(f"{rel} is larger than {limit} bytes")
        return data

    def write(self, rel: str, data: bytes) -> None:
        self.calls.append(("write", rel))
        self.files[rel] = bytes(data)
        self.modified[rel] = self._tick()

    def upload(self, local: str, rel: str) -> None:
        self.write(rel, Path(local).read_bytes())

    def download(self, rel: str, local: str, limit: int) -> None:
        data = self.read(rel, limit)
        if data is None:
            raise OSError(f"{rel} is missing")
        Path(local).write_bytes(data)

    def delete(self, rel: str) -> None:
        self.calls.append(("delete", rel))
        self.files.pop(rel, None)
        self.modified.pop(rel, None)
