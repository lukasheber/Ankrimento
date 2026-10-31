"""Syncing Incremento's profile data through a shared folder.

Anki syncs cards through AnkiWeb, but Incremento keeps its own state in
``user_files/<profile>/`` (``incremento.db``: priorities, A-factors, reading
positions, highlights, knowledge tree; plus the managed PDFs, EPUBs and
other files) and its settings in the add-on config. This module syncs that
state between devices through any folder a sync service keeps in step
(Google Drive, Dropbox, iCloud, OneDrive, Syncthing...).

It depends only on upstream Incremento backend modules, so a desktop add-on
can run the same code and both sides share one implementation of the format.

Folder layout, per profile (``<root>/<profile>/``)::

    snapshot.json   the commit record, written last (see below)
    incremento.db   consistent copy (upstream's snapshot_database)
    config.json     Incremento's user settings
    files/<path>    managed files from user_files/<profile>/<path>
    lease.json      which device has the profile open

``snapshot.json`` lists the database and every file with size and sha256.
Sync services upload files one by one, so a reader accepts a snapshot only
once everything it lists has arrived.

Each device remembers the snapshot it last synced with (its *base*). If the
folder holds a newer snapshot and the device changed nothing since its base,
it pulls; if only the device changed, it pushes; if both changed, that is a
conflict and nothing is overwritten.

Pushing is refused while an import is pending, so a half-made import never
reaches another device (whose import recovery would roll back its files).
"""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import shutil
import sqlite3
import tempfile
import time
import uuid
from collections.abc import Callable
from contextlib import closing
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

#: Python package of Incremento's backend. Desktop installs from AnkiWeb live
#: in a numbered add-on folder; the desktop add-on sets this accordingly.
BACKEND_PACKAGE = "incremento.backend"


def backend(module: str) -> Any:
    """An upstream backend module, e.g. ``backend("paths")``."""
    return importlib.import_module(f"{BACKEND_PACKAGE}.{module}")


FORMAT = 1
SNAPSHOT_NAME = "snapshot.json"
LEASE_NAME = "lease.json"
DB_NAME = "incremento.db"
CONFIG_NAME = "config.json"
FILES_DIR = "files"
LOCAL_STATE_NAME = ".ankrimento-sync.json"
LEASE_SECONDS = 10 * 60

# Never synced (names from backend/paths.py and backend/migration.py): the
# live database (synced as a snapshot instead), per-device state, browser
# profiles, and derived copies each device rebuilds.
_SKIPPED_TOP_LEVEL = {
    "incremento.db",
    "incremento.db-wal",
    "incremento.db-shm",
    LOCAL_STATE_NAME,
    ".incremento_profile_migration.json",
    "extraction_draft.json",
    "db_checkpoints",
    "diagnostics",
    "pdf_annotations",
    "search_profile",
    "video_profile",
    "web_profile",
    "sync-conflicts",
}


class SyncError(RuntimeError):
    pass


class LeaseHeld(SyncError):
    """Another device has the profile open."""


class RemoteIncomplete(SyncError):
    """The sync service is still delivering the folder's latest snapshot."""


@dataclass
class Status:
    """What a sync would do.

    ``state`` is one of ``"in_sync"``, ``"push"``, ``"pull"``, ``"conflict"``
    and ``"remote_incomplete"`` (the sync service is still uploading).
    """

    state: str
    remote_device: str = ""
    lease_holder: str = ""


ConfigReader = Callable[[], dict[str, Any]]
ConfigWriter = Callable[[dict[str, Any]], None]


class Store(Protocol):
    """The profile's folder in the sync service; paths are ``/``-separated and relative."""

    def read_text(self, rel: str) -> str | None: ...

    def write_text(self, rel: str, text: str) -> None: ...

    def upload(self, local: Path, rel: str) -> None: ...

    def download(self, rel: str, local: Path) -> None: ...

    def size(self, rel: str) -> int | None: ...

    def delete(self, rel: str) -> None: ...


