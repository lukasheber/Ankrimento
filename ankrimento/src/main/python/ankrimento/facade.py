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


# AnkiDroid always has four answer slots (1 Again, 2 Hard, 3 Good, 4 Easy) and
# answers through gestures and keys bound to them. Incremento's desktop
# buttons are numbered from 1. These maps place them so that "Good", the
# usual default answer, means Same for topics and Pass for items, matching
# desktop's default buttons.
_TOPIC_SLOT_FOR_BUTTON = {1: 2, 2: 3, 3: 4}  # More, Same, Less
_TOPIC_BUTTON_FOR_SLOT = {1: 1, 2: 1, 3: 2, 4: 3}
_ITEM_SLOT_FOR_BUTTON = {1: 1, 2: 3}  # Fail, Pass
_ITEM_BUTTON_FOR_SLOT = {1: 1, 2: 2, 3: 2, 4: 2}


def answer_buttons(card_id: int) -> str:
    """The answer buttons Incremento shows for a card.

    ``mode`` is upstream's ``reviewer_button_mode``. Each button names the
    AnkiDroid slot it occupies, its label, the ease Anki will record and,
    for topics, the next interval. ``standard`` cards keep AnkiDroid's buttons.
    Desktop wiring: ``_topic_review_buttons`` and ``_incremento_button_time``.
    """
    from incremento.backend.reviewer_buttons import (
        item_fail_pass_buttons,
        remap_item_fail_pass_ease,
        reviewer_button_mode,
    )
    from incremento.backend.topic_scheduler import (
        TOPIC_ANKI_EASE,
        TOPIC_REVIEW_BUTTONS,
        topic_due_label,
    )

    col = runtime.current().col
    card = col.get_card(int(card_id))
    mode = reviewer_button_mode(card)
    buttons: list[dict[str, Any]] = []
    if mode == "topic":
        for button, label in TOPIC_REVIEW_BUTTONS:
            buttons.append(
                {
                    "slot": _TOPIC_SLOT_FOR_BUTTON[button],
                    "label": label,
                    "ease": TOPIC_ANKI_EASE,
                    "due": topic_due_label(card, button),
                }
            )
    elif mode == "items_fail_pass":
        for button, label in item_fail_pass_buttons(col.sched.answerButtons(card), card):
            buttons.append(
                {
                    "slot": _ITEM_SLOT_FOR_BUTTON[button],
                    "label": label,
                    "ease": remap_item_fail_pass_ease(card, button),
                    "due": None,
                }
            )
    return _dumps({"mode": mode, "buttons": buttons})


def before_answer(card_id: int, slot: int) -> str:
    """Translate the AnkiDroid answer slot into the ease Anki should record.

    Topic choices are all answered as Good; :func:`after_answer` applies them.
    Desktop wiring: ``reviewer_will_answer_card`` handlers
    (``_topic_reviewer_will_answer_card``). Topic postpone is not wired yet.
    """
    from incremento.backend.custom_schedule import prepare_custom_schedule_answer
    from incremento.backend.reviewer_buttons import (
        remap_item_fail_pass_ease,
        reviewer_button_mode,
    )
    from incremento.backend.topic_scheduler import prepare_topic_answer

    card = runtime.current().col.get_card(int(card_id))
    slot = int(slot)
    mode = reviewer_button_mode(card)
    if mode == "topic":
        ease = prepare_topic_answer(card, _TOPIC_BUTTON_FOR_SLOT[slot])
    else:
        prepare_custom_schedule_answer(card)
        if mode == "items_fail_pass":
            ease = remap_item_fail_pass_ease(card, _ITEM_BUTTON_FOR_SLOT[slot])
        else:
            ease = slot
    return _dumps({"mode": mode, "ease": int(ease)})


def after_answer(card_id: int, ease: int) -> None:
    """Run Incremento's post-answer scheduling for a card AnkiDroid just answered.

    Desktop wiring: ``reviewer_did_answer_card`` handlers, in desktop's
    order. Connected: topic A-factor, custom schedule. Not yet: focus timer,
    review time, learning leech, diagnostics.
    """
    from incremento.backend.custom_schedule import apply_custom_schedule_after_answer
    from incremento.backend.topic_scheduler import on_topic_card_answered

    card = runtime.current().col.get_card(int(card_id))
    on_topic_card_answered(None, card, int(ease))
    apply_custom_schedule_after_answer(None, card, int(ease))
    gui_hooks.reviewer_did_answer_card(None, card, int(ease))


def undo_state_changed() -> None:
    """Bring Incremento's state in line after an undo, a redo or any other op.

    Desktop wiring: ``undo_state_did_change`` handlers (topic and custom
    schedule reconciliation). Anki desktop fires that hook after every
    collection operation; call this likewise.
    """
    from types import SimpleNamespace

    from incremento.backend.custom_schedule import (
        reconcile_custom_schedule_state_after_anki_operation,
    )
    from incremento.backend.topic_scheduler import reconcile_topic_state_after_anki_operation

    status = runtime.current().col.undo_status()
    undo_info = SimpleNamespace(can_undo=bool(status.undo), can_redo=bool(status.redo))
    reconcile_topic_state_after_anki_operation(undo_info)
    reconcile_custom_schedule_state_after_anki_operation(undo_info)
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
