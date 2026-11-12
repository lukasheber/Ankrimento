"""Switching the sync add-on off on the phone puts Incremento back as it was; switching it on restores sync."""

from __future__ import annotations

import importlib
import sys

import pytest

from ankrimento import facade


@pytest.fixture
def sync_on(rt):
    assert facade.sync_addon_enabled()
    yield
    facade.set_sync_addon_enabled(True)  # whatever a test did, later tests need it on


def _skip():
    return importlib.import_module("incremento.backend.export_bundle").should_skip_user_file


def test_the_sync_addon_is_on_after_start(rt, sync_on):
    assert "incremento.backend.sync_service" in sys.modules or importlib.import_module("incremento.backend.sync_service")
    assert _skip()("sync/blobs/ab/cd") is True


def test_off_removes_the_engine_and_restores_incrementos_backup_rule(rt, sync_on):
    assert facade.set_sync_addon_enabled(False) is False

    assert not facade.sync_addon_enabled()
    assert not [name for name in sys.modules if name.startswith("incremento.backend.sync_")]
    assert _skip()("sync/blobs/ab/cd") is False
    assert _skip()("bookshelf_cover_cache/x") is True  # Incremento's own rules are untouched


def test_syncing_while_it_is_off_says_so_instead_of_failing_obscurely(rt, sync_on):
    facade.set_sync_addon_enabled(False)

    with pytest.raises(RuntimeError, match="switched off"):
        facade.engine_sync(object(), "saf:x")


def test_on_again_brings_the_engine_and_the_rule_back(rt, sync_on):
    facade.set_sync_addon_enabled(False)

    assert facade.set_sync_addon_enabled(True) is True
    assert importlib.import_module("incremento.backend.sync_service")
    assert _skip()("sync/blobs/ab/cd") is True


def test_the_sync_data_survives_switching_off(rt, sync_on):
    from incremento.backend import db
    from incremento.backend.sync_schema import ensure_schema

    conn = db.get_connection(str(rt.addon_dir), rt.profile)
    ensure_schema(conn)
    facade.set_sync_addon_enabled(False)
    facade.set_sync_addon_enabled(True)

    conn = db.get_connection(str(rt.addon_dir), rt.profile)
    assert conn.execute("SELECT count(*) FROM sync_meta").fetchone()[0] >= 1


def test_the_phone_pairs_with_the_six_digits_a_hub_shows(rt, sync_on, tmp_path):
    from incremento.backend import sync_http

    book = sync_http.KeyBook(tmp_path / "keys.json")
    hub = sync_http.Hub(tmp_path / "Hub", book.as_mapping(), host="127.0.0.1", port=0, key_book=book)
    hub.start()
    try:
        pin = hub.open_pairing()
        code = facade.pair_hub(f"{pin[:3]} {pin[3:]} 127.0.0.1:{hub.port}", "SM-X123")

        pairing = sync_http.Pairing.from_code(code)
        assert (pairing.identity, pairing.host, pairing.port) == ("SM-X123", "127.0.0.1", hub.port)
        assert book.devices()["SM-X123"] == pairing.key
    finally:
        hub.stop()


def test_pairing_while_the_sync_addon_is_off_says_so(rt, sync_on):
    facade.set_sync_addon_enabled(False)

    with pytest.raises(RuntimeError, match="switched off"):
        facade.pair_hub("123456", "tablet")
