"""The sync engine on the phone: shipped beside the vendored add-on, working on the real Anki collection.

Both devices below run the engine's code in this process (the add-on is a singleton), each with its own profile
folder, exactly as desktop's tests do; what is checked here is what is specific to the phone: the overlay is
installed, nothing it needs is missing from the vendored add-on, and the host collection answers the engine's queries.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from conftest import ENGINE_DIR

ENGINE_TOOL = Path(__file__).resolve().parents[1] / "tools"


def _digest(path: Path) -> str:
    import hashlib

    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def test_the_overlay_is_exactly_what_the_manifest_records():
    manifest = json.loads((ENGINE_DIR / "ENGINE.json").read_text(encoding="utf8"))
    shipped = sorted(p.name for p in (ENGINE_DIR / "backend").glob("*.py"))

    assert shipped == sorted(manifest["files"]), "files were added or removed without update_engine.py"
    for name, expected in manifest["files"].items():
        assert _digest(ENGINE_DIR / "backend" / name) == expected, f"{name} was edited by hand"


def test_the_installed_addon_contains_the_engine_beside_upstreams_backend(rt):
    for name in json.loads((ENGINE_DIR / "ENGINE.json").read_text(encoding="utf8"))["files"]:
        assert (rt.addon_dir / "backend" / name).is_file(), name
    assert (rt.addon_dir / "backend" / "session.py").is_file()


def test_the_engine_imports_in_the_phones_runtime(rt):
    from incremento.backend import sync_service, sync_store  # noqa: F401


# --- Two devices through one store, on the real collection ---------------------------------------------------------

@pytest.fixture
def pair(rt, tmp_path):
    """The phone (the runtime's add-on folder, its own profile) and a desktop (a separate add-on folder).

    Both answer card questions from the phone's real Anki collection, as two installs of one collection would.
    """
    from incremento.backend import db, sync_service
    from incremento.backend.profile_startup import prepare_storage
    from incremento.backend.sync_store import MemoryStore

    phone = Device(rt.col, str(rt.addon_dir), "Phone sync")
    desktop = Device(rt.col, str(tmp_path / "desktop" / "addons21" / "incremento"), "Desktop sync")
    for device in (phone, desktop):
        prepare_storage(device.addon, device.profile)
    store = MemoryStore()
    for device in (phone, desktop):
        sync_service.configure(device.addon, device.profile, f"store:{device.profile}", query=rt.col.db.all, store=store)
    yield phone, desktop, store
    db.close_connection()


class Device:
    def __init__(self, col, addon, profile):
        self.col, self.addon, self.profile = col, addon, profile

    def set_priority(self, card_id, value):
        from incremento.backend import db

        with db.get_connection(self.addon, self.profile) as conn:
            conn.execute("INSERT OR REPLACE INTO priorities(card_id, priority) VALUES (?, ?)", (card_id, value))

    def priority(self, card_id):
        from incremento.backend import db

        row = db.get_connection(self.addon, self.profile).execute(
            "SELECT priority FROM priorities WHERE card_id=?", (card_id,)).fetchone()
        return None if row is None else row[0]

    def sync(self, store):
        from incremento.backend import sync_service

        report = sync_service.sync_now(self.addon, self.profile, self.col.db.all, store=store)
        assert report.problems == [] and report.skipped is None, (report.problems, report.skipped)
        return report


def _card(rt):
    note = rt.col.new_note(rt.col.models.by_name("Basic"))
    note["Front"], note["Back"] = "sync front", "sync back"
    rt.col.add_note(note, rt.col.decks.id("Default"))
    return note.card_ids()[0]


def test_a_priority_set_on_the_desktop_reaches_the_phone_and_back(rt, pair):
    phone, desktop, store = pair
    card = _card(rt)

    desktop.set_priority(card, 25.0)
    desktop.sync(store)
    phone.sync(store)
    assert phone.priority(card) == 25.0

    phone.set_priority(card, 60.0)
    phone.sync(store)
    desktop.sync(store)
    assert desktop.priority(card) == 60.0


def _library_of_one_card(rt, phone, store):
    card = _card(rt)
    phone.set_priority(card, 10.0)
    phone.sync(store)


def test_a_device_whose_notes_have_other_guids_cannot_join_the_phones_library(rt, pair, tmp_path):
    from incremento.backend import sync_service
    from incremento.backend.profile_startup import prepare_storage

    phone, _, store = pair
    _library_of_one_card(rt, phone, store)
    stranger = str(tmp_path / "stranger" / "addons21" / "incremento")
    prepare_storage(stranger, "Stranger")

    def other_collection(sql, *args):  # the same card ids, but notes that are someone else's
        return [(c, n, "someone-else", u) for c, n, _guid, u in rt.col.db.all(sql, *args)]             if "JOIN notes" in sql else rt.col.db.all(sql, *args)

    with pytest.raises(sync_service.SyncSetupError, match="another Anki collection"):
        sync_service.configure(stranger, "Stranger", "store:stranger", query=other_collection, store=store)


def test_a_collection_without_the_libraries_cards_is_told_to_sync_with_ankiweb_first(rt, pair, tmp_path):
    from incremento.backend import sync_service
    from incremento.backend.profile_startup import prepare_storage

    phone, _, store = pair
    _library_of_one_card(rt, phone, store)
    empty = str(tmp_path / "empty" / "addons21" / "incremento")
    prepare_storage(empty, "Empty")

    with pytest.raises(sync_service.SyncSetupError, match="AnkiWeb first"):
        sync_service.configure(empty, "Empty", "store:empty",
                               query=lambda sql, *args: [(0, 0)] if "usn, scm FROM col" in sql else [], store=store)
