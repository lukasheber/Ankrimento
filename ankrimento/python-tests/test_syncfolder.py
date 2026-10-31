"""Two devices syncing Incremento data through one shared folder."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from ankrimento.syncfolder import LeaseHeld, LocalStore, SyncError, SyncFolder
from test_pdf import _MINIMAL_PDF


class _Config:
    """A device's Incremento settings (desktop's meta.json "config")."""

    def __init__(self, values: dict) -> None:
        self.values = dict(values)

    def read(self) -> dict:
        return dict(self.values)

    def write(self, values: dict) -> None:
        self.values = dict(values)


@pytest.fixture
def devices(rt, tmp_path):
    """Device A is the test runtime's profile; device B a second, empty one."""
    from aqt import mw
    from incremento.backend.profile_startup import prepare_storage

    folder = LocalStore(tmp_path / "Google Drive" / "Incremento" / rt.profile)
    addon_b = tmp_path / "phone" / "addons21" / "incremento"
    prepare_storage(str(addon_b), rt.profile)
    config_a = _Config(mw.addonManager.getConfig("incremento"))
    config_b = _Config({})
    a = SyncFolder(folder, rt.addon_dir, rt.profile, read_config=config_a.read,
                   write_config=config_a.write, device_name="Desktop")
    b = SyncFolder(folder, addon_b, rt.profile, read_config=config_b.read,
                   write_config=config_b.write, device_name="Phone")
    yield a, b, config_b
    for device in (a, b):
        device._state_path.unlink(missing_ok=True)


def _priority(addon_dir, profile, card_id):
    from incremento.backend.priority_manager import get_priority

    return get_priority(str(addon_dir), profile, card_id)


def test_first_device_pushes_and_an_empty_device_pulls(rt, devices, tmp_path):
    a, b, config_b = devices
    source = tmp_path / "Synced.pdf"
    source.write_bytes(_MINIMAL_PDF)
    card_id = facade.import_pdf(str(source), "Synced", priority=33.0)
    pdf_rel = (lambda p: p.relative_to(a.user_files).as_posix())(
        __import__("pathlib").Path(facade.pdf_path(card_id)))

    assert a.status().state == "push"
    a.push()
    assert a.status().state == "in_sync"
    assert (a.remote.root / "files" / pdf_rel).read_bytes() == _MINIMAL_PDF

    assert b.status().state == "pull"
    b.pull()
    assert (b.user_files / pdf_rel).read_bytes() == _MINIMAL_PDF
    assert _priority(b.addon_dir, b.profile, card_id) == 33.0
    assert config_b.values == a.read_config()
    assert b.status().state == "in_sync"


def test_changes_flow_back_and_conflicts_are_not_overwritten(rt, devices):
    from incremento.backend.priority_manager import set_priority

    a, b, _ = devices
    a.push()
    b.pull()

    set_priority(str(b.addon_dir), b.profile, 4242, 7.0)
    assert b.status().state == "push"
    b.push()
    assert a.status().state == "pull"
    a.pull()
    assert _priority(a.addon_dir, a.profile, 4242) == 7.0

    set_priority(str(a.addon_dir), a.profile, 4242, 8.0)
    set_priority(str(b.addon_dir), b.profile, 4242, 9.0)
    a.push()
    assert b.status().state == "conflict"
    with pytest.raises(SyncError):
        b.pull()
    assert _priority(b.addon_dir, b.profile, 4242) == 9.0

    b.pull(force=True)  # keep the folder's version
    assert _priority(b.addon_dir, b.profile, 4242) == 8.0
    assert list((b.user_files / "sync-conflicts").iterdir())  # local copy kept


def test_a_snapshot_still_uploading_is_not_pulled(rt, devices):
    a, b, _ = devices
    a.push()
    snapshot = json.loads((a.remote.root / "snapshot.json").read_text())
    snapshot["snapshot_id"] = "newer"
    snapshot["files"]["not-yet-uploaded.pdf"] = {"size": 10, "sha256": "0" * 64}
    (a.remote.root / "snapshot.json").write_text(json.dumps(snapshot))
    assert b.status().state == "remote_incomplete"


def test_a_half_written_snapshot_record_is_not_mistaken_for_none(rt, devices):
    """Some stores overwrite in place; a torn snapshot.json must not look like an empty folder."""
    a, b, _ = devices
    a.push()
    text = (a.remote.root / "snapshot.json").read_text()
    (a.remote.root / "snapshot.json").write_text(text[: len(text) // 2])
    assert b.status().state == "remote_incomplete"
    with pytest.raises(SyncError):
        b.push(force=True)


def test_lease_warns_other_devices(rt, devices):
    from incremento.backend.priority_manager import set_priority

    a, b, _ = devices
    a.push()
    b.pull()
    a.acquire_lease()
    assert b.status().lease_holder == "Desktop"
    set_priority(str(b.addon_dir), b.profile, 4243, 1.0)
    with pytest.raises(LeaseHeld):
        b.push()
    a.release_lease()
    b.push()


def test_push_waits_for_pending_imports(rt, devices):
    from incremento.backend.operation_journal import ImportOperation

    a, _, _ = devices
    with ImportOperation(str(a.addon_dir), a.profile, "pdf"):
        with pytest.raises(SyncError, match="pending"):
            a.push()


def test_facade_sync_holds_and_releases_the_lease(rt, tmp_path):
    store = LocalStore(tmp_path / "folder" / rt.profile)
    try:
        assert json.loads(facade.sync(store, "Phone", "status"))["state"] == "push"
        assert json.loads(facade.sync(store, "Phone", "sync"))["state"] == "push"
        assert json.loads((store.root / "lease.json").read_text())["device_name"] == "Phone"
        assert json.loads(facade.sync(store, "Phone", "status"))["state"] == "in_sync"
        facade.sync(store, "Phone", "release")
        assert not (store.root / "lease.json").exists()
    finally:
        (rt.addon_dir / "user_files" / rt.profile / ".ankrimento-sync.json").unlink(missing_ok=True)


def test_a_device_without_settings_does_not_erase_anothers(rt, devices):
    """A fresh phone has no Incremento settings yet ({}); pushing must not wipe desktop's."""
    from incremento.backend.priority_manager import set_priority

    a, b, config_b = devices
    desktop_settings = a.read_config()
    assert desktop_settings
    set_priority(str(b.addon_dir), b.profile, 5151, 3.0)
    b.push()
    a.pull(force=True)
    assert a.read_config() == desktop_settings
    assert config_b.values == {}
