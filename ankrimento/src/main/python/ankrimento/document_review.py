"""Reviewing the cards made from a document: desktop's Review All for a PDF or EPUB.

Desktop asks for the review's scope in ``frontend/media_review_dialog.py``
(Qt), then selects the cards with upstream's ``backend/media_review.py`` and
builds the review with ``start_explicit_review_from_selector``. The phone
offers the same choices in a sheet, counted with the same selection.
"""

from __future__ import annotations

import random
from typing import Any

from ankrimento import runtime

# media_review_dialog._OPTION_MESSAGE_IDS: each choice with desktop's name for it, in desktop's order.
_LABELS = {
    "state": (("all", "reader_media_all_available"), ("due", "reader_media_due_only")),
    "card_kind": (("both", "reader_media_topics_items"), ("topics", "reader_media_topics_only"), ("items", "reader_media_items_only")),
    "media_range": (("all", "reader_media_entire"), ("to_current", "reader_media_to_current")),
    "order": (
        ("attached", "reader_media_order_attached"),
        ("media_position", "reader_media_order_position"),
        ("due_first", "reader_media_order_due"),
        ("random", "reader_media_order_random"),
    ),
}
_KINDS = {"pdf": ("PDF", "INCREMENTO_PDF_REVIEW_DECK"), "epub": ("EPUB", "INCREMENTO_EPUB_REVIEW_DECK")}
_OPTION_KEYS = ("state", "card_kind", "media_range", "order")


def _deck(kind: str) -> str:
    from incremento.backend import session

    return str(getattr(session, _KINDS[kind][1]))


def _options(raw: dict | None) -> dict[str, Any]:
    raw = dict(raw or {})
    return {key: str(raw[key]) for key in _OPTION_KEYS if key in raw}


def _classifier():
    from incremento.backend.topic_scheduler import resolve_topic_card_classifier

    try:
        return resolve_topic_card_classifier()
    except Exception:  # noqa: BLE001 - as desktop's dialog, review without one
        return None


def preview(card_id: int, kind: str, position: float, options: dict | None = None) -> dict[str, Any]:
    """How many of the document's cards the choices take, and desktop's names for the choices."""
    from incremento.backend.i18n import t
    from incremento.backend.media_review import inspect_linked_media_review_rows, select_linked_media_review_rows

    rt = runtime.current()
    rows = inspect_linked_media_review_rows(
        str(rt.addon_dir), rt.profile, int(card_id), col=rt.col, media_kind=kind,
        include_tree_descendants=True, target_deck_name=_deck(kind), topic_classifier=_classifier(),
    )
    selected = select_linked_media_review_rows(rows, current_position=position, **_options(options))
    return {
        "selected": int(selected["selected_count"]),
        "topics": int(selected["topic_count"]),
        "items": int(selected["item_count"]),
        "total": int(selected["total_count"]),
        "labels": {group: [[value, t(message)] for value, message in choices] for group, choices in _LABELS.items()},
    }


def start(card_id: int, kind: str, position: float, options: dict | None = None) -> bool:
    """Builds the review in desktop's review deck for the document; the host enters review when ready."""
    from incremento.backend.i18n import t
    from incremento.backend.media_review import linked_media_review_card_ids
    from incremento.backend.session import start_explicit_review_from_selector

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    chosen = _options(options)
    label, deck = _KINDS[kind][0], _deck(kind)
    seed = random.SystemRandom().randrange(1, 2**31)
    classifier = _classifier()
    selected_before = rt.col.decks.get_current_id()

    def select_ids(col) -> list[int]:
        return linked_media_review_card_ids(
            addon_dir, profile, int(card_id), col=col, media_kind=kind, current_position=position,
            random_seed=seed, include_tree_descendants=True, target_deck_name=deck,
            topic_classifier=classifier, **chosen,
        )

    def empty_deck() -> None:
        # The phone's addition to desktop's end of review (which reopens the reader): the deck is
        # emptied, so its cards go back to their own decks and it need not show in the deck list,
        # and the deck selected before is selected again, as the review's own is now out of sight.
        deck_id = rt.col.decks.id_for_name(deck)
        if deck_id:
            rt.col.sched.empty_filtered_deck(deck_id)
            rt.col.decks.select(selected_before if selected_before != deck_id else 1)

    return bool(
        start_explicit_review_from_selector(
            select_ids,
            deck_name=deck,
            preserve_order=True,
            reschedule=True,
            empty_message=t("reader_media_no_matching_cards", media=label),
            error_message=t("reader_media_review_start_failed", media=label),
            on_finished=empty_deck,
            diagnostic_source="media_review",
            diagnostic_content_kind=kind,
        )
    )
