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
# that are left out here; the session's own are counted below, by the due Incremento gives them when it answers.
DUE = "((is:learn is:due) OR (is:review is:due)) -deck:filtered"
IN_SESSION = '(deck:"Incremento Session*" ((is:learn is:due) OR (is:review is:due)))'


def plan(col: Any) -> dict[str, int]:
    """How many cards are due now (in the decks and still waiting in the session) and how many were answered today."""
    return {"due": len(col.find_cards(DUE)) + len(col.find_cards(IN_SESSION)), "done": len(col.find_cards("rated:1"))}


def apply_mode(cfg: Any, mode: str, count: int, due: int) -> None:
    """Turn a session's settings into the mode's, for this session only."""
    if mode == MODE_TODAY:
        cfg.include_new = False
        cfg.include_learning = True
        cfg.include_due = True
        cfg.session_card_count = max(1, due)
        # The session is the day's plan as it stood at the start; nothing tops it up with other cards.
        cfg.auto_refill_session = False
    elif mode == MODE_AHEAD:
        cfg.include_new = True
        cfg.include_learning = False
        cfg.include_due = False
        cfg.session_card_count = max(1, count)
        cfg.auto_refill_session = False
