"""The calls Kotlin makes into Incremento, as JSON in and JSON out.

Every function here delegates to unchanged upstream code. Where upstream's
behaviour is wired up in its Qt composition root (``incremento/__init__.py``),
the same calls are made here in the same order; those places are marked
"desktop wiring" so they can be checked when the upstream pin moves.
"""

from __future__ import annotations

import json
from typing import Any

from aqt import gui_hooks

from ankrimento import runtime


def _dumps(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"))


def select_session() -> str:
    """Pick session cards with the saved scheduler settings."""
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.session_selection import select_session_cards

    rt = runtime.current()
    result = select_session_cards(
        load_scheduler_config(),
        str(rt.addon_dir),
        col=rt.col,
        profile=rt.profile,
    )
    return _dumps({"card_ids": [int(cid) for cid in result.selected_ids]})


def build_session_deck(card_ids_json: str, preserve_order: bool = False) -> str:
    """Fill the "Incremento Session" filtered deck with exactly these cards."""
    from aqt import mw
    from aqt.operations import on_op_finished
    from incremento.backend.session import _prepare_filtered_review_deck
    from incremento.backend.session_selection import incremento_session_deck_name

    rt = runtime.current()
    built = _prepare_filtered_review_deck(
        [int(cid) for cid in json.loads(card_ids_json)],
        # Named scheduler presets get their own deck; presets aren't exposed yet.
        deck_name=incremento_session_deck_name(None),
        preserve_order=bool(preserve_order),
        col=rt.col,
        return_result=True,
    )
    on_op_finished(mw, built.changes, None)
    return _dumps({"deck_id": int(built.deck_id)})


def before_answer(card_id: int, button: int) -> str:
    """Translate a reviewer button into the ease Anki should record.

    Topic buttons 1/2/3 mean More/Same/Less; all are answered as Good and the
    choice is applied after the answer by :func:`after_answer`.
    Desktop wiring: ``_topic_reviewer_will_answer_card``.
    """
    from incremento.backend.topic_scheduler import is_topic_card, prepare_topic_answer

    card = runtime.current().col.get_card(int(card_id))
    if is_topic_card(card):
        return _dumps({"topic": True, "ease": int(prepare_topic_answer(card, int(button)))})
    return _dumps({"topic": False, "ease": int(button)})


def after_answer(card_id: int, ease: int) -> None:
    """Run Incremento's post-answer scheduling for a card AnkiDroid just answered.

    Desktop wiring: ``reviewer_did_answer_card`` handlers, of which the topic
    A-factor scheduler is connected so far.
    """
    from incremento.backend.topic_scheduler import on_topic_card_answered

    card = runtime.current().col.get_card(int(card_id))
    on_topic_card_answered(None, card, int(ease))
    gui_hooks.reviewer_did_answer_card(None, card, int(ease))


def undo_state_changed() -> None:
    """Bring Incremento's state in line after an undo, a redo or any other op.

    Desktop wiring: ``undo_state_did_change`` handlers, of which topic
    reconciliation is connected so far. Anki desktop fires that hook after
    every collection operation; call this likewise.
    """
    from types import SimpleNamespace

    from incremento.backend.topic_scheduler import reconcile_topic_state_after_anki_operation

    status = runtime.current().col.undo_status()
    undo_info = SimpleNamespace(can_undo=bool(status.undo), can_redo=bool(status.redo))
    reconcile_topic_state_after_anki_operation(undo_info)
    gui_hooks.undo_state_did_change(undo_info)


def topic_state(card_id: int) -> str:
    """The stored A-factor and interval of a topic card."""
    from incremento.backend.db import get_topic_schedule_state, topic_schedule_exists

    rt = runtime.current()
    addon_dir = str(rt.addon_dir)
    if not topic_schedule_exists(addon_dir, rt.profile, int(card_id)):
        return _dumps(None)
    a_factor, precise_interval, interval = get_topic_schedule_state(
        addon_dir, rt.profile, int(card_id)
    )
    return _dumps(
        {"a_factor": a_factor, "precise_interval": precise_interval, "interval": interval}
    )


def get_priority(card_id: int) -> float:
    from incremento.backend.priority_manager import get_priority as _get_priority

    rt = runtime.current()
    return float(_get_priority(str(rt.addon_dir), rt.profile, int(card_id)))


def set_priority(card_id: int, priority: float) -> None:
    from incremento.backend.priority_manager import set_priority as _set_priority

    rt = runtime.current()
    _set_priority(str(rt.addon_dir), rt.profile, int(card_id), float(priority))
