"""The Storage Access Framework folder as the sync engine's Store (adapter over the Kotlin ``SafExchange``)."""

from __future__ import annotations

import pytest

from saf_fake import FakeSafExchange
from test_engine_sync import Device, _card


@pytest.fixture
def saf():
    return FakeSafExchange()


@pytest.fixture
def store(rt, saf):
    from ankrimento.safstore import KotlinStore

    return KotlinStore(saf, clock=lambda: 1_800_000_000.0)


def test_listing_reports_files_folders_sizes_and_times(rt, saf, store):
    saf.write("lib/devices/a/device.json", b"{}")
    saf.write("lib/library.json", b"12345")

    entries = {entry.name: entry for entry in store.listdir("lib")}

    assert entries["devices"].is_dir and not entries["library.json"].is_dir
    assert entries["library.json"].size == 5
    assert entries["library.json"].mtime == saf.modified["lib/library.json"] / 1000
    assert store.listdir("nothing/here") == []


def test_stat_and_read_of_a_missing_file_are_none_and_a_folder_is_a_folder(rt, saf, store):
    saf.write("a/b/file", b"x")

    assert store.stat("a/missing") is None and store.read("a/missing", 10) is None
    assert store.stat("a/b").is_dir
    assert store.read("a/b/file", 10) == b"x"


def test_a_file_larger_than_the_limit_is_refused_not_truncated(rt, saf, store):
    from incremento.backend.sync_store import StoreError

    saf.write("big", b"x" * 100)

    with pytest.raises(StoreError):
        store.read("big", 10)


def test_write_upload_download_and_delete_round_trip(rt, saf, store, tmp_path):
    source = tmp_path / "source.bin"
    source.write_bytes(b"payload")

    store.write("dir/one", b"1")
    store.upload(source, "dir/two")
    store.download("dir/two", tmp_path / "copy.bin", 100)
    store.delete("dir/one")
    store.delete("dir/never-existed")

    assert (tmp_path / "copy.bin").read_bytes() == b"payload"
    assert sorted(saf.files) == ["dir/two"]


@pytest.mark.parametrize("rel", ["/abs", "a/../b", "a//b", "a" + chr(92) + "b", "a/./b", "x\x00y"])
def test_unsafe_paths_are_refused_before_reaching_the_provider(rt, saf, store, rel):
    from incremento.backend.sync_store import StoreError

    with pytest.raises(StoreError):
        store.write(rel, b"x")
    with pytest.raises(StoreError):
        store.read(rel, 10)

    assert saf.calls == []


def test_writing_the_root_is_refused(rt, saf, store):
    from incremento.backend.sync_store import StoreError

    with pytest.raises(StoreError):
        store.write("", b"x")


def test_a_failure_in_kotlin_becomes_a_store_error(rt, saf, store):
    from incremento.backend.sync_store import StoreError

    def boom(rel, data):
        raise RuntimeError("provider went away")

    saf.write = boom

    with pytest.raises(StoreError, match="provider went away"):
        store.write("a", b"x")


def test_a_file_whose_provider_gives_no_time_counts_as_new_so_it_is_not_swept(rt, saf, store):
    saf.reports_modified = False
    saf.write("lib/blobs/aa/" + "a" * 64, b"x")

    [entry] = store.listdir("lib/blobs/aa")

    assert entry.mtime == 1_800_000_000.0  # the clock: "just now", never "since 1970"
    assert store.stat("lib/blobs/aa/" + "a" * 64).mtime == 1_800_000_000.0


def test_two_devices_sync_through_the_saf_adapter(rt, saf, store, tmp_path):
    from incremento.backend import db, sync_service
    from incremento.backend.profile_startup import prepare_storage

    phone = Device(rt.col, str(rt.addon_dir), "Phone saf")
    desktop = Device(rt.col, str(tmp_path / "desktop" / "addons21" / "incremento"), "Desktop saf")
    for device in (phone, desktop):
        prepare_storage(device.addon, device.profile)
        sync_service.configure(device.addon, device.profile, "saf:test", query=rt.col.db.all, store=store)
    card = _card(rt)

    desktop.set_priority(card, 42.0)
    desktop.sync(store)
    phone.sync(store)
    phone.set_priority(card, 7.0)
    phone.sync(store)
    desktop.sync(store)

    assert phone.priority(card) == desktop.priority(card) == 7.0
    assert any(path.endswith("/library.json") for path in saf.files)
    db.close_connection()
