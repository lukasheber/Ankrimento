"""Writing topics: Markdown files edited on the phone, saved as desktop saves them.

Desktop's writing dock (``frontend/writing_dock.py``, Qt) saves with
``write_writing_text`` (rotating backups per the settings) and records word
counts with ``set_writing_word_stats``. That module imports Qt, so the small
settings and word-count helpers it uses are mirrored here, each named after
its desktop original.
"""

from __future__ import annotations

import datetime
import re
from typing import Any

from aqt import mw

from ankrimento import runtime

# Mirrors frontend/writing_dock.py constants.
_WRITING_WORD_COUNT_MODES = ("simple", "word_like")
_DEFAULT_WRITING_BACKUP_TIERS = ("1m", "30m", "1d")
_WORD_LIKE_TOKEN_RE = re.compile(r"[A-Za-z0-9]+(?:[’'`-][A-Za-z0-9]+)*", re.UNICODE)


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, "incremento")


def count_words(text: str, mode: str) -> int:
    """Mirrors ``_count_words``."""
    if mode == "word_like":
        return len(_WORD_LIKE_TOKEN_RE.findall(text or ""))
    return len([token for token in (text or "").split() if token.strip()])


def _relpath(card_id: int) -> str:
    from incremento.backend.writing_manager import WRITING_FILE_FIELD

    return str(runtime.current().col.get_card(int(card_id)).note()[WRITING_FILE_FIELD])


def open_text(card_id: int) -> dict[str, Any]:
    from incremento.backend.db import get_writing_progress
    from incremento.backend.writing_manager import ensure_writing_file, read_writing_text

    addon_dir, profile = _context()
    relpath = _relpath(card_id)
    ensure_writing_file(addon_dir, relpath, profile=profile)
    progress = get_writing_progress(addon_dir, profile, int(card_id))
    return {"text": read_writing_text(addon_dir, relpath), "cursor": int(progress.get("cursor_position") or 0)}


def save_text(card_id: int, text: str) -> int:
    """Save like desktop's ``_write_writing_snapshot``; returns the word count."""
    from incremento.backend.db import get_writing_word_stats, set_writing_word_stats
    from incremento.backend.writing_manager import normalize_writing_backup_tiers, write_writing_text

    addon_dir, profile = _context()
    cfg = _config()
    write_writing_text(
        addon_dir,
        _relpath(card_id),
        text,
        profile=profile,
        backups_enabled=bool(cfg.get("writing_backups_enabled", True)),
        backup_tiers=normalize_writing_backup_tiers(cfg.get("writing_backup_tiers")) or _DEFAULT_WRITING_BACKUP_TIERS,
    )
    mode = str(cfg.get("writing_word_count_mode", "simple") or "").strip().lower()
    words = count_words(text, mode if mode in _WRITING_WORD_COUNT_MODES else "simple")
    # Mirrors _logical_today_text and the daily baseline: the first count of a
    # day is that day's starting point.
    today = datetime.date.today().isoformat()
    stats = get_writing_word_stats(addon_dir, profile, int(card_id))
    if stats.get("daily_logical_date") == today:
        baseline = int(stats.get("daily_baseline_words") or 0)
    else:
        baseline = int(stats.get("current_word_count") or words)
    set_writing_word_stats(
        addon_dir, profile, int(card_id), current_word_count=words, daily_logical_date=today, daily_baseline_words=baseline
    )
    return words


def create(title: str, deck_name: str = "Topics") -> int:
    """A new writing topic, as desktop's Add Markdown Writing does."""
    from incremento.backend.writing_manager import add_writing_card

    rt = runtime.current()
    return int(add_writing_card(str(rt.addon_dir), rt.col, title, deck_name=deck_name))


# ── Formatting toolbar and preview ────────────────────────────────────────────
#
# Desktop's toolbar buttons rewrite the selection with ``_apply_markdown_transform``,
# which works on its Qt editor; the text helpers it calls are compiled from
# ``frontend/writing_dock.py`` alone (shipped to be read, never imported).

_TEXT_HELPERS = ("_normalize_selection_text", "_wrap_selection_text", "_prefix_lines_text")
_text_helpers: dict[str, Any] | None = None

# The toolbar's formatting buttons in desktop's order: kind, label (a message id or the text itself), tooltip.
_TOOLBAR = (
    ("h1", "H1", "writing_insert_heading"),
    ("bold", "B", "writing_bold"),
    ("italic", "I", "writing_italic"),
    ("bullet", "•", "writing_bullet_list"),
    ("number", "1.", "writing_numbered_list"),
    ("quote", "writing_quote", "writing_block_quote"),
    ("code", "</>", "writing_code_block"),
    ("rule", "HR", "writing_horizontal_rule"),
)


def _helpers() -> dict[str, Any]:
    global _text_helpers
    if _text_helpers is None:
        import ast

        path = runtime.current().addon_dir / "frontend" / "writing_dock.py"
        tree = ast.parse(path.read_text(encoding="utf8"), filename=str(path))
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in _TEXT_HELPERS]
        if len(functions) != len(_TEXT_HELPERS):
            raise RuntimeError("upstream writing helpers not found")
        namespace: dict[str, Any] = {}
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(path), "exec"), namespace)  # noqa: S102 - upstream's own code
        _text_helpers = namespace
    return _text_helpers


def toolbar() -> list[dict[str, str]]:
    """Desktop's formatting buttons, with its labels and tooltips."""
    from incremento.backend.i18n import t

    return [
        {"kind": kind, "label": t(label) if label.startswith("writing_") else label, "description": t(description)}
        for kind, label, description in _TOOLBAR
    ]


def transform(kind: str, selected: str) -> str:
    """What a toolbar button puts in place of ``selected``; mirrors ``_apply_markdown_transform``."""
    from incremento.backend.i18n import t

    helpers = _helpers()
    selected = helpers["_normalize_selection_text"](selected)
    if kind == "h1":
        return helpers["_prefix_lines_text"](selected, "# ", t("writing_placeholder_heading"))
    if kind == "bold":
        return helpers["_wrap_selection_text"](selected, "**", "**", t("writing_placeholder_bold"))
    if kind == "italic":
        return helpers["_wrap_selection_text"](selected, "*", "*", t("writing_placeholder_italic"))
    if kind == "bullet":
        return helpers["_prefix_lines_text"](selected, "- ", t("writing_placeholder_list_item"))
    if kind == "number":
        body = selected.strip()
        if not body:
            return "1. " + t("writing_placeholder_list_item")
        return "\n".join(f"{idx + 1}. {line}" if line else f"{idx + 1}." for idx, line in enumerate(body.splitlines()))
    if kind == "quote":
        return helpers["_prefix_lines_text"](selected, "> ", t("writing_placeholder_quote"))
    if kind == "code":
        body = selected.strip("\n") or t("writing_placeholder_code")
        return f"```\n{body}\n```"
    if kind == "rule":
        return "\n\n---\n\n"
    raise ValueError(f"unknown formatting: {kind!r}")


def preview(text: str) -> str:
    """Desktop's inert Markdown preview of the writing."""
    from incremento.backend.markdown_manager import render_markdown_text_preview

    return render_markdown_text_preview(text)
