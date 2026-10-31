"""A ``RustBackend`` that adopts a collection the host app already has open."""

from __future__ import annotations

from typing import Any

from anki._backend import RustBackend
from anki.collection import Collection

from ankrimento.rsbridge import HostBackend


class AdoptedBackend(RustBackend):
    """Shares the host's backend instance instead of opening a second one.

    One backend means one collection handle and one undo queue, so an
    Incremento change made from Python and a review answered in AnkiDroid
    land in the same undo history.
    """

    def __init__(self, host_backend: HostBackend) -> None:
        # RustBackend.__init__ would create a new native backend; the host's
        # backend is already initialized with the user's languages.
        self._backend = host_backend

    def open_collection(self, **_kwargs: Any) -> Any:
        # The host opened the collection; Collection.reopen() calls this.
        return None

    def close_collection(self, *_args: Any, **_kwargs: Any) -> Any:
        # The host owns the collection lifecycle; closing from Python would
        # pull the database out from under AnkiDroid.
        return None


def adopt_collection(host_backend: HostBackend, collection_path: str) -> Collection:
    return Collection(collection_path, backend=AdoptedBackend(host_backend))
