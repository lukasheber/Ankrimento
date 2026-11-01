"""Knowledge tree tools: desktop's tree search, and postponing a branch.

Desktop's tree dialog searches with ``search_knowledge_tree_nodes`` and
postpones a branch from its postpone dialog (``frontend/knowledge_tree_postpone_dialog.py``,
Qt). The phone offers the branch's postpone with the preset that dialog would
load for it, shows upstream's simulation, then applies the same plan.
"""

from __future__ import annotations

import copy
from typing import Any

from ankrimento import runtime

_SEARCH_LIMIT = 200


def search(query: str) -> list[dict[str, Any]]:
    """Nodes whose title or note text has ``query``, in tree order."""
    from incremento.backend.knowledge_tree import search_knowledge_tree_nodes

    rt = runtime.current()
    hits = search_knowledge_tree_nodes(
        str(rt.addon_dir), rt.profile, str(query or ""), include_title=True, include_note_text=True, limit=_SEARCH_LIMIT
    )
    return [{key: hit[key] for key in ("card_id", "title", "node_kind", "match_reason")} for hit in hits]


def _postpone_config(root_card_id: int) -> tuple[str, dict]:
    """The preset desktop's dialog loads for this branch (its own, else the default, else upstream's
    defaults), limited to the branch; mirrors ``_load_initial_preset``."""
    from incremento.backend.knowledge_tree_postpone import (
        SCOPE_SELECTED_BRANCH,
        default_postpone_preset,
        get_branch_attached_preset,
        load_default_postpone_preset,
    )

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    preset = get_branch_attached_preset(addon_dir, profile, root_card_id) or load_default_postpone_preset(addon_dir, profile)
    if preset is None:
        return "", default_postpone_preset(branch_root_card_id=root_card_id)
    config = copy.deepcopy(preset.get("config") or {})
    config["scope"] = SCOPE_SELECTED_BRANCH
    config["branch_root_card_id"] = root_card_id
    return str(preset.get("name") or ""), config


def _summary_lines(summary: dict) -> list[str]:
    """Mirrors ``_format_postpone_summary`` in frontend/knowledge_tree_postpone_dialog.py."""
    from incremento.backend.i18n import t, tn

    return [
        tn("reader_postpone_elements", int(summary.get("elements_to_postpone") or 0)),
        t("reader_postpone_average_interval", days=f"{float(summary.get('average_delay_interval') or 0.0):.1f}"),
        t("reader_postpone_average_delay", percent=f"{float(summary.get('average_delay') or 0.0):.1f}"),
        tn("reader_postpone_items_skipped", int(summary.get("items_skipped") or 0)),
        tn("reader_postpone_topics_skipped", int(summary.get("topics_skipped") or 0)),
        t("reader_postpone_max_qualified", interval=int(summary.get("max_interval_qualified") or 0)),
        t("reader_postpone_max_found", interval=int(summary.get("max_interval_found") or 0)),
    ]


def postpone_plan(root_card_id: int) -> dict[str, Any]:
    """Upstream's simulation for the branch: the preset used, its summary and how many would move."""
    from incremento.backend.knowledge_tree_postpone import simulate_postpone_plan

    rt = runtime.current()
    root = int(root_card_id)
    name, config = _postpone_config(root)
    summary = simulate_postpone_plan(str(rt.addon_dir), rt.profile, config, branch_root_card_id=root, col=rt.col)
    return {"preset": name, "lines": _summary_lines(summary), "count": int(summary.get("elements_to_postpone") or 0)}


def postpone_branch(root_card_id: int) -> dict[str, Any]:
    """Applies the plan, as the dialog's Postpone does; the count and desktop's message."""
    from incremento.backend.i18n import t, tn
    from incremento.backend.knowledge_tree_postpone import apply_postpone_plan

    rt = runtime.current()
    root = int(root_card_id)
    _, config = _postpone_config(root)
    summary = apply_postpone_plan(str(rt.addon_dir), rt.profile, config, branch_root_card_id=root, col=rt.col)
    count = int(summary.get("applied_count") or 0)
    message = tn("reader_postpone_applied_count", count) if count > 0 else t("reader_postpone_no_qualified_cards")
    return {"count": count, "message": message}
