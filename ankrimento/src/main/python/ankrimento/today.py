"""The day's plan: what the schedule says is due today and what has been done, and sessions that follow it.

Incremento's own session draws from a pool of every new card plus whatever is due, a sample at a time, so a
finished session says nothing about the day. The plan is the schedule alone: reviewed cards that are due now.
A *today* session takes all of them, plus new cards: ordinary Anki decks bring theirs up to each deck's own
"New cards/day", and Incremento's material (topics, documents, the items made from them) comes in a daily amount
of its own, the most important first. *Study ahead* (the New material button, once the day is done) is more of
Incremento's material, the most important first, in a size the user chose: ordinary decks gave their share. Neither changes the saved session settings: the mode is applied to the settings of that
one session (see ``session.HeadlessSchedulerDialog.to_config``).
"""

from __future__ import annotations

import copy
import random
from typing import Any

MODE_CLASSIC = "classic"  # upstream's own session, from the saved settings
MODE_TODAY = "today"  # every card that is due, and a daily amount of new material mixed in
MODE_AHEAD = "ahead"  # new material only, in the size asked for

# Reviewed cards waiting to be studied: learning and review cards (not new, buried or suspended ones). A card in a
# filtered deck has had its due replaced by a position in it, which says nothing about its schedule, so decks like
# that are left out here and the session's own are counted from the due they had before (see _waiting_in_session).
DUE_CARDS = "((is:learn is:due) OR (is:review is:due)) -deck:filtered"
# Documents are topics with a due like any other. Upstream's picker ignores that due for them (it calls them
# "normally due-independent readers"), so a daily session adds them itself, when they are due and not paused by Revisit.
DOCUMENTS = 'note:"Incremento PDF" OR note:"Incremento EPUB"'
SESSION_LEARNING = '(deck:"Incremento Session*" is:learn is:due)'
SESSION_DECK_PREFIX = "Incremento Session"
# Material not started yet that a session may bring in: not suspended, postponed or held in a filtered deck other
# than the session's own (a new session sends what that one holds home first, see empty_session_decks).
NEW_READY = f'is:new -is:suspended -is:buried (-deck:filtered OR deck:"{SESSION_DECK_PREFIX}*")'
# Items Incremento made carry its tag or one of its item tags (an extract, a cloze); ordinary cards carry neither.
INCREMENTO_TAG = "Incremento"


def due_search(col: Any) -> str:
    """What is due: reviewed cards whose date has come, except documents paused with Revisit."""
    from incremento.backend.topic_review_actions import reader_revisit_filter

    return f"{DUE_CARDS} (-({DOCUMENTS}) OR {reader_revisit_filter(col)})"


def due_documents(col: Any) -> list[int]:
    """The documents that are due and not paused with Revisit."""
    from incremento.backend.topic_review_actions import reader_revisit_filter

    return list(col.find_cards(f"{DUE_CARDS} ({DOCUMENTS}) {reader_revisit_filter(col)}"))


def add_new_material(picker: Any, col: Any, per_day: int) -> None:
    """Mix the day's new cards into the session, then shuffle it all: what is left of the daily amount of Incremento
    material, the most important first, and the new cards of ordinary decks up to each deck's own limit."""
    kinds = new_material(col)
    left = new_left_today(col, per_day) if per_day > 0 else 0
    _add_most_important(picker, col, kinds, left, "daily_plan_new")
    for card_id in regular_new(col, kinds):
        if card_id not in picker.picked_ids:
            _record(picker, card_id, "items", "daily_plan_deck_new")
    # Different materials, mixed: the order is not the order they were found in.
    random.shuffle(picker.selected_ids)


def add_more_material(picker: Any, col: Any, count: int) -> None:
    """A session of more Incremento material once the day is done: ``count`` pieces, the most important first."""
    _add_most_important(picker, col, new_material(col), count, "study_ahead")
    random.shuffle(picker.selected_ids)


