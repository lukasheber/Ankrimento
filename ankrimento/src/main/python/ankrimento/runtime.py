"""Start-up: bridge to the host backend, adopt its collection, load Incremento."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ankrimento import addon, rsbridge


@dataclass
class Runtime:
    col: Any
    addon_dir: Path
    profile: str


_runtime: Runtime | None = None


def start(
    host: rsbridge.Host,
    *,
    collection_path: str,
    addons_dir: str,
    profile: str,
    addon_source_dir: str,
    build_id: str,
    main_dispatcher: Callable[[Callable[[], None]], None] | None = None,
    host_events: Callable[[str, dict[str, Any]], None] | None = None,
) -> Runtime:
    """Bring up Incremento's backend on the host's open collection.

    ``profile`` is the Anki profile name, which selects the same
    ``user_files/<profile>/`` folder desktop uses.
    """
    global _runtime
    if _runtime is not None:
        raise RuntimeError("Ankrimento runtime already started")

    host_backend = rsbridge.install(host)
    from ankrimento.adopted import adopt_collection

    col = adopt_collection(host_backend, collection_path)

    addon_dir = addon.install_addon(Path(addon_source_dir), Path(addons_dir), build_id)

    import aqt

    mw = aqt.mw
    mw.col = col
    mw.addonManager = type(mw.addonManager)(addons_dir)
    mw.pm.name = profile
    mw.taskman.main_dispatcher = main_dispatcher
    mw.host_events = host_events

    addon.register_package(addon_dir)
    from incremento.backend import paths, profile_startup

    paths.set_active_profile(profile)
    profile_startup.prepare_storage(str(addon_dir), paths.get_active_profile())

    _runtime = Runtime(col=col, addon_dir=addon_dir, profile=paths.get_active_profile())
    return _runtime


def current() -> Runtime:
    if _runtime is None:
        raise RuntimeError("Ankrimento runtime not started")
    return _runtime
