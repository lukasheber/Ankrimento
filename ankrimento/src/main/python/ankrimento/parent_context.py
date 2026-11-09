"""The source of an extract, read-only, for the card being reviewed.

Desktop opens it in a sidebar beside the reviewer (``frontend/reviewer_parent_context.py``, Qt). The loading is
upstream's ``backend/card_context.load_parent_context``: the saved extraction origin, or the knowledge tree's parent
for older links, bounded and sanitised, never a live note. The phone shows the result in a sheet over the card.
"""

from __future__ import annotations

from typing import Any

from ankrimento import runtime


def load(card_id: int) -> dict[str, Any]:
    """``{}`` when the card has no parent; otherwise its id, title, whether it still exists, and its visible fields."""
    from incremento.backend.card_context import load_parent_context

    rt = runtime.current()
    context = load_parent_context(str(rt.addon_dir), rt.profile, rt.col, int(card_id))
    if context is None:
        return {}
    return {
        "card_id": int(context["card_id"]),
        "title": context["title"],
        "available": bool(context["available"]),
        "fields": [{"name": name, "html": markup} for name, markup in context["fields"]],
    }
