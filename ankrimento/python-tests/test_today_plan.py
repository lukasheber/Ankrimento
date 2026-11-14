"""The daily plan: what is due today, what was done, and sessions that follow the schedule instead of the whole pool."""

from __future__ import annotations

import copy
import json
import uuid

import pytest

from ankrimento import facade, session, today
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


def _amount(rt, n: int) -> int:
    """A daily amount of ``n`` new pieces of Incremento material on top of those the tests before this one started today."""
    return len(today.new_material(rt.col, "introduced:1")) + n


def _regular_deck(col, per_day: int, count: int, name: str = "") -> list[int]:
    """A deck of ordinary Anki cards (no Incremento tags) with its own "New cards/day", in the order Anki shows them."""
    name = name or f"Anki {uuid.uuid4().hex[:8]}"
    deck_id = col.decks.id(name)
    config = col.decks.get_config(col.decks.add_config_returning_id(name))
    config["new"]["perDay"] = per_day
    col.decks.update_config(config)
    deck = col.decks.get(deck_id)
    deck["conf"] = config["id"]
    col.decks.save(deck)
    return _add_notes(col, name, count, "regular")


def _tag(col, card_ids, tag: str) -> None:
    if card_ids:
        note_ids = col.db.list(f"select distinct nid from cards where id in ({','.join(map(str, card_ids))})")
        col.tags.bulk_add(note_ids, tag)


def _plan(new_per_day: int = 0) -> dict:
    return json.loads(facade.today_plan(new_per_day))


