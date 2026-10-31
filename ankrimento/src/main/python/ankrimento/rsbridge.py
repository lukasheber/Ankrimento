"""Stand-in for Anki's native ``anki._rsbridge`` that forwards to a host.

Python's ``anki`` library talks to the Rust backend only through
``_rsbridge``. On Android there is no native ``_rsbridge``; instead the host
(AnkiDroid's already-open Rust backend, reached through Kotlin) answers the
same calls. Both sides are built from the same Anki release, so service and
method indices agree.

A host exposes two methods. Each returns ``(ok_bytes, err_bytes)`` with
exactly one set, where ``err_bytes`` is a serialized
``anki.backend_pb2.BackendError``::

    command(service: int, method: int, data: bytes)
    db_command(data: bytes)
"""

from __future__ import annotations

import sys
import types
from typing import Any, Protocol


class Host(Protocol):
    def command(self, service: int, method: int, data: bytes) -> Any: ...

    def db_command(self, data: bytes) -> Any: ...


# CollectionService.OpenCollection and CloseCollection (anki._backend_generated).
_COLLECTION_LIFECYCLE = {(3, 0), (3, 1)}


class HostBackend:
    """The ``_rsbridge.Backend`` interface on top of a :class:`Host`."""

    def __init__(self, host: Host) -> None:
        self._host = host

    def command(self, service: int, method: int, data: bytes) -> bytes:
        if (int(service), int(method)) in _COLLECTION_LIFECYCLE:
            # The host owns its one open collection. A second Collection
            # (upstream opens throwaway ones, e.g. to validate a backup) would
            # otherwise open over it or close it.
            from anki import backend_pb2

            error = backend_pb2.BackendError(
                kind=backend_pb2.BackendError.INVALID_INPUT,
                message="Opening or closing collections is not available inside the host app.",
            )
            raise Exception(error.SerializeToString())
        return _unpack(self._host.command(int(service), int(method), bytes(data)))

    def db_command(self, data: bytes) -> bytes:
        return _unpack(self._host.db_command(bytes(data)))


def _unpack(result: Any) -> bytes:
    ok, err = result[0], result[1]
    if err is not None:
        # anki._backend parses error.args[0] as BackendError bytes.
        raise Exception(bytes(err))
    if ok is None:
        raise RuntimeError("host returned neither a result nor an error")
    return bytes(ok)


def install(host: Host) -> HostBackend:
    """Register the stand-in as ``anki._rsbridge`` and return its backend.

    Must run before anything imports ``anki._backend``, which checks the
    bridge's build hash at import time.
    """
    if "anki._backend" in sys.modules:
        raise RuntimeError("anki._backend was imported before the host bridge was installed")
    import anki.buildinfo

    backend = HostBackend(host)
    module = types.ModuleType("anki._rsbridge")
    module.Backend = HostBackend  # type: ignore[attr-defined]
    module.buildhash = lambda: anki.buildinfo.buildhash  # type: ignore[attr-defined]
    module.open_backend = lambda _init: backend  # type: ignore[attr-defined]
    module.initialize_logging = lambda _path=None: None  # type: ignore[attr-defined]

    def syncserver() -> None:
        raise RuntimeError("the sync server is not available inside the host app")

    module.syncserver = syncserver  # type: ignore[attr-defined]
    sys.modules["anki._rsbridge"] = module
    # `from anki import _rsbridge` prefers the package attribute, which a native
    # bridge imported earlier (desktop tests) would otherwise still provide.
    import anki

    anki._rsbridge = module  # type: ignore[attr-defined]
    return backend