def _add_most_important(picker: Any, col: Any, kinds: dict[int, str], count: int, stage: str) -> None:
    """Put the ``count`` most important of ``kinds`` into the session, in the priority direction of the settings."""
    from incremento.backend import cards as card_utils

    if count <= 0:
        return
    ranked = card_utils.sort_cards_for_priority_mode(
        [card_id for card_id in kinds if card_id not in picker.picked_ids],
        picker.addon_dir,
        lower_is_more_important=picker.cfg.priority_lower_is_more_important,
        col=col,
        profile=picker.profile,
    )
    for card_id in ranked[:count]:
        _record(picker, card_id, kinds[card_id], stage)


def _record(picker: Any, card_id: int, card_type: str, stage: str) -> None:
    """Put a card into the session as upstream records any pick."""
    from incremento.backend.session_selection import _record_selected_card

    _record_selected_card(
        card_id=card_id,
        card_type=card_type,
        tag=None,
        mode="priority",
        stage=stage,
        selected_ids=picker.selected_ids,
        picked_meta=picker.picked_meta,
        added_to_filtered=picker.picked_ids,
        scheduler_counts=picker.scheduler_counts,
        session_counts=picker.session_counts,
    )


def empty_session_decks(col: Any) -> None:
    """Send the cards still in the session decks home before a daily session picks its cards.

    Cards postponed in the last session wait buried in its deck and only go home when it is rebuilt, which is after
    the cards for the next session were picked: the day's plan would start without them.
    """
    for deck in col.decks.all_names_and_ids():
        if deck.name.startswith(SESSION_DECK_PREFIX):
            col.sched.empty_filtered_deck(deck.id)


def add_due_documents(picker: Any, col: Any) -> None:
    """Put the due documents the picker left out into the session, recorded as upstream records any pick."""
    from incremento.backend import cards as card_utils

    for card_id in due_documents(col):
        if card_id not in picker.picked_ids:
            _record(picker, card_id, card_utils.get_document_card_type(card_id, col=col) or "pdf", "daily_plan")


def new_material(col: Any, ready: str = NEW_READY) -> dict[int, str]:
    """Incremento's material not started yet, with its kind as upstream names it: topics, items, pdf, epub.

    Topics as upstream classifies them (documents and extracts among them), and the items Incremento made, which
    carry its tag or an item tag. ``ready`` picks the cards looked at: ``introduced:1`` gives those started today.
    """
    from aqt import mw
    from incremento.backend import cards as card_utils
    from incremento.backend.config_service import load_addon_config
    from incremento.backend.knowledge_tree import configured_item_tags
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.topic_scheduler import resolve_topic_card_classifier

    cfg = load_scheduler_config()
    config = copy.deepcopy(load_addon_config(mw.addonManager, "incremento"))
    classifier = resolve_topic_card_classifier(config, scheduler_config=cfg)
    # Upstream keeps the lists it classified for a search until a session starts; the plan needs them as they are now.
    card_utils.clear_topic_item_cache()
    marks = " OR ".join(f'"tag:{tag}"' for tag in [INCREMENTO_TAG, *configured_item_tags(config)])
    kinds: dict[int, str] = {}
    for card_id in card_utils.get_all_item_cards(
        items_filter=cfg.items_filter, ready_filter=f"{ready} ({marks})", col=col, topic_classifier=classifier
    ):
        kinds[card_id] = "items"
    for card_id in card_utils.get_all_topic_cards(topics_filter=cfg.topics_filter, ready_filter=ready, col=col, topic_classifier=classifier):
        kinds[card_id] = "topics"
    for card_id in col.find_cards(f"{ready} ({DOCUMENTS})"):
        kinds[card_id] = card_utils.get_document_card_type(card_id, col=col) or "pdf"
    return kinds


def new_left_today(col: Any, per_day: int) -> int:
    """What is left of the daily amount of Incremento material: the pieces started today (answered once) count."""
    return max(0, int(per_day) - len(new_material(col, "introduced:1")))


