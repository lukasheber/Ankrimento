"""The day's plan: what the schedule says is due today and what has been done, and sessions that follow it.

Incremento's own session draws from a pool of every new card plus whatever is due, a sample at a time, so a
finished session says nothing about the day. The plan is the schedule alone: reviewed cards that are due now.
A *today* session takes all of them and nothing else; *study ahead* is the opposite, new material only, in a
size the user chose. Neither changes the saved session settings: the mode is applied to the settings of that
one session (see ``session.HeadlessSchedulerDialog.to_config``).
"""

from __future__ import annotations

from typing import Any

MODE_CLASSIC = "classic"  # upstream's own session, from the saved settings
MODE_TODAY = "today"  # every reviewed card that is due, no new cards
MODE_AHEAD = "ahead"  # new material only, in the size asked for

# Reviewed cards waiting to be studied: learning and review cards (not new, buried or suspended ones). A card in a
# filtered deck has had its due replaced by a position in it, which says nothing about its schedule, so decks like
# that are left out here and the session's own are counted from the due they had before (see _waiting_in_session).
# PDF and EPUB documents are not here: upstream offers them to every session whether or not they are due, a share of
# the topics set by "Documents within topics", so they cannot be part of what is "due today". (Videos and web pages
# are ordinary topics, due when their schedule says so.)
DOCUMENTS = 'note:"Incremento PDF" OR note:"Incremento EPUB"'
DUE = f"((is:learn is:due) OR (is:review is:due)) -deck:filtered -({DOCUMENTS})"
SESSION_LEARNING = f'(deck:"Incremento Session*" is:learn is:due -({DOCUMENTS}))'
SESSION_DECK_PREFIX = "Incremento Session"


def empty_session_decks(col: Any) -> None:
    """Send the cards still in the session decks home before a daily session picks its cards.

    Cards postponed in the last session wait buried in its deck and only go home when it is rebuilt, which is after
    the cards for the next session were picked: the day's plan would start without them.
    """
    for deck in col.decks.all_names_and_ids():
        if deck.name.startswith(SESSION_DECK_PREFIX):
            col.sched.empty_filtered_deck(deck.id)


def plan(col: Any) -> dict[str, int]:
    """How many cards are due now (in the decks and still waiting in the session) and how many were answered today."""
    return {"due": len(col.find_cards(DUE)) + _waiting_in_session(col), "done": len(col.find_cards("rated:1"))}


def _waiting_in_session(col: Any) -> int:
    """Cards still waiting in the session deck: review cards whose own due has come (the deck gives them a position
    as due, so that one says nothing), and learning cards that are due. Postponed (buried) ones are not waiting."""
    ids = col.find_cards(f'deck:"{SESSION_DECK_PREFIX}*" -({DOCUMENTS})')
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
        # Documents are not part of the day's plan (see DOCUMENTS): their share would take slots from what is due.
        cfg.pdf_rate = 0.0
        # The session is the day's plan as it stood at the start; nothing tops it up with other cards.
        cfg.auto_refill_session = False
    elif mode == MODE_AHEAD:
        cfg.include_new = True
        cfg.include_learning = False
        cfg.include_due = False
        cfg.session_card_count = max(1, count)
        cfg.auto_refill_session = False
