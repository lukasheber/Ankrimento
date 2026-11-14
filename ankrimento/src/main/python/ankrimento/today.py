"""The day's plan: what the schedule says is due today and what has been done, and sessions that follow it.

Incremento's own session draws from a pool of every new card plus whatever is due, a sample at a time, so a
finished session says nothing about the day. The plan is the schedule alone: reviewed cards that are due now.
A *today* session takes all of them and nothing else; *study ahead* is the opposite, new material only, in a
size the user chose. Neither changes the saved session settings: the mode is applied to the settings of that
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
# Material not started yet that a session may bring in: not suspended, postponed or held in a filtered deck.
NEW_READY = "is:new -is:suspended -is:buried -deck:filtered"


def due_search(col: Any) -> str:
    """What is due: reviewed cards whose date has come, except documents paused with Revisit."""
    from incremento.backend.topic_review_actions import reader_revisit_filter

    return f"{DUE_CARDS} (-({DOCUMENTS}) OR {reader_revisit_filter(col)})"


def due_documents(col: Any) -> list[int]:
    """The documents that are due and not paused with Revisit."""
    from incremento.backend.topic_review_actions import reader_revisit_filter

    return list(col.find_cards(f"{DUE_CARDS} ({DOCUMENTS}) {reader_revisit_filter(col)}"))


def add_new_material(picker: Any, col: Any, per_day: int) -> None:
    """Mix what is left of the day's new material into the session, the most important first, then shuffle it all."""
    from incremento.backend import cards as card_utils
    from incremento.backend.session_selection import _record_selected_card

    left = new_left_today(col, per_day) if per_day > 0 else 0
    if left:
        kinds = new_material(col)
        ranked = card_utils.sort_cards_for_priority_mode(
            [card_id for card_id in kinds if card_id not in picker.picked_ids],
            picker.addon_dir,
            lower_is_more_important=picker.cfg.priority_lower_is_more_important,
            col=col,
            profile=picker.profile,
        )
        for card_id in ranked[:left]:
            _record_selected_card(
                card_id=card_id,
                card_type=kinds[card_id],
                tag=None,
                mode="priority",
                stage="daily_plan_new",
                selected_ids=picker.selected_ids,
                picked_meta=picker.picked_meta,
                added_to_filtered=picker.picked_ids,
                scheduler_counts=picker.scheduler_counts,
                session_counts=picker.session_counts,
            )
    # Different materials, mixed: the order is not the order they were found in.
    random.shuffle(picker.selected_ids)


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
    from incremento.backend.session_selection import _record_selected_card

    for card_id in due_documents(col):
        if card_id in picker.picked_ids:
            continue
        _record_selected_card(
            card_id=card_id,
            card_type=card_utils.get_document_card_type(card_id, col=col) or "pdf",
            tag=None,
            mode="priority",
            stage="daily_plan",
            selected_ids=picker.selected_ids,
            picked_meta=picker.picked_meta,
            added_to_filtered=picker.picked_ids,
            scheduler_counts=picker.scheduler_counts,
            session_counts=picker.session_counts,
        )


def new_material(col: Any) -> dict[int, str]:
    """The material not started yet that sessions may use, with its kind as upstream names it: topics, items, pdf, epub."""
    from aqt import mw
    from incremento.backend import cards as card_utils
    from incremento.backend.config_service import load_addon_config
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.topic_scheduler import resolve_topic_card_classifier

    cfg = load_scheduler_config()
    classifier = resolve_topic_card_classifier(copy.deepcopy(load_addon_config(mw.addonManager, "incremento")), scheduler_config=cfg)
    kinds: dict[int, str] = {}
    for card_id in card_utils.get_all_item_cards(items_filter=cfg.items_filter, ready_filter=NEW_READY, col=col, topic_classifier=classifier):
        kinds[card_id] = "items"
    for card_id in card_utils.get_all_topic_cards(topics_filter=cfg.topics_filter, ready_filter=NEW_READY, col=col, topic_classifier=classifier):
        kinds[card_id] = "topics"
    for card_id in col.find_cards(f"{NEW_READY} ({DOCUMENTS})"):
        kinds[card_id] = card_utils.get_document_card_type(card_id, col=col) or "pdf"
    return kinds


def new_left_today(col: Any, per_day: int) -> int:
    """What is left of the daily amount of new material: new cards started today (answered for the first time) count."""
    return max(0, int(per_day) - len(col.find_cards("introduced:1")))


def plan(col: Any, new_per_day: int = 0) -> dict[str, int]:
    """Cards due now (in the decks and still waiting in the session), new material the day still brings, cards answered today."""
    left = new_left_today(col, new_per_day) if new_per_day > 0 else 0
    # New material already in the session is still to come (a new session would send it home and choose again).
    available = len(new_material(col)) + _new_in_session(col) if left else 0
    return {
        "due": len(col.find_cards(due_search(col))) + _waiting_in_session(col),
        "new": min(left, available),
        "done": len(col.find_cards("rated:1")),
    }


def _new_in_session(col: Any) -> int:
    """New cards waiting in the session deck (not postponed)."""
    ids = col.find_cards(f'deck:"{SESSION_DECK_PREFIX}*"')
    if not ids:
        return 0
    return int(col.db.scalar(f"select count() from cards where id in ({','.join(map(str, ids))}) and queue = 0") or 0)


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
        cfg.include_new = True
        cfg.include_learning = False
        cfg.include_due = False
        cfg.session_card_count = max(1, count)
        cfg.auto_refill_session = False
