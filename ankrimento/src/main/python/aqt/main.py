"""The ``mw`` object: Anki desktop's main window, reduced to what Incremento uses."""

from __future__ import annotations

import json
import os
import threading
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Any


class AddonManager:
    """Add-on config storage with Anki desktop's on-disk layout.

    Defaults come from ``<addon>/config.json`` and user changes are stored
    under ``"config"`` in ``<addon>/meta.json``, exactly as desktop does, so
    an add-on folder can move between desktop and mobile unchanged.
    """

    def __init__(self, addons_folder: str) -> None:
        self._addons_folder = addons_folder
        self._lock = threading.RLock()

    def addonsFolder(self, module: str | None = None) -> str:
        if module is None:
            return self._addons_folder
        return os.path.join(self._addons_folder, module)

    def addonFromModule(self, module: str) -> str:
        return module.split(".")[0]

    def addonConfigDefaults(self, addon: str) -> dict[str, Any] | None:
        path = os.path.join(self.addonsFolder(addon), "config.json")
        try:
            with open(path, encoding="utf8") as file:
                return json.load(file)
        except Exception:
            return None

    def addonMeta(self, addon: str) -> dict[str, Any]:
        path = os.path.join(self.addonsFolder(addon), "meta.json")
        try:
            with open(path, encoding="utf8") as file:
                return json.load(file)
        except Exception:
            return {}

    def writeAddonMeta(self, addon: str, meta: dict[str, Any]) -> None:
        path = os.path.join(self.addonsFolder(addon), "meta.json")
        temp_path = f"{path}.tmp"
        with self._lock:
            with open(temp_path, "w", encoding="utf8") as file:
                json.dump(meta, file)
            os.replace(temp_path, path)

    def getConfig(self, module: str) -> dict[str, Any] | None:
        addon = self.addonFromModule(module)
        config = self.addonConfigDefaults(addon)
        if config is None:
            return None
        config.update(self.addonMeta(addon).get("config", {}))
        return config

    def writeConfig(self, module: str, conf: dict[str, Any]) -> None:
        addon = self.addonFromModule(module)
        with self._lock:
            meta = self.addonMeta(addon)
            meta["config"] = conf
            self.writeAddonMeta(addon, meta)


class ProfileManager:
    def __init__(self, name: str = "") -> None:
        self.name = name


class TaskManager:
    """Background work in the shape of ``aqt.taskman.TaskManager``.

    ``run_on_main`` goes through the host's main-thread dispatcher. Without a
    host (desktop tests), closures run inline on the calling thread.
    """

    def __init__(self) -> None:
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="ankrimento")
        self.main_dispatcher: Callable[[Callable[[], None]], None] | None = None

    def run_on_main(self, closure: Callable[[], None]) -> None:
        dispatcher = self.main_dispatcher
        if dispatcher is None:
            closure()
        else:
            dispatcher(closure)

    def run_in_background(
        self,
        task: Callable[..., Any],
        on_done: Callable[[Future], None] | None = None,
        args: dict[str, Any] | None = None,
        uses_collection: bool = True,
    ) -> Future:
        future = self._executor.submit(task, **(args or {}))
        if on_done is not None:
            future.add_done_callback(lambda done: self.run_on_main(lambda: on_done(done)))
        return future

    def with_progress(
        self,
        task: Callable[[], Any],
        on_done: Callable[[Future], None] | None = None,
        parent: Any = None,
        label: str | None = None,
        immediate: bool = False,
        uses_collection: bool = True,
    ) -> Future:
        return self.run_in_background(task, on_done, uses_collection=uses_collection)


class DeckBrowser:
    def __init__(self, window: MainWindow) -> None:
        self._window = window

    def refresh(self) -> None:
        self._window.notify_host("deck_browser_refresh")


class MainWindow:
    def __init__(self) -> None:
        self.col = None
        self.state = "deckBrowser"
        self.addonManager = AddonManager("")
        self.pm = ProfileManager()
        self.taskman = TaskManager()
        self.deckBrowser = DeckBrowser(self)
        self.host_events: Callable[[str, dict[str, Any]], None] | None = None

    def notify_host(self, event: str, **fields: Any) -> None:
        callback = self.host_events
        if callback is not None:
            callback(event, fields)

    def moveToState(self, state: str, *args: Any) -> None:
        old_state = self.state
        self.state = state
        self.notify_host("move_to_state", state=state, old_state=old_state)
        from aqt import gui_hooks

        gui_hooks.state_did_change(state, old_state)

    def reset(self, unused_arg: bool = False) -> None:
        self.notify_host("reset")
