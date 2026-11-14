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
        # Overdue by three days: an original due of 0 would read as "none" when a filtered deck sends the card home.
        col.sched.set_due_date(due_ids, "0")
        col.db.execute(f"update cards set due = -3 where id in ({','.join(map(str, due_ids))})")
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


def test_a_new_daily_session_takes_the_cards_the_last_one_still_held(rt, host_events):  # noqa: F811
    """The cards in the session deck have its positions as their due, so the picker cannot see them: send them home first."""
    _events, entered_review = host_events
    due, _future, _new = _scheduled(rt.col, due=8)
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    facade.reviewer_closed()
    entered_review.clear()

    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due
    facade.reviewer_closed()


def test_cards_waiting_in_the_session_count_by_their_own_schedule(rt, host_events):  # noqa: F811
    """Not by the position the session deck gives them: the collection here is a day old, its session is bigger."""
    _events, entered_review = host_events
    _scheduled(rt.col, due=8)
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert _plan()["due"] == 8
    _answer_good(rt.col, _session_cards(rt)[0])
    assert _plan()["due"] == 7
    rt.col.sched.bury_cards(_session_cards(rt)[:3])
    assert _plan()["due"] == 4
    facade.reviewer_closed()


def test_documents_are_not_part_of_the_days_plan(rt, tmp_path, host_events):  # noqa: F811
    """Upstream offers documents to every session, due or not, as a share of the topics: they cannot be "due today"."""
    from test_pdf import _MINIMAL_PDF

    source = tmp_path / "Due book.pdf"
    source.write_bytes(_MINIMAL_PDF)
    document = facade.import_pdf(str(source), "Due book", priority=50.0)
    due, _future, _new = _scheduled(rt.col, due=6)
    rt.col.sched.set_due_date([document], "0")
    rt.col.db.execute(f"update cards set due = -3 where id = {document}")
    assert _plan()["due"] == 6

    _events, entered_review = host_events
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due
    assert _plan()["due"] == 6
    for card_id in _session_cards(rt):
        _answer_good(rt.col, card_id)
    assert _plan()["due"] == 0
    facade.reviewer_closed()
    facade.discard_extract(document)


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
