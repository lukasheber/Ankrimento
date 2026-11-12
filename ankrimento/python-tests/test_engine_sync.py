"""The sync add-on on the phone: installed beside the vendored Incremento, working on the real Anki collection.

Both devices below run the engine's code in this process (the add-on is a singleton), each with its own profile
folder, exactly as desktop's tests do; what is checked here is what is specific to the phone: the add-on is
installed and switched on, nothing it needs is missing from the vendored Incremento, and the host collection answers
the engine's queries.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from conftest import SYNC_ADDON


def test_the_sync_addon_is_installed_beside_incremento(rt):
    installed = rt.addon_dir.parent / "incremento_sync"
    for name in (p.name for p in (SYNC_ADDON / "engine").glob("sync_*.py")):
        assert (installed / "engine" / name).is_file(), name
    assert (installed / "loader.py").is_file()
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


# --- The facade the app calls --------------------------------------------------------------------------------------

@pytest.fixture
def phone_profile(rt):
    """The runtime's own profile, as the app syncs it; its sync state is wiped afterwards."""
    import shutil

    from incremento.backend import db, sync_paths, sync_service
    from incremento.backend.sync_schema import SYNC_TABLES, ensure_schema

    yield rt.profile
    # The profile is shared by the whole session: forget the library, the device and the local blobs.
    conn = db.get_connection(str(rt.addon_dir), rt.profile)
    ensure_schema(conn)  # a test that failed early never made the tables
    with conn:
        for table in reversed(SYNC_TABLES):
            conn.execute(f"DELETE FROM {table}")
    ensure_schema(conn)
    shutil.rmtree(sync_paths.get_sync_dir(str(rt.addon_dir), rt.profile), ignore_errors=True)


def test_the_facade_syncs_the_runtimes_profile_with_a_desktop_through_a_store(rt, phone_profile, tmp_path):
    import json

    from ankrimento import facade
    from incremento.backend import db, sync_service
    from incremento.backend.profile_startup import prepare_storage
    from incremento.backend.sync_store import MemoryStore

    store = MemoryStore()
    phone = Device(rt.col, str(rt.addon_dir), rt.profile)
    desktop = Device(rt.col, str(tmp_path / "desktop" / "addons21" / "incremento"), "Desktop facade")
    prepare_storage(desktop.addon, desktop.profile)
    sync_service.configure(desktop.addon, desktop.profile, "store:desktop", query=rt.col.db.all, store=store)
    card = _card(rt)

    desktop.set_priority(card, 33.0)
    desktop.sync(store)
    first = json.loads(facade.engine_sync(store, "saf:content://tree/one"))
    phone.set_priority(card, 80.0)
    second = json.loads(facade.engine_sync(store, "saf:content://tree/one"))
    desktop.sync(store)

    assert first["problems"] == [] and first["applied"] >= 1
    assert phone.priority(card) == 80.0
    assert second["sent"] >= 1 and second["conflicts"] == 0
    assert desktop.priority(card) == 80.0
    db.close_connection()


def test_the_facade_refuses_a_folder_whose_library_is_not_this_profiles(rt, phone_profile, tmp_path):
    from ankrimento import facade
    from incremento.backend import sync_service
    from incremento.backend.profile_startup import prepare_storage
    from incremento.backend.sync_store import MemoryStore

    store = MemoryStore()
    stranger = str(tmp_path / "stranger" / "addons21" / "incremento")
    prepare_storage(stranger, "Stranger")
    card = _card(rt)
    Device(rt.col, stranger, "Stranger").set_priority(card, 1.0)
    sync_service.configure(stranger, "Stranger", "store:s", query=_other_guids(rt), store=store)
    sync_service.sync_now(stranger, "Stranger", _other_guids(rt), store=store)

    # Whether the profile is already bound to a library or joins this one, it is not this collection's.
    with pytest.raises(sync_service.SyncSetupError):
        facade.engine_sync(store, "saf:content://tree/two")


def _other_guids(rt):
    def query(sql, *args):  # the same cards under notes that are someone else's
        rows = rt.col.db.all(sql, *args)
        if "JOIN notes" in sql:
            return [(c, n, "someone-else", u) for c, n, _guid, u in rows]
        return rows

    return query


