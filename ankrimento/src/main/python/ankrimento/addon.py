"""Installing and importing the unchanged Incremento add-on.

The add-on is installed into ``<addons_dir>/incremento`` with the same layout
as Anki desktop's ``addons21/incremento``: upstream code next to the
profile data in ``user_files/`` and user config in ``meta.json``. Updating
replaces only the shipped code, as Anki's add-on updater does, so profile
data and config survive app updates and stay compatible with desktop.
"""

from __future__ import annotations

import shutil
import sys
import types
from pathlib import Path

ADDON_PACKAGE = "incremento"

# Only the Qt-free parts of the add-on ship to mobile. The Qt UI (frontend/,
# __init__.py), web assets and the browser extension are desktop-only, except
# frontend/epub_dock.py (read, never imported, for its EPUB page script) and frontend/epub_passages.py (the part of
# that script it inserts; plain text, no Qt),
# frontend/search_all_results.py (Search ALL's Qt-free search pipeline),
# frontend/stats_dialog.py and frontend/stats_dashboard.py (read, never imported,
# for their statistics helpers), frontend/stats_progress.py (the statistics'
# period model), frontend/writing_dock.py (read, never imported, for its text
# helpers) and the
# web dock's bridge script.
SHIPPED_ENTRIES = (
    "backend",
    "locales",
    "config.json",
    "manifest.json",
    "frontend/epub_dock.py",
    "frontend/epub_passages.py",
    "frontend/reader_coverage.py",
    "frontend/reader_links.py",
    "frontend/search_all_results.py",
    "frontend/stats_dashboard.py",
    "frontend/stats_dialog.py",
    "frontend/stats_progress.py",
    "frontend/writing_dock.py",
    "web/web_dock_bridge.js",
)

_INSTALL_STAMP = ".ankrimento-install"

# The sync add-on (github.com/lukasheber/incremento-sync) installs beside Incremento, as it does on desktop. Only its
# Qt-free part ships: the engine, the loader that switches it on and off, and its texts.
SYNC_PACKAGE = "incremento_sync"
SYNC_ENTRIES = ("engine", "locales", "loader.py", "patches.py", "host.py", "i18n.py", "manifest.json", "config.json")


def install_addon(source_dir: Path, addons_dir: Path, build_id: str) -> Path:
    """Copy the shipped add-on entries unless ``build_id`` is already installed."""
    return _install(source_dir, addons_dir / ADDON_PACKAGE, SHIPPED_ENTRIES, build_id)


def install_sync_addon(source_dir: Path, addons_dir: Path, build_id: str) -> Path:
    """The sync add-on's shipped entries, installed like Incremento's."""
    return _install(source_dir, addons_dir / SYNC_PACKAGE, SYNC_ENTRIES, build_id)


def _install(source_dir: Path, addon_dir: Path, entries: tuple[str, ...], build_id: str) -> Path:
    stamp = addon_dir / _INSTALL_STAMP
    try:
        if stamp.read_text(encoding="utf8") == build_id:
            return addon_dir
    except OSError:
        pass

    addon_dir.mkdir(parents=True, exist_ok=True)
    for name in entries:
        source = source_dir / name
        target = addon_dir / name
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()
        if source.is_dir():
            shutil.copytree(
                source,
                target,
                ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "AGENTS.md"),
            )
        elif source.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    stamp.write_text(build_id, encoding="utf8")
    return addon_dir


def register_package(addon_dir: Path) -> types.ModuleType:
    """Make ``incremento.backend`` importable without running the Qt ``__init__.py``.

    Upstream modules derive the add-on name from ``__name__``, so importing
    them as ``incremento.backend.*`` keeps config keys and paths identical to
    desktop.
    """
    existing = sys.modules.get(ADDON_PACKAGE)
    if existing is not None:
        if list(getattr(existing, "__path__", [])) != [str(addon_dir)]:
            raise RuntimeError(f"{ADDON_PACKAGE} is already registered from another location")
        return existing
    package = types.ModuleType(ADDON_PACKAGE)
    package.__path__ = [str(addon_dir)]
    package.__file__ = str(addon_dir / "__init__.py")
    package.__package__ = ADDON_PACKAGE
    sys.modules[ADDON_PACKAGE] = package
    return package


def register_sync_package(addon_dir: Path) -> types.ModuleType:
    """Make ``incremento_sync.loader`` importable (its ``__init__.py`` is for desktop Anki and is not shipped)."""
    existing = sys.modules.get(SYNC_PACKAGE)
    if existing is not None:
        return existing
    package = types.ModuleType(SYNC_PACKAGE)
    package.__path__ = [str(addon_dir)]
    package.__file__ = str(addon_dir / "__init__.py")
    package.__package__ = SYNC_PACKAGE
    sys.modules[SYNC_PACKAGE] = package
    return package


def sync_enabled() -> bool:
    loader = sys.modules.get(f"{SYNC_PACKAGE}.loader")
    return bool(loader and loader.is_enabled())


def set_sync_enabled(enabled: bool) -> bool:
    """Switches the sync add-on on or off in the running Incremento; off puts Incremento back as it was.

    Returns whether it is on afterwards. A sync add-on that is not installed (an older build) stays off.
    """
    if SYNC_PACKAGE not in sys.modules:
        return False
    from incremento_sync import loader

    if enabled:
        loader.enable(sys.modules[ADDON_PACKAGE])
    else:
        loader.disable()
    return loader.is_enabled()
