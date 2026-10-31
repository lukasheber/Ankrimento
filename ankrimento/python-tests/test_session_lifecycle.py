"""A session started through upstream's learnFunction, reviewed as AnkiDroid does."""

from __future__ import annotations

import json
import threading

import pytest

from ankrimento import facade
from test_review_flow import _add_notes, _answer_good


@pytest.fixture
def host_events():
    from aqt import mw

    events: list[tuple[str, dict]] = []
    entered_review = threading.Event()

    def record(event: str, fields: dict) -> None:
        events.append((event, fields))
        if event == "move_to_state" and fields["state"] == "review":
            entered_review.set()

    previous = mw.host_events
    mw.host_events = record
    yield events, entered_review
    mw.host_events = previous


def test_learn_function_session_reaches_review_and_counts_answers(rt, host_events):
    events, entered_review = host_events
    _add_notes(rt.col, "Topics", 2, "lifecycle topic")

    facade.start_session()
    assert entered_review.wait(timeout=30), f"no review state; events: {events}"

    from aqt import mw
    from incremento.backend.session_selection import incremento_session_deck_name

    deck_id = rt.col.decks.id_for_name(incremento_session_deck_name(None))
    card_id = rt.col.find_cards(f"did:{deck_id}")[0]

    facade.question_shown(card_id)
    facade.answer_shown(card_id)
    ease = json.loads(facade.before_answer(card_id, 3))["ease"]
    _answer_good(rt.col, card_id)
    facade.after_answer(card_id, ease)

    counts = json.loads(facade.session_counts())
    assert sum(counts["type"].values()) == 1

    facade.reviewer_closed()
    assert mw.state == "overview"
