"""Select a session, build its deck and answer a topic the way AnkiDroid would."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade


def _add_notes(col, deck_name: str, count: int, prefix: str) -> list[int]:
    deck_id = col.decks.id(deck_name)
    notetype = col.models.by_name("Basic")
    card_ids = []
    for index in range(count):
        note = col.new_note(notetype)
        note["Front"] = f"{prefix} {index}"
        note["Back"] = "back"
        col.add_note(note, deck_id)
        card_ids.extend(note.card_ids())
    return card_ids


def _answer_good(col, card_id: int) -> None:
    """What AnkiDroid's reviewer does: answer through the backend's v3 scheduler."""
    # Imported here: anki.scheduler loads anki._backend, which must wait until
    # the runtime fixture has installed the host bridge.
    from anki.scheduler.v3 import CardAnswer

    card = col.get_card(card_id)
    card.start_timer()
    states = col._backend.get_scheduling_states(card.id)
    col.sched.answer_card(col.sched.build_answer(card=card, states=states, rating=CardAnswer.GOOD))


@pytest.fixture(scope="module")
def cards(rt):
    topics = _add_notes(rt.col, "Topics", 5, "topic")
    items = _add_notes(rt.col, "Default", 5, "item")
    return {"topics": topics, "items": items}


@pytest.fixture(scope="module")
def session_deck(rt, cards):
    selected = json.loads(facade.select_session())["card_ids"]
    assert selected
    assert set(selected) <= set(cards["topics"]) | set(cards["items"])
    deck_id = json.loads(facade.build_session_deck(json.dumps(selected)))["deck_id"]
    return deck_id, selected


def test_session_deck_holds_exactly_the_selected_cards(rt, session_deck):
    deck_id, selected = session_deck
    deck = rt.col.decks.get(deck_id)
    assert deck["dyn"]
    assert deck["name"] == "Incremento Session"
    assert sorted(rt.col.find_cards(f"did:{deck_id}")) == sorted(selected)


def test_topic_answer_applies_a_factor_in_the_answer_undo_step(rt, cards, session_deck):
    _, selected = session_deck
    card_id = next(cid for cid in selected if cid in cards["topics"])
    before = rt.col.get_card(card_id)

    decision = json.loads(facade.before_answer(card_id, 1))  # Again slot = More
    assert decision == {"mode": "topic", "ease": 3}

    _answer_good(rt.col, card_id)
    facade.after_answer(card_id, decision["ease"])

    state = json.loads(facade.topic_state(card_id))
    assert state["a_factor"] == pytest.approx(3.5 * 0.9)
    answered = rt.col.get_card(card_id)
    assert answered.ivl == state["interval"]
    assert answered.odid == 0  # back in its home deck after a rescheduling answer
    assert rt.col.db.scalar("select count() from revlog where cid = ?", card_id) == 1

    # The interval override was merged into Anki's own "Answer Card" step, so
    # one undo restores the card completely.
    rt.col.undo()
    restored = rt.col.get_card(card_id)
    assert (restored.type, restored.queue, restored.ivl, restored.did) == (
        before.type,
        before.queue,
        before.ivl,
        before.did,
    )

    # Incremento's own topic state follows the undo, then the redo.
    facade.undo_state_changed()
    assert json.loads(facade.topic_state(card_id)) is None
    rt.col.redo()
    facade.undo_state_changed()
    assert json.loads(facade.topic_state(card_id))["a_factor"] == pytest.approx(3.5 * 0.9)


def test_topic_buttons_are_more_same_less_with_same_on_good(rt, cards):
    buttons = json.loads(facade.answer_buttons(cards["topics"][1]))
    assert buttons["mode"] == "topic"
    assert [(b["slot"], b["label"], b["ease"]) for b in buttons["buttons"]] == [
        (2, "More", 3),
        (3, "Same", 3),
        (4, "Less", 3),
    ]
    assert all(b["due"].endswith("d") for b in buttons["buttons"])


@pytest.fixture
def fail_pass_items(rt):
    """Turn on Incremento's Fail/Pass item buttons (off in upstream's config.json)."""
    from aqt import mw

    config = mw.addonManager.getConfig("incremento")
    previous = config["use_fail_pass_on_items"]
    config["use_fail_pass_on_items"] = True
    mw.addonManager.writeConfig("incremento", config)
    yield
    config["use_fail_pass_on_items"] = previous
    mw.addonManager.writeConfig("incremento", config)


def test_items_keep_ankidroid_buttons_by_default(rt, cards):
    buttons = json.loads(facade.answer_buttons(cards["items"][0]))
    assert buttons == {"mode": "standard", "buttons": []}
    decision = json.loads(facade.before_answer(cards["items"][0], 2))
    assert decision == {"mode": "standard", "ease": 2}


def test_item_buttons_are_fail_and_pass(rt, cards, fail_pass_items):
    from incremento.backend.i18n import t

    buttons = json.loads(facade.answer_buttons(cards["items"][0]))
    assert buttons["mode"] == "items_fail_pass"
    assert [(b["slot"], b["label"], b["ease"]) for b in buttons["buttons"]] == [
        (1, t("backend_item_fail"), 1),
        (3, t("backend_item_pass"), 3),
    ]


@pytest.mark.parametrize(("slot", "ease"), [(1, 1), (2, 3), (3, 3), (4, 3)])
def test_item_answers_map_to_fail_or_pass(rt, cards, fail_pass_items, slot, ease):
    decision = json.loads(facade.before_answer(cards["items"][0], slot))
    assert decision == {"mode": "items_fail_pass", "ease": ease}


def test_priority_round_trips_through_incremento_db(rt, cards):
    card_id = cards["items"][1]
    facade.set_priority(card_id, 12.0)
    assert facade.get_priority(card_id) == 12.0
