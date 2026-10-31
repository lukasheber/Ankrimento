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
# frontend/epub_dock.py (read, never imported, for its EPUB page script) and the
# web dock's bridge script.
SHIPPED_ENTRIES = (
    "backend",
    "locales",
    "config.json",
    "manifest.json",
    "frontend/epub_dock.py",
    "web/web_dock_bridge.js",
)

_INSTALL_STAMP = ".ankrimento-install"


def install_addon(source_dir: Path, addons_dir: Path, build_id: str) -> Path:
    """Copy the shipped add-on entries unless ``build_id`` is already installed."""
    addon_dir = addons_dir / ADDON_PACKAGE
    stamp = addon_dir / _INSTALL_STAMP
    try:
        if stamp.read_text(encoding="utf8") == build_id:
            return addon_dir
    except OSError:
        pass

    addon_dir.mkdir(parents=True, exist_ok=True)
    for name in SHIPPED_ENTRIES:
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