def test_publish_only_sends_this_devices_changes_without_receiving_the_others(rt, phone_profile, tmp_path):
    import json

    from ankrimento import facade
    from incremento.backend import db, sync_service
    from incremento.backend.profile_startup import prepare_storage
    from incremento.backend.sync_store import MemoryStore

    store = MemoryStore()
    phone = Device(rt.col, str(rt.addon_dir), rt.profile)
    desktop = Device(rt.col, str(tmp_path / "desktop" / "addons21" / "incremento"), "Desktop closing")
    prepare_storage(desktop.addon, desktop.profile)
    sync_service.configure(desktop.addon, desktop.profile, "store:desktop", query=rt.col.db.all, store=store)
    facade.engine_sync(store, "saf:content://tree/closing")
    first, second = _card(rt), _card(rt)

    desktop.set_priority(first, 11.0)
    desktop.sync(store)
    phone.set_priority(second, 22.0)
    closing = json.loads(facade.engine_sync(store, "saf:content://tree/closing", publish_only=True))
    desktop.sync(store)

    assert closing["sent"] >= 1 and closing["received"] == 0
    assert phone.priority(first) is None  # not received yet
    assert desktop.priority(second) == 22.0
    db.close_connection()


# --- Syncing through a hub on the network ---------------------------------------------------------------------------

@pytest.fixture
def running_hub(tmp_path):
    """A hub on this machine, paired with the phone; the other device pairs with it too."""
    import ssl

    from incremento.backend.sync_http import Hub, KeyBook

    if ssl.SSLContext.__module__ != "ssl":  # a developer environment that patches ssl (pip-system-certs)
        from pip._vendor import truststore

        truststore.extract_from_ssl()
    book = KeyBook(tmp_path / "keys.json")
    root = tmp_path / "HubFolder"
    root.mkdir()
    hub = Hub(root, book.as_mapping(), host="127.0.0.1", port=0)
    hub.start()
    yield hub, book
    hub.stop()


def test_the_facade_syncs_with_a_desktop_through_a_hub(rt, phone_profile, running_hub, tmp_path):
    import json

    from ankrimento import facade
    from incremento.backend import db, sync_service
    from incremento.backend.profile_startup import prepare_storage
    from incremento.backend.sync_http import HttpStore, Pairing

    hub, book = running_hub
    phone_code = Pairing("127.0.0.1", hub.port, "phone", book.add("phone")).code()
    desktop_pairing = Pairing("127.0.0.1", hub.port, "desktop", book.add("desktop"))
    desktop_store = HttpStore.from_pairing(desktop_pairing)
    phone = Device(rt.col, str(rt.addon_dir), rt.profile)
    desktop = Device(rt.col, str(tmp_path / "desktop" / "addons21" / "incremento"), "Desktop hub")
    prepare_storage(desktop.addon, desktop.profile)
    sync_service.configure(desktop.addon, desktop.profile, "hub:desktop", query=rt.col.db.all, store=desktop_store)
    card = _card(rt)

    desktop.set_priority(card, 44.0)
    desktop.sync(desktop_store)
    first = json.loads(facade.engine_sync_hub(phone_code))
    received = phone.priority(card)
    phone.set_priority(card, 66.0)
    json.loads(facade.engine_sync_hub(phone_code))
    desktop.sync(desktop_store)

    assert first["problems"] == [] and received == 44.0
    assert desktop.priority(card) == 66.0
    db.close_connection()


def test_a_bad_pairing_code_is_a_clear_error(rt, phone_profile):
    from ankrimento import facade

    with pytest.raises(ValueError, match="pairing code"):
        facade.engine_sync_hub("this is not a code")


def test_a_hub_that_is_not_there_is_an_error_the_app_can_show(rt, phone_profile):
    from ankrimento import facade
    from incremento.backend import sync_service
    from incremento.backend.sync_http import Pairing, new_key

    unreachable = Pairing("127.0.0.1", 9, "phone", new_key()).code()  # nothing listens on port 9

    with pytest.raises(sync_service.SyncSetupError, match="cannot be read"):
        facade.engine_sync_hub(unreachable)
    # An unreachable hub is not an empty one: the profile did not invent a library for it.
    from ankrimento import runtime

    rt = runtime.current()
    assert sync_service.sync_identity(sync_service.get_connection(str(rt.addon_dir), rt.profile)) is None


# --- A large first sync in slices -----------------------------------------------------------------------------------

