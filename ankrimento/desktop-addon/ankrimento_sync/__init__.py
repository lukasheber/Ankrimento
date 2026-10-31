"""Ankrimento Sync: Incremento's data, synced between computers and phones.

AnkiWeb syncs cards, but Incremento keeps its own data (priorities, topic
A-factors, reading positions, highlights, knowledge tree, managed PDFs and
other files) in its add-on folder. This add-on syncs that data through a
folder kept in step by Google Drive, Dropbox, iCloud, OneDrive or Syncthing,
in the format Ankrimento (Incremento on Android) uses.

It syncs when a profile opens, after every AnkiWeb sync, and when the
profile closes. The format and the rules live in ``syncfolder.py``, shared
with Ankrimento.
"""

from __future__ import annotations

import socket
from pathlib import Path

from aqt import gui_hooks, mw
from aqt.qt import QAction, QFileDialog, QMenu, QMessageBox
from aqt.utils import showWarning, tooltip

from . import syncfolder

_ADDON = __name__


def _config() -> dict:
    return mw.addonManager.getConfig(_ADDON) or {}


def incremento_package() -> str | None:
    """The add-on folder holding Incremento (AnkiWeb installs use a number)."""
    fingerprint = ("backend/db.py", "backend/topic_scheduler.py", "frontend/pdf_dock.py")
    for name in mw.addonManager.allAddons():
        folder = Path(mw.addonManager.addonsFolder(name))
        if all((folder / part).is_file() for part in fingerprint):
            return name
    return None


def _sync_folder() -> syncfolder.SyncFolder | None:
    root = str(_config().get("sync_folder") or "").strip()
    package = incremento_package()
    if not root or package is None or mw.pm.name is None:
        return None
    syncfolder.BACKEND_PACKAGE = f"{package}.backend"
    profile = syncfolder.backend("paths").sanitize_profile_name(mw.pm.name)
    manager = mw.addonManager
    return syncfolder.SyncFolder(
        syncfolder.LocalStore(Path(root) / profile),
        manager.addonsFolder(package),
        profile,
        read_config=lambda: dict(manager.addonMeta(package).get("config", {})),
        write_config=lambda config: manager.writeConfig(package, config),
        device_name=str(_config().get("device_name") or "").strip() or socket.gethostname(),
    )


def _report(status: syncfolder.Status, *, quiet: bool) -> None:
    messages = {
        "pull": f"Incremento data updated from {status.remote_device}",
        "push": "Incremento data saved to the sync folder",
        "remote_incomplete": "The sync folder is still receiving another device's changes; Incremento data will sync later.",
    }
    message = messages.get(status.state) or ("" if quiet else "Incremento data is up to date")
    if status.lease_holder:
        message = f"{message}\nIncremento is open on {status.lease_holder}".strip()
    if message:
        tooltip(message, period=5000)


def _resolve_conflict(folder: syncfolder.SyncFolder, remote_device: str) -> None:
    box = QMessageBox(mw)
    box.setWindowTitle("Incremento data changed in two places")
    box.setText(
        f"Incremento data changed on this computer and on {remote_device} since they last synced. "
        "Which version should be kept? The other is saved in a sync-conflicts folder."
    )
    keep_here = box.addButton("Keep this computer's", QMessageBox.ButtonRole.AcceptRole)
    keep_other = box.addButton("Keep the other device's", QMessageBox.ButtonRole.DestructiveRole)
    box.addButton(QMessageBox.StandardButton.Cancel)
    box.exec()
    if box.clickedButton() is keep_here:
        folder.push(force=True)
    elif box.clickedButton() is keep_other:
        folder.pull(force=True)
    else:
        return
    folder.acquire_lease()
    tooltip("Incremento data synced")


def sync(*, quiet: bool = True) -> None:
    """Pull or push as needed in the background, then hold the lease."""
    folder = _sync_folder()
    if folder is None:
        if not quiet:
            showWarning("Choose an Incremento sync folder first (Tools → Ankrimento Sync).")
        return

    def work() -> syncfolder.Status:
        status = folder.sync()
        if status.state in {"pull", "push", "in_sync"}:
            folder.acquire_lease()
        return status

    def done(future) -> None:
        try:
            status = future.result()
        except syncfolder.SyncError as error:
            tooltip(str(error), period=6000)
            return
        except Exception as error:  # noqa: BLE001 - report every failure to the user
            showWarning(f"Syncing Incremento data failed: {error}")
            return
        if status.state == "conflict":
            _resolve_conflict(folder, status.remote_device)
        else:
            _report(status, quiet=quiet)

    mw.taskman.run_in_background(work, done, uses_collection=False)


def _on_profile_will_close() -> None:
    folder = _sync_folder()
    if folder is None:
        return
    try:
        if folder.status().state == "push":
            folder.push(force=True)
        folder.release_lease()
    except Exception as error:  # noqa: BLE001 - never block closing the profile
        print(f"[Ankrimento Sync] sync on close failed: {error}")


def _choose_folder() -> None:
    current = str(_config().get("sync_folder") or "")
    chosen = QFileDialog.getExistingDirectory(mw, "Incremento sync folder (inside Google Drive, Dropbox…)", current)
    if not chosen:
        return
    config = _config()
    config["sync_folder"] = chosen
    mw.addonManager.writeConfig(_ADDON, config)
    sync(quiet=False)


def _setup_menu() -> None:
    menu = QMenu("Ankrimento Sync", mw)
    choose = QAction("Choose Incremento sync folder…", mw)
    choose.triggered.connect(_choose_folder)
    now = QAction("Sync Incremento data now", mw)
    now.triggered.connect(lambda: sync(quiet=False))
    menu.addAction(choose)
    menu.addAction(now)
    mw.form.menuTools.addMenu(menu)


_setup_menu()
gui_hooks.profile_did_open.append(lambda: sync(quiet=True))
gui_hooks.sync_did_finish.append(lambda: sync(quiet=True))
gui_hooks.profile_will_close.append(_on_profile_will_close)