def regular_new(col: Any, incremento: dict[int, str] | None = None) -> list[int]:
    """New cards of ordinary decks the day still brings: each deck's own "New cards/day" less what was started in it
    today, a parent's limit holding for its subdecks, in the order of the deck list and of each deck's new cards."""
    incremento = new_material(col) if incremento is None else incremento
    ids = [card_id for card_id in col.find_cards(NEW_READY) if card_id not in incremento]
    if not ids:
        return []
    names = {deck.id: deck.name for deck in col.decks.all_names_and_ids()}
    left = _new_left_by_deck(col, names)
    # A card in the session deck counts in the deck it came from, by the place it has there.
    rows = col.db.all(
        "select id, case when odid then odid else did end, case when odid then odue else due end from cards"
        f" where id in ({','.join(map(str, ids))})"
    )
    rows.sort(key=lambda row: ([part.casefold() for part in names.get(row[1], "").split("::")], row[2], row[0]))
    picked = []
    for card_id, deck_id, _position in rows:
        chain = _deck_and_parents(names.get(deck_id, ""))
        if chain and all(left.get(name, 0) > 0 for name in chain):
            for name in chain:
                left[name] -= 1
            picked.append(card_id)
    return picked


def _deck_and_parents(name: str) -> list[str]:
    parts = name.split("::") if name else []
    return ["::".join(parts[: index + 1]) for index in range(len(parts))]


def _new_left_by_deck(col: Any, names: dict[int, str]) -> dict[str, int]:
    """How many new cards each ordinary deck still gives today, by name: a card started in a subdeck counts against
    its parents too, as Anki counts it."""
    started: dict[str, int] = {}
    introduced = col.find_cards("introduced:1")
    if introduced:
        homes = col.db.list(
            f"select case when odid then odid else did end from cards where id in ({','.join(map(str, introduced))})"
        )
        for deck_id in homes:
            for name in _deck_and_parents(names.get(deck_id, "")):
                started[name] = started.get(name, 0) + 1
    left = {}
    for deck_id, name in names.items():
        deck = col.decks.get(deck_id, default=False)
        if deck and not deck.get("dyn"):
            left[name] = max(0, _new_limit(col, deck) - started.get(name, 0))
    return left


def _new_limit(col: Any, deck: dict) -> int:
    """The deck's "New cards/day": the limit set for today only, the deck's own, or its preset's."""
    today = deck.get("newLimitToday") or {}
    if today.get("today") == col.sched.today and today.get("limit") is not None:
        return int(today["limit"])
    if deck.get("newLimit") is not None:
        return int(deck["newLimit"])
    return int(col.decks.config_dict_for_deck_id(deck["id"])["new"]["perDay"])


def plan(col: Any, new_per_day: int = 0) -> dict[str, int]:
    """Cards due now (in the decks and still waiting in the session), new cards the day still brings, cards answered today.

    New cards already in the session are still to come: a new session sends them home and chooses again.
    """
    incremento = new_material(col)
    left = new_left_today(col, new_per_day) if new_per_day > 0 else 0
    return {
        "due": len(col.find_cards(due_search(col))) + _waiting_in_session(col),
        "new": min(left, len(incremento)) + len(regular_new(col, incremento)),
        "done": len(col.find_cards("rated:1")),
    }


def _waiting_in_session(col: Any) -> int:
    """Cards still waiting in the session deck: review cards whose own due has come (the deck gives them a position
    as due, so that one says nothing), and learning cards that are due. Postponed (buried) ones are not waiting."""
    ids = col.find_cards(f'deck:"{SESSION_DECK_PREFIX}*"')
    if not ids:
        return 0
    reviews = col.db.scalar(
        f"select count() from cards where id in ({','.join(map(str, ids))}) and queue = 2 and odue <= ?", col.sched.today
    )
    return int(reviews or 0) + len(col.find_cards(SESSION_LEARNING))


def apply_mode(cfg: Any, mode: str, count: int, due: int) -> None:
    """Turn a session's settings into the mode's, for this session only."""
    if mode == MODE_TODAY:
        cfg.include_new = False
        cfg.include_learning = True
        cfg.include_due = True
        cfg.session_card_count = max(1, due)
        # The picker's share of documents ignores their due; the due ones are added afterwards (add_due_documents).
        cfg.pdf_rate = 0.0
        # The session is the day's plan as it stood at the start; nothing tops it up with other cards.
        cfg.auto_refill_session = False
    elif mode == MODE_AHEAD:
        # The cards are chosen by add_more_material; the picker itself takes none.
        cfg.include_new = False
        cfg.include_learning = False
        cfg.include_due = False
        cfg.session_card_count = max(1, count)
        cfg.pdf_rate = 0.0
        cfg.auto_refill_session = False
