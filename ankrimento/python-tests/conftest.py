"""Desktop harness: a native Anki backend stands in for AnkiDroid's.

``DesktopHost`` owns a real Rust backend with the collection open, the way
AnkiDroid does, and answers the bridge's ``command``/``db_command`` calls.
Everything above it (the bridge, the adopted collection, the ``aqt`` stand-in
and Incremento's unchanged backend) is the code that ships to Android.

The runtime is a process-wide singleton, so one runtime serves the session.
"""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

# Keep a handle on the native bridge before ankrimento.rsbridge replaces it.
import anki._rsbridge as native_rsbridge  # isort: skip
from anki import backend_pb2
from anki._backend_generated import RustBackendGenerated

REPO_ROOT = Path(__file__).resolve().parents[2]
INCREMENTO_SOURCE = REPO_ROOT / "vendor" / "incremento"
ENGINE_DIR = REPO_ROOT / "ankrimento" / "engine"
PROFILE = "User 1"


class _NativeCalls(RustBackendGenerated):
    def __init__(self, native) -> None:
        self._native = native

    def _run_command(self, service: int, method: int, input: bytes) -> bytes:
        return self._native.command(service, method, input)


class DesktopHost:
    def __init__(self, collection_path: Path) -> None:
        init = backend_pb2.BackendInit(preferred_langs=["en"], server=False)
        self._native = native_rsbridge.open_backend(init.SerializeToString())
        base = str(collection_path)[: -len(".anki2")]
        _NativeCalls(self._native).open_collection(
            collection_path=str(collection_path),
            media_folder_path=f"{base}.media",
            media_db_path=f"{base}.media.db",
        )

    def command(self, service: int, method: int, data: bytes):
        return self._call(lambda: self._native.command(service, method, data))

    def db_command(self, data: bytes):
        return self._call(lambda: self._native.db_command(data))

    @staticmethod
    def _call(fn):
        try:
            return fn(), None
        except Exception as error:
            return None, bytes(error.args[0])


def assemble_addon_source(destination: Path) -> Path:
    """The add-on source the app is built from: the vendored release plus the engine overlay.

    Mirrors the ``incrementoAddonSource`` Gradle task: ``engine/backend`` is merged into the add-on's ``backend``.
    """
    from ankrimento.addon import SHIPPED_ENTRIES

    for name in SHIPPED_ENTRIES:
        source = INCREMENTO_SOURCE / name
        if source.is_dir():
            shutil.copytree(source, destination / name, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "AGENTS.md"))
        elif source.exists():
            (destination / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination / name)
    shutil.copytree(ENGINE_DIR / "backend", destination / "backend", dirs_exist_ok=True)
    return destination


@pytest.fixture(scope="session")
def rt(tmp_path_factory):
    from ankrimento import runtime

    root = tmp_path_factory.mktemp("ankrimento")
    collection_path = root / "collection.anki2"
    host = DesktopHost(collection_path)
    return runtime.start(
        host,
        collection_path=str(collection_path),
        addons_dir=str(root / "addons21"),
        profile=PROFILE,
        addon_source_dir=str(assemble_addon_source(root / "addon_source")),
        build_id="test",
    )