class LocalStore:
    """A plain folder (desktop sync clients, Syncthing, tests)."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)

    def _path(self, rel: str) -> Path:
        return self.root.joinpath(*rel.split("/"))

    def read_text(self, rel: str) -> str | None:
        try:
            return self._path(rel).read_text(encoding="utf8")
        except OSError:
            return None

    def write_text(self, rel: str, text: str) -> None:
        path = self._path(rel)
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        temp.write_text(text, encoding="utf8")
        os.replace(temp, path)

    def upload(self, local: Path, rel: str) -> None:
        _copy_atomic(local, self._path(rel))

    def download(self, rel: str, local: Path) -> None:
        shutil.copyfile(self._path(rel), local)

    def size(self, rel: str) -> int | None:
        path = self._path(rel)
        return path.stat().st_size if path.is_file() else None

    def delete(self, rel: str) -> None:
        self._path(rel).unlink(missing_ok=True)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for chunk in iter(lambda: file.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json_sha256(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode("utf8")).hexdigest()


def _read_json(path: Path) -> Any:
    try:
        with open(path, encoding="utf8") as file:
            return json.load(file)
    except (OSError, ValueError):
        return None


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    with open(temp, "w", encoding="utf8") as file:
        json.dump(value, file, indent=1, sort_keys=True)
    os.replace(temp, path)


def _copy_atomic(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.tmp")
    shutil.copyfile(source, temp)
    os.replace(temp, destination)


class SyncFolder:
    def __init__(
        self,
        remote: Store,
        addon_dir: str | Path,
        profile: str,
        *,
        read_config: ConfigReader,
        write_config: ConfigWriter,
        device_name: str,
    ) -> None:
        """``remote`` is the profile's folder: ``<sync root>/<profile>``."""
        paths = backend("paths")
        get_user_files_dir, sanitize_profile_name = paths.get_user_files_dir, paths.sanitize_profile_name

        self.profile = sanitize_profile_name(profile)
        self.addon_dir = Path(addon_dir)
        self.user_files = get_user_files_dir(str(self.addon_dir), self.profile)
        self.remote = remote
        self.read_config = read_config
        self.write_config = write_config
        self.device_name = device_name
        self._state_path = self.user_files / LOCAL_STATE_NAME

    # ── Local state ──────────────────────────────────────────────────────

    def _state(self) -> dict[str, Any]:
        state = _read_json(self._state_path)
        if not isinstance(state, dict):
            state = {}
        if not state.get("device_id"):
            state["device_id"] = uuid.uuid4().hex
            self.user_files.mkdir(parents=True, exist_ok=True)
            _write_json(self._state_path, state)
        return state

    @property
    def device_id(self) -> str:
        return str(self._state()["device_id"])

    def _local_files(self, known: dict[str, Any]) -> dict[str, dict[str, Any]]:
        """Synced files under user_files, reusing known hashes for unchanged files."""
        should_skip_user_file = backend("export_bundle").should_skip_user_file

        files: dict[str, dict[str, Any]] = {}
        if not self.user_files.is_dir():
            return files
        for directory, dirnames, filenames in os.walk(self.user_files):
            rel_dir = Path(directory).relative_to(self.user_files).as_posix()
            rel_dir = "" if rel_dir == "." else rel_dir
            if not rel_dir:
                dirnames[:] = [d for d in dirnames if d not in _SKIPPED_TOP_LEVEL]
            dirnames[:] = [d for d in dirnames if not should_skip_user_file(f"{rel_dir}/{d}".lstrip("/"))]
            for name in filenames:
                rel = f"{rel_dir}/{name}" if rel_dir else name
                if not rel_dir and name in _SKIPPED_TOP_LEVEL:
                    continue
                if name.startswith(".") and name.endswith(".tmp"):
                    continue
                if should_skip_user_file(rel):
                    continue
                path = self.user_files / rel
                if path.is_symlink() or not path.is_file():
                    continue
                stat = path.stat()
                previous = known.get(rel) or {}
                if previous.get("size") == stat.st_size and previous.get("mtime_ns") == stat.st_mtime_ns:
                    sha = previous["sha256"]
                else:
                    sha = _sha256(path)
                files[rel] = {"size": stat.st_size, "mtime_ns": stat.st_mtime_ns, "sha256": sha}
        return files

    def _snapshot_db(self, destination: Path) -> dict[str, Any]:
        snapshot_database = backend("export_bundle").snapshot_database

        info = snapshot_database(self.user_files / DB_NAME, destination)
        return {"size": destination.stat().st_size, "sha256": _sha256(destination), **info}

    def _local_changes(self, state: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
        """Whether anything changed since the base snapshot, and the current fingerprints."""
        with tempfile.TemporaryDirectory(dir=self.user_files, prefix=".sync-") as temp:
            db = self._snapshot_db(Path(temp) / DB_NAME)
        files = self._local_files(state.get("files") or {})
        config_sha = _json_sha256(self.read_config())
        current = {"db_sha256": db["sha256"], "files": files, "config_sha256": config_sha}
        if not state.get("snapshot_id"):
            return not self._is_empty_profile(), current
        changed = (
            db["sha256"] != state.get("db_sha256")
            or config_sha != state.get("config_sha256")
            or {rel: f["sha256"] for rel, f in files.items()}
            != {rel: f["sha256"] for rel, f in (state.get("files") or {}).items()}
        )
        return changed, current

    def _is_empty_profile(self) -> bool:
        """A fresh profile with no Incremento content yet; safe to replace on first pull."""
        try:
            with closing(sqlite3.connect((self.user_files / DB_NAME).resolve().as_uri() + "?mode=ro", uri=True)) as db:
                content = db.execute("SELECT COUNT(*) FROM content_items").fetchone()[0]
                priorities = db.execute("SELECT COUNT(*) FROM priorities").fetchone()[0]
        except sqlite3.Error:
            return True
        return content == 0 and priorities == 0 and not self._local_files({})

    # ── Remote ───────────────────────────────────────────────────────────

    def _read_remote_json(self, rel: str) -> Any:
        text = self.remote.read_text(rel)
        if text is None:
            return None
        try:
            return json.loads(text)
        except ValueError:
            return None

    def _write_remote_json(self, rel: str, value: Any) -> None:
        self.remote.write_text(rel, json.dumps(value, indent=1, sort_keys=True))

    def _remote_snapshot(self) -> dict[str, Any] | None:
        """The folder's snapshot record, or None when the folder has none yet."""
        text = self.remote.read_text(SNAPSHOT_NAME)
        if text is None:
            return None
        try:
            snapshot = json.loads(text)
        except ValueError:
            snapshot = None
        if not isinstance(snapshot, dict) or "snapshot_id" not in snapshot:
            # Present but unreadable: being written. Treating it as absent
            # would let this device push over a newer snapshot.
            raise RemoteIncomplete("The sync folder is being updated; try again later.")
        if snapshot.get("format") != FORMAT:
            raise SyncError(f"Sync folder format {snapshot.get('format')} is not supported; update Ankrimento.")
        return snapshot

    def _remote_complete(self, snapshot: dict[str, Any]) -> bool:
        if self.remote.size(DB_NAME) != snapshot["db"]["size"]:
            return False
        return all(
            self.remote.size(f"{FILES_DIR}/{rel}") == info["size"] for rel, info in snapshot["files"].items()
        )

    def lease_holder(self) -> str:
        """Name of another device holding an unexpired lease, or ""."""
        lease = self._read_remote_json(LEASE_NAME)
        if not isinstance(lease, dict) or lease.get("device_id") == self.device_id:
            return ""
        if float(lease.get("expires_at", 0)) < time.time():
            return ""
        return str(lease.get("device_name") or "another device")

    def acquire_lease(self) -> None:
        self._write_remote_json(
            LEASE_NAME,
            {"device_id": self.device_id, "device_name": self.device_name, "expires_at": time.time() + LEASE_SECONDS},
        )

    def release_lease(self) -> None:
        lease = self._read_remote_json(LEASE_NAME)
        if isinstance(lease, dict) and lease.get("device_id") == self.device_id:
            self.remote.delete(LEASE_NAME)

    # ── Operations ───────────────────────────────────────────────────────

    def status(self) -> Status:
        state = self._state()
        try:
            snapshot = self._remote_snapshot()
        except RemoteIncomplete:
            return Status("remote_incomplete")
        local_changed, _ = self._local_changes(state)
        if snapshot is None:
            return Status("push")
        remote_device = str(snapshot.get("device_name") or "")
        lease_holder = self.lease_holder()
        if snapshot["snapshot_id"] == state.get("snapshot_id"):
            return Status("push" if local_changed else "in_sync", remote_device, lease_holder)
        if not self._remote_complete(snapshot):
            return Status("remote_incomplete", remote_device, lease_holder)
        return Status("conflict" if local_changed else "pull", remote_device, lease_holder)

    def pull(self, *, force: bool = False) -> None:
        """Replace this device's Incremento state with the folder's snapshot.

        With ``force`` (resolving a conflict in the folder's favour) the
        local database is first kept under ``user_files/<profile>/sync-conflicts``.
        """
        incremento_db = backend("db")
        prepare_storage = backend("profile_startup").prepare_storage

        status = self.status()
        if status.state == "conflict" and not force:
            raise SyncError("This device and the sync folder both changed; choose which to keep.")
        if status.state not in {"pull", "conflict"}:
            raise SyncError(f"Nothing to pull ({status.state}).")
        snapshot = self._remote_snapshot()
        assert snapshot is not None
        supported = incremento_db._SCHEMA_MIGRATIONS[-1][0]
        if int(snapshot.get("db_schema_version", 0)) > supported:
            raise SyncError("The sync folder was written by a newer Incremento; update Ankrimento first.")

        state = self._state()
        staging = Path(tempfile.mkdtemp(dir=self.user_files, prefix=".sync-pull-"))
        try:
            staged_db = staging / DB_NAME
            self.remote.download(DB_NAME, staged_db)
            if _sha256(staged_db) != snapshot["db"]["sha256"]:
                raise SyncError("The sync folder's database is still uploading; try again later.")
            with closing(sqlite3.connect(str(staged_db))) as check:
                if check.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                    raise SyncError("The sync folder's database is damaged.")
            staged_files: dict[str, Path] = {}
            local_files = self._local_files(state.get("files") or {})
            for rel, info in snapshot["files"].items():
                if (local_files.get(rel) or {}).get("sha256") == info["sha256"]:
                    continue
                staged = staging / FILES_DIR / rel
                staged.parent.mkdir(parents=True, exist_ok=True)
                self.remote.download(f"{FILES_DIR}/{rel}", staged)
                if _sha256(staged) != info["sha256"]:
                    raise SyncError(f"{rel} in the sync folder is still uploading; try again later.")
                staged_files[rel] = staged

            if force and status.state == "conflict":
                kept = self.user_files / "sync-conflicts" / time.strftime("%Y%m%d-%H%M%S")
                kept.mkdir(parents=True, exist_ok=True)
                self._snapshot_db(kept / DB_NAME)

            incremento_db.close_connection(str(self.addon_dir), self.profile)
            for suffix in ("-wal", "-shm"):
                (self.user_files / f"{DB_NAME}{suffix}").unlink(missing_ok=True)
            os.replace(staged_db, self.user_files / DB_NAME)
            for rel, staged in staged_files.items():
                (self.user_files / rel).parent.mkdir(parents=True, exist_ok=True)
                os.replace(staged, self.user_files / rel)
            for rel in set(state.get("files") or {}) - set(snapshot["files"]):
                (self.user_files / rel).unlink(missing_ok=True)
            config = self._read_remote_json(CONFIG_NAME)
            if isinstance(config, dict) and config:
                self.write_config(config)
        finally:
            shutil.rmtree(staging, ignore_errors=True)

        prepare_storage(str(self.addon_dir), self.profile)
        self._record_base(snapshot)

    def push(self, *, force: bool = False) -> None:
        """Write this device's Incremento state to the folder as a new snapshot.

        With ``force`` (resolving a conflict in this device's favour) the
        folder's newer snapshot is replaced.
        """
        pending_import_recovery_needed = backend("operation_journal").pending_import_recovery_needed

        if pending_import_recovery_needed(str(self.addon_dir), self.profile):
            raise SyncError("An import is still pending on this device; finish it before syncing.")
        holder = self.lease_holder()
        if holder and not force:
            raise LeaseHeld(f"Incremento is open on {holder}.")
        status = self.status()
        if status.state == "conflict" and not force:
            raise SyncError("This device and the sync folder both changed; choose which to keep.")
        if status.state not in {"push", "conflict"} and not (force and status.state == "pull"):
            raise SyncError(f"Nothing to push ({status.state}).")

        state = self._state()
        previous = self._remote_snapshot()
        with tempfile.TemporaryDirectory(dir=self.user_files, prefix=".sync-push-") as temp:
            db_info = self._snapshot_db(Path(temp) / DB_NAME)
            self.remote.upload(Path(temp) / DB_NAME, DB_NAME)
        files = self._local_files(state.get("files") or {})
        remote_files = (previous or {}).get("files") or {}
        for rel, info in files.items():
            if (remote_files.get(rel) or {}).get("sha256") != info["sha256"]:
                self.remote.upload(self.user_files / rel, f"{FILES_DIR}/{rel}")
        for rel in set(remote_files) - set(files):
            self.remote.delete(f"{FILES_DIR}/{rel}")
        config = self.read_config()
        if config:
            # A device that never saved settings has {}; it must not erase others'.
            self._write_remote_json(CONFIG_NAME, config)

        snapshot = {
            "format": FORMAT,
            "snapshot_id": uuid.uuid4().hex,
            "parent_id": (previous or {}).get("snapshot_id", ""),
            "device_id": self.device_id,
            "device_name": self.device_name,
            "created_at": time.time(),
            "db_schema_version": db_info["schema_version"],
            "db": {"size": db_info["size"], "sha256": db_info["sha256"]},
            "config_sha256": _json_sha256(config),
            "files": {rel: {"size": info["size"], "sha256": info["sha256"]} for rel, info in files.items()},
        }
        self._write_remote_json(SNAPSHOT_NAME, snapshot)
        self._record_base(snapshot, local_files=files, db_sha256=db_info["sha256"])

    def _record_base(
        self,
        snapshot: dict[str, Any],
        *,
        local_files: dict[str, Any] | None = None,
        db_sha256: str | None = None,
    ) -> None:
        state = self._state()
        if local_files is None or db_sha256 is None:
            _, current = self._local_changes({})
            local_files, db_sha256 = current["files"], current["db_sha256"]
        state.update(
            {
                "snapshot_id": snapshot["snapshot_id"],
                "db_sha256": db_sha256,
                "config_sha256": _json_sha256(self.read_config()),
                "files": local_files,
            }
        )
        _write_json(self._state_path, state)

    def sync(self) -> Status:
        """Pull or push as needed; conflicts and incomplete uploads are left to the caller."""
        status = self.status()
        if status.state == "pull":
            self.pull()
        elif status.state == "push":
            self.push()
        return status
