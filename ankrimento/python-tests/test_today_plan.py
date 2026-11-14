"""The daily plan: what is due today, what was done, and sessions that follow the schedule instead of the whole pool."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade, session
from test_review_flow import _add_notes, _answer_good
from test_session_lifecycle import host_events  # noqa: F401 - fixture


@pytest.fixture(autouse=True)
def _isolated(rt):
    """Only this test's cards count: everything else is suspended, and the session deck and config are restored."""
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    col = rt.col
    for deck in col.decks.all_names_and_ids():
        if deck.name.startswith("Incremento Session"):
            col.sched.empty_filtered_deck(deck.id)
    others = list(col.find_cards("-is:suspended"))
    if others:
        col.sched.suspend_cards(others)
    session.forget_preview()
    yield
    if others:
        col.sched.unsuspend_cards(others)
    mw.addonManager.writeConfig("incremento", saved)
    session.forget_preview()


def _plan() -> dict:
    return json.loads(facade.today_plan())


def _scheduled(col, due=0, future=0, new=0):
    """Cards due today, scheduled for later, and never seen."""
    due_ids = _add_notes(col, "Topics", due // 2, "due topic") + _add_notes(col, "Default", due - due // 2, "due item")
    future_ids = _add_notes(col, "Topics", future, "future topic")
    new_ids = _add_notes(col, "Topics", new // 2, "new topic") + _add_notes(col, "Default", new - new // 2, "new item")
    if due_ids:
        col.sched.set_due_date(due_ids, "0")
    if future_ids:
        col.sched.set_due_date(future_ids, "30")
    return set(due_ids), set(future_ids), set(new_ids)


def test_the_plan_counts_what_is_due_and_nothing_scheduled_later_or_new(rt):
    _scheduled(rt.col, due=6, future=4, new=5)
    assert _plan()["due"] == 6


def test_the_plan_counts_what_was_done_today(rt):
    done_before = _plan()["done"]
    due, _future, _new = _scheduled(rt.col, due=6)
    for card_id in sorted(due)[:2]:
        _answer_good(rt.col, card_id)
    assert _plan() == {"due": 4, "done": done_before + 2}


def test_nothing_due_means_the_day_is_done(rt):
    assert _plan()["due"] == 0


def test_a_todays_session_takes_every_due_card_and_nothing_else(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, future, new = _scheduled(rt.col, due=60, future=10, new=20)

    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due
    assert not set(_session_cards(rt)) & (future | new)
    facade.reviewer_closed()


def test_study_ahead_takes_new_material_only_up_to_its_size(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, future, new = _scheduled(rt.col, due=6, future=4, new=30)

    facade.start_session("ahead", 7)
    assert entered_review.wait(timeout=30)
    picked = set(_session_cards(rt))
    assert len(picked) == 7
    assert picked <= new
    facade.reviewer_closed()


def test_the_saved_session_settings_are_not_changed_by_a_daily_session(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    _scheduled(rt.col, due=5)
    before = facade.session_settings()
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    facade.reviewer_closed()
    assert facade.session_settings() == before


def test_classic_sessions_still_draw_from_the_whole_pool(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, _future, new = _scheduled(rt.col, due=3, new=10)
    facade.start_session()
    assert entered_review.wait(timeout=30)
    picked = set(_session_cards(rt))
    assert picked & due and picked & new
    facade.reviewer_closed()


def test_the_preview_of_a_daily_session_is_the_session(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, _future, _new = _scheduled(rt.col, due=40, new=10)
    preview = json.loads(facade.session_preview("today", 0))
    assert {entry["card_id"] for entry in preview["entries"]} == due

    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due
    facade.reviewer_closed()


def _session_cards(rt):
    from incremento.backend.session_selection import incremento_session_deck_name

    deck_id = rt.col.decks.id_for_name(incremento_session_deck_name(None))
    return rt.col.find_cards(f"did:{deck_id}")


def test_the_plan_adds_the_session_deck_to_what_is_due_elsewhere():
    """A filtered deck's cards carry a position, not their schedule, as their due: its cards are counted on their own."""
    from ankrimento import today

    class Found:
        def find_cards(self, search):
            return {today.DUE: [1, 2, 3], today.IN_SESSION: [4, 5], "rated:1": [6]}[search]

    assert today.plan(Found()) == {"due": 5, "done": 1}
    assert "-deck:filtered" in today.DUE
