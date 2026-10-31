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