def test_the_facade_reports_when_a_budget_cut_the_sync_short_and_a_later_call_finishes_it(rt, phone_profile, tmp_path):
    import json

    from ankrimento import facade
    from incremento.backend import db, paths, sync_service
    from incremento.backend.sync_store import MemoryStore

    store = MemoryStore()
    cards = [_card(rt) for _ in range(5)]
    for index, card in enumerate(cards):
        pdf = paths.get_pdf_dir(str(rt.addon_dir), rt.profile) / f"slice{index}.pdf"
        pdf.parent.mkdir(parents=True, exist_ok=True)
        pdf.write_bytes(b"%PDF-1.7\n" + f"slice {index} ".encode() * 2000)
        note = rt.col.get_card(card).nid
        with db.get_connection(str(rt.addon_dir), rt.profile) as conn:
            conn.execute("INSERT INTO content_items(content_id, kind, card_id, note_id, storage_key, created_at, "
                         "updated_at) VALUES (?, 'pdf', ?, ?, ?, 1, 1)", (f"{index + 1:032x}", card, note, f"pdfs/slice{index}.pdf"))

    reports = []
    for _ in range(40):
        reports.append(json.loads(facade.engine_sync(store, "saf:content://tree/slices", budget_seconds=0.0)))
        if not reports[-1]["more"]:
            break

    assert len(reports) > 1 and reports[-1]["more"] is False
    assert all(report["problems"] == [] for report in reports)
    assert sum(report["sent"] for report in reports) >= 5
    for index in range(5):
        pdf = paths.get_pdf_dir(str(rt.addon_dir), rt.profile) / f"slice{index}.pdf"
        pdf.unlink(missing_ok=True)
    db.close_connection()


def _managed_file(rt, device, folder, name, data):
    """A local file card with a managed copy in ``device``'s profile, as the add-on there makes it."""
    from incremento.backend.local_file_manager import add_local_file_card

    folder.mkdir(parents=True, exist_ok=True)
    source = folder / name
    source.write_bytes(data)
    card = add_local_file_card(device.addon, device.profile, rt.col, source_path=str(source), title=name, mode="managed_copy")
    managed = Path(device.addon) / "user_files" / device.profile / "files"
    return card, next(path for path in managed.iterdir() if path.name.startswith(Path(name).stem))


def test_a_local_file_added_on_the_phone_reaches_the_desktop(rt, pair, tmp_path):
    phone, desktop, store = pair
    _, kept = _managed_file(rt, phone, tmp_path / "source", "Handout.docx", b"week 3 handout\n" * 40)

    phone.sync(store)
    desktop.sync(store)

    received = Path(desktop.addon) / "user_files" / desktop.profile / "files" / kept.name
    assert received.read_bytes() == b"week 3 handout\n" * 40


def test_a_local_file_added_on_the_desktop_reaches_the_phone(rt, pair, tmp_path):
    phone, desktop, store = pair
    _, kept = _managed_file(rt, desktop, tmp_path / "source", "Slides.pdf", b"%PDF-1.4 desktop slides")

    desktop.sync(store)
    phone.sync(store)

    received = Path(phone.addon) / "user_files" / phone.profile / "files" / kept.name
    assert received.read_bytes() == b"%PDF-1.4 desktop slides"


def test_a_changed_local_file_replaces_the_old_one_on_the_other_device(rt, pair, tmp_path):
    phone, desktop, store = pair
    _, kept = _managed_file(rt, desktop, tmp_path / "source", "Notes.txt", b"first version")
    desktop.sync(store)
    phone.sync(store)

    kept.write_bytes(b"second version")
    desktop.sync(store)
    phone.sync(store)

    received = Path(phone.addon) / "user_files" / phone.profile / "files" / kept.name
    assert received.read_bytes() == b"second version"


def test_links_between_documents_travel_both_ways(rt, pair, tmp_path):
    from incremento.backend import db
    from incremento.backend.document_links import add_document_link, list_document_links
    from incremento.backend.epub_connections import add_connection, list_connections

    phone, desktop, store = pair
    source, target = _card(rt), _card(rt)

    add_document_link(desktop.addon, desktop.profile, source_card_id=source, source_kind="pdf", source_page=2,
                      target_card_id=target, target_page=5, target_scroll=0.5, target_passage={"quote": "the exact words"})
    add_connection(phone.addon, phone.profile, {"card_id": source, "section": 0, "offset": 1, "scroll": 0.0},
                   {"card_id": target, "section": 1, "offset": 2, "scroll": 0.3, "passage": {"quote": "a place", "start": 0, "end": 7}})

    desktop.sync(store)
    phone.sync(store)
    desktop.sync(store)

    on_phone = list_document_links(phone.addon, phone.profile, source)
    assert [(row["source_page"], row["target_page"]) for row in on_phone] == [(2, 5)]
    assert on_phone[0]["target_passage"] == {"quote": "the exact words"}
    on_desktop = list_connections(desktop.addon, desktop.profile, source, 0)
    assert [row["target"]["passage"]["quote"] for row in on_desktop] == ["a place"]
    db.close_connection()