def _scheduled(col, due=0, future=0, new=0):
    """Cards due today, scheduled for later, and never seen."""
    due_ids = _add_notes(col, "Topics", due // 2, "due topic") + _add_notes(col, "Default", due - due // 2, "due item")
    future_ids = _add_notes(col, "Topics", future, "future topic")
    new_items = _add_notes(col, "Default", new - new // 2, "new item")
    _tag(col, new_items, "item")  # an item made in Incremento, as an extract is
    new_ids = _add_notes(col, "Topics", new // 2, "new topic") + new_items
    if due_ids:
        # Overdue by three days: an original due of 0 would read as "none" when a filtered deck sends the card home.
        col.sched.set_due_date(due_ids, "0")
        col.db.execute(f"update cards set due = -3 where id in ({','.join(map(str, due_ids))})")
    if future_ids:
        col.sched.set_due_date(future_ids, "30")
    return set(due_ids), set(future_ids), set(new_ids)


def test_the_plan_counts_what_is_due_and_nothing_scheduled_later_or_new(rt):
    _scheduled(rt.col, due=6, future=4, new=5)
    assert _plan() == {"due": 6, "new": 0, "done": _plan()["done"]}


def test_the_plan_counts_what_was_done_today(rt):
    done_before = _plan()["done"]
    due, _future, _new = _scheduled(rt.col, due=6)
    for card_id in sorted(due)[:2]:
        _answer_good(rt.col, card_id)
    assert _plan() == {"due": 4, "new": 0, "done": done_before + 2}


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


def test_a_document_is_a_topic_due_when_its_date_has_come(rt, tmp_path, host_events):  # noqa: F811
    """Upstream's picker ignores a document's due; the daily plan does not: due ones are in, the others are not."""
    from test_pdf import _MINIMAL_PDF

    due_source, later_source = tmp_path / "Due book.pdf", tmp_path / "Later book.pdf"
    due_source.write_bytes(_MINIMAL_PDF)
    later_source.write_bytes(_MINIMAL_PDF)
    due_document = facade.import_pdf(str(due_source), "Due book", priority=50.0)
    later_document = facade.import_pdf(str(later_source), "Later book", priority=50.0)
    due, _future, _new = _scheduled(rt.col, due=6)
    rt.col.sched.set_due_date([due_document], "0")
    rt.col.db.execute(f"update cards set due = -3 where id = {due_document}")
    rt.col.sched.set_due_date([later_document], "30")
    assert _plan()["due"] == 7

    _events, entered_review = host_events
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due | {due_document}
    for card_id in _session_cards(rt):
        _answer_good(rt.col, card_id)
    assert _plan()["due"] == 0
    facade.reviewer_closed()
    facade.discard_extract(due_document)
    facade.discard_extract(later_document)


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
    assert preview["target"] == preview["count"]  # the plan is the whole session: nothing "fewer than asked for"

    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due
    facade.reviewer_closed()


def _session_cards(rt):
    from incremento.backend.session_selection import incremento_session_deck_name

    deck_id = rt.col.decks.id_for_name(incremento_session_deck_name(None))
    return rt.col.find_cards(f"did:{deck_id}")


def _prioritised(rt, card_ids):
    """Give the cards priorities 1, 2, 3...: the first is the most important."""
    from incremento.backend.priority_manager import set_priority

    for rank, card_id in enumerate(card_ids, start=1):
        set_priority(str(rt.addon_dir), rt.profile, card_id, float(rank))


def test_new_material_is_mixed_in_up_to_the_daily_amount_by_priority(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, _future, new = _scheduled(rt.col, due=6, new=12)
    ranked = sorted(new)
    _prioritised(rt, ranked)

    amount = _amount(rt, 4)
    assert _plan(amount) == {"due": 6, "new": 4, "done": _plan(amount)["done"]}
    facade.start_session("today", amount)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due | set(ranked[:4])
    facade.reviewer_closed()


def test_a_second_session_the_same_day_adds_only_what_is_left_of_the_amount(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    _due, _future, new = _scheduled(rt.col, due=2, new=12)
    ranked = sorted(new)
    _prioritised(rt, ranked)
    amount = _amount(rt, 4)
    facade.start_session("today", amount)
    assert entered_review.wait(timeout=30)
    for card_id in ranked[:3]:  # three of the four new cards are started
        _answer_good(rt.col, card_id)
    facade.reviewer_closed()
    entered_review.clear()

    assert _plan(amount)["new"] == 1
    facade.start_session("today", amount)
    assert entered_review.wait(timeout=30)
    # The three started ones are learning cards now, due again: what is new in the session is the one that is left.
    new_in_session = {card_id for card_id in _session_cards(rt) if rt.col.get_card(card_id).type == 0}
    assert new_in_session == {ranked[3]}
    facade.reviewer_closed()


def test_the_plan_counts_no_more_new_material_than_there_is(rt):
    _scheduled(rt.col, due=3, new=2)
    assert _plan(_amount(rt, 5))["new"] == 2


def test_no_daily_amount_means_no_new_material(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, _future, _new = _scheduled(rt.col, due=4, new=6)
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due
    facade.reviewer_closed()


def test_the_preview_of_a_daily_session_counts_its_new_material_in_its_size(rt):
    _scheduled(rt.col, due=4, new=6)
    preview = json.loads(facade.session_preview("today", _amount(rt, 3)))
    assert preview["count"] == 7
    assert preview["target"] == 7


def test_new_material_waiting_in_the_session_is_still_left_of_the_day(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    _due, _future, new = _scheduled(rt.col, due=3, new=4)  # all the new material there is goes into the session
    ranked = sorted(new)
    _prioritised(rt, ranked)
    amount = _amount(rt, 4)
    assert _plan(amount)["new"] == 4

    facade.start_session("today", amount)
    assert entered_review.wait(timeout=30)
    assert _plan(amount) == {"due": 3, "new": 4, "done": _plan(amount)["done"]}
    _answer_good(rt.col, ranked[0])  # one of the four is started
    plan = _plan(amount)
    assert plan["new"] == 3
    facade.reviewer_closed()


def test_ordinary_decks_bring_their_new_cards_up_to_their_own_daily_limit(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    due, _future, _new = _scheduled(rt.col, due=3)
    first = _regular_deck(rt.col, per_day=3, count=6)
    second = _regular_deck(rt.col, per_day=2, count=6)

    assert _plan(0)["new"] == 5  # whatever the daily amount of Incremento material is
    facade.start_session("today", 0)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due | set(first[:3]) | set(second[:2])
    facade.reviewer_closed()


def test_new_cards_started_today_count_against_their_decks_limit(rt):
    cards = _regular_deck(rt.col, per_day=3, count=6)
    _answer_good(rt.col, cards[0])
    assert _plan(0)["new"] == 2


def test_a_parent_decks_limit_holds_for_its_subdecks(rt):
    parent = f"Anki {uuid.uuid4().hex[:8]}"
    _regular_deck(rt.col, per_day=2, count=0, name=parent)
    _regular_deck(rt.col, per_day=5, count=6, name=f"{parent}::Child")
    assert _plan(0)["new"] == 2


def test_the_daily_amount_is_for_incremento_material_only(rt, host_events):  # noqa: F811
    """Ordinary cards follow their deck, never the daily amount, however important they are."""
    _events, entered_review = host_events
    due, _future, new = _scheduled(rt.col, due=2, new=6)
    regular = _regular_deck(rt.col, per_day=0, count=4)
    _prioritised(rt, regular + sorted(new))  # the ordinary cards first
    amount = _amount(rt, 3)

    assert _plan(amount)["new"] == 3
    facade.start_session("today", amount)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due | set(sorted(new)[:3])
    facade.reviewer_closed()


def test_an_ordinary_card_started_today_leaves_the_daily_amount_alone(rt):
    _scheduled(rt.col, due=1, new=6)
    regular = _regular_deck(rt.col, per_day=5, count=3)
    amount = _amount(rt, 4)
    _answer_good(rt.col, regular[0])
    assert _plan(amount)["new"] == 4 + 2


def test_new_material_follows_the_priority_direction_chosen_in_the_settings(rt, host_events):  # noqa: F811
    """With higher numbers set as more important, the cards with the highest priorities come in first."""
    from aqt import mw

    config = mw.addonManager.getConfig("incremento")
    config["priority_lower_is_more_important"] = False
    mw.addonManager.writeConfig("incremento", config)
    _events, entered_review = host_events
    due, _future, new = _scheduled(rt.col, due=2, new=12)
    ranked = sorted(new)
    _prioritised(rt, ranked)  # priorities 1..12: here the last ones are the most important

    facade.start_session("today", _amount(rt, 4))
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == due | set(ranked[-4:])
    facade.reviewer_closed()


def test_more_new_material_after_the_day_is_incremento_material_by_priority(rt, host_events):  # noqa: F811
    """The New material button: ordinary decks gave their share in the day's plan, so it takes Incremento's only."""
    _events, entered_review = host_events
    _due, _future, new = _scheduled(rt.col, due=0, new=12)
    regular = _regular_deck(rt.col, per_day=20, count=4)
    ranked = sorted(new)
    _prioritised(rt, regular + ranked)  # the ordinary cards first

    facade.start_session("ahead", 5)
    assert entered_review.wait(timeout=30)
    assert set(_session_cards(rt)) == set(ranked[:5])
    facade.reviewer_closed()


def test_the_preview_of_more_new_material_is_the_session(rt):
    _scheduled(rt.col, due=0, new=12)
    preview = json.loads(facade.session_preview("ahead", 5))
    assert preview["count"] == 5
    assert all(entry["mode_key"] == "priority" for entry in preview["entries"])
