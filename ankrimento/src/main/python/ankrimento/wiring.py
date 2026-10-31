"""Upstream's Qt-free reviewer hook handlers, registered as desktop registers them.

Desktop connects Incremento's handlers to Anki's GUI hooks in its Qt
composition root (``incremento/__init__.py``). The handlers that need no Qt
are connected here to the ``aqt.gui_hooks`` stand-in, in the same order, so
the host firing a hook runs the same upstream code as desktop's reviewer.

Left out (Qt): the focus-timer widget, reader docks (the host opens its own
readers), the Add Card dock and diagnostics.
"""

from __future__ import annotations

from typing import Any

from aqt import gui_hooks, mw

_installed = False
_leech_tracker: Any = None


def _config() -> dict:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, "incremento")


def prepare_learning_leech(card: Any, ease: int) -> None:
    """Desktop wiring: ``_learning_leech_reviewer_will_answer_card``."""
    from incremento.backend.paths import get_active_profile

    _leech_tracker.clear()
    try:
        _leech_tracker.prepare_answer(mw.col, get_active_profile(), card, ease, _config())
    except Exception:
        _leech_tracker.clear()


def _finish_learning_leech(_reviewer: Any, card: Any, ease: int) -> None:
    """Desktop wiring: ``_learning_leech_on_card_answered``."""
    from incremento.backend.paths import get_active_profile

    try:
        _leech_tracker.finish_answer(mw.col, get_active_profile(), card, ease)
    except Exception:
        _leech_tracker.clear()


def install() -> None:
    global _installed, _leech_tracker
    if _installed:
        return
    from incremento.backend import review_time_tracker
    from incremento.backend.custom_schedule import apply_custom_schedule_after_answer
    from incremento.backend.learning_leech import LearningLeechTracker
    from incremento.backend.topic_scheduler import on_topic_card_answered

    _leech_tracker = LearningLeechTracker()
    gui_hooks.reviewer_did_show_question.append(review_time_tracker.on_reviewer_question_shown)
    gui_hooks.reviewer_did_show_answer.append(review_time_tracker.on_reviewer_answer_shown)
    gui_hooks.state_did_change.append(review_time_tracker.on_state_did_change)
    gui_hooks.reviewer_did_answer_card.append(review_time_tracker.on_reviewer_card_answered)
    gui_hooks.reviewer_did_answer_card.append(on_topic_card_answered)
    gui_hooks.reviewer_did_answer_card.append(apply_custom_schedule_after_answer)
    gui_hooks.reviewer_did_answer_card.append(_finish_learning_leech)
    _installed = True
