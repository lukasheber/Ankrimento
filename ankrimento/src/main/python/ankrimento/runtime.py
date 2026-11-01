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
    host_language: str = "en",
) -> Runtime:
    """Bring up Incremento's backend on the host's open collection.

    ``profile`` is the Anki profile name, which selects the same
    ``user_files/<profile>/`` folder desktop uses. ``host_language`` is the
    app's language (a BCP 47 tag), standing in for Anki's on desktop.
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

    from ankrimento import wiring

    wiring.install()
    wiring.recover_interrupted_imports(col)

    _runtime = Runtime(col=col, addon_dir=addon_dir, profile=paths.get_active_profile())
    activate_language(host_language)
    return _runtime


def activate_language(host_language: str) -> str:
    """The language of upstream's messages; mirrors desktop's startup (``_initialize_language``,
    then ``_activate_profile_language``): Incremento's ``ui_language`` setting, or the app's
    language when it is "auto" and upstream has it, otherwise English. Returns the locale."""
    from aqt import mw
    from incremento.backend import language_packs
    from incremento.backend.config_service import load_addon_config
    from incremento.backend.i18n import initialize_language

    rt = current()
    choice = str(load_addon_config(mw.addonManager, "incremento").get("ui_language", "auto") or "auto")
    pack = language_packs.load_pack(str(rt.addon_dir), rt.profile, choice[7:]) if choice.startswith("custom:") else None
    return initialize_language(choice, host_language, custom_pack=pack)


def current() -> Runtime:
    if _runtime is None:
        raise RuntimeError("Ankrimento runtime not started")
    return _runtime
