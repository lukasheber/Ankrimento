"""Session presets, stored and applied as desktop's scheduler dialog does."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade


@pytest.fixture(autouse=True)
def _keep_config(rt):
    """Presets write Incremento's settings; later tests expect them as they were."""
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    yield
    mw.addonManager.writeConfig("incremento", saved)


def _settings() -> dict:
    return json.loads(facade.session_settings())


def _with_card_count(count: int) -> None:
    facade.save_session_settings(json.dumps({**_settings(), "card_count": count}))


def _presets() -> dict:
    return json.loads(facade.session_presets())


def test_a_new_preset_holds_the_current_settings_and_names_its_session_deck(rt):
    _with_card_count(30)

    facade.add_session_preset("Evening")

    assert _presets() == {"selected": "Evening", "names": ["Evening"]}
    # Desktop keeps one session deck per preset.
    assert facade.session_deck_name() == "Incremento Session (Evening)"
    from ankrimento import session

    assert session.HeadlessSchedulerDialog(None).selected_dialog_profile_name() == "Evening"


def test_loading_a_preset_brings_back_its_settings_and_current_settings_keep_the_values(rt):
    _with_card_count(30)
    facade.add_session_preset("Short")
    _with_card_count(80)

    facade.load_session_preset("Short")
    assert _settings()["card_count"] == 30

    _with_card_count(45)
    facade.load_session_preset(None)
    assert _presets()["selected"] is None
    assert _settings()["card_count"] == 45
    assert facade.session_deck_name() == "Incremento Session"


def test_saving_updates_the_selected_preset(rt):
    _with_card_count(30)
    facade.add_session_preset("Morning")
    _with_card_count(60)

    facade.save_session_preset()
    _with_card_count(10)
    facade.load_session_preset("Morning")

    assert _settings()["card_count"] == 60


def test_rename_keeps_the_preset_and_its_selection_and_refuses_a_taken_name(rt):
    _with_card_count(25)
    facade.add_session_preset("Old")
    facade.add_session_preset("Other")
    facade.load_session_preset("Old")

    facade.rename_session_preset("Old", "New")

    assert _presets() == {"selected": "New", "names": ["New", "Other"]}
    facade.load_session_preset("New")
    assert _settings()["card_count"] == 25
    with pytest.raises(Exception, match="already"):
        facade.rename_session_preset("New", "Other")
    with pytest.raises(Exception, match="already"):
        facade.add_session_preset("Other")


def test_deleting_the_selected_preset_returns_to_current_settings(rt):
    facade.add_session_preset("Gone")

    facade.delete_session_preset("Gone")

    assert _presets() == {"selected": None, "names": []}
    assert facade.session_deck_name() == "Incremento Session"


@pytest.fixture
def session_deck(rt):
    """Makes a filtered session deck holding one card, as a session of that preset leaves it."""
    notes = []

    def make(name: str) -> int:
        note = rt.col.new_note(rt.col.models.by_name("Basic"))
        note["Front"] = f"in {name}"
        rt.col.add_note(note, rt.col.decks.id("Default"))
        notes.append(note.id)
        deck_id = rt.col.decks.new_filtered(name)
        deck = rt.col.sched.get_or_create_filtered_deck(deck_id)
        deck.config.search_terms[0].search = f"nid:{note.id}"
        rt.col.sched.add_or_update_filtered_deck(deck)
        return deck_id

    yield make
    # Later tests build sessions from the collection: leave it as it was.
    rt.col.remove_notes(notes)
    for deck in rt.col.decks.all_names_and_ids():
        if deck.name.startswith("Incremento Session ("):
            rt.col.decks.remove([deck.id])


def test_renaming_a_preset_keeps_its_session_with_it(rt, session_deck):
    facade.add_session_preset("Reading")
    deck_id = session_deck("Incremento Session (Reading)")

    facade.rename_session_preset("Reading", "Deep reading")

    # The deck list hides session decks: a deck left under the old name would be out of reach.
    assert rt.col.decks.name(deck_id) == "Incremento Session (Deep reading)"
    assert facade.session_deck_name() == "Incremento Session (Deep reading)"


def test_deleting_a_preset_returns_its_sessions_cards_home(rt, session_deck):
    facade.add_session_preset("Done with")
    deck_id = session_deck("Incremento Session (Done with)")
    card_id = rt.col.decks.cids(deck_id)[0]

    facade.delete_session_preset("Done with")

    assert rt.col.decks.id_for_name("Incremento Session (Done with)") is None
    assert rt.col.get_card(card_id).did == rt.col.decks.id("Default")
