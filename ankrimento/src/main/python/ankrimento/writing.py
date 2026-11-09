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
    """Mirrors ``_count_words``: the hidden excerpt markers desktop's "Send to Writing" leaves are not words."""
    from incremento.backend.writing_links import strip_markers

    text = strip_markers(str(text or ""))
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
    # Mirrors the dock's save: excerpt links follow the markers still in the saved text.
    from incremento.backend.writing_links import sync_links

    sync_links(addon_dir, profile, int(card_id), text)
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


# ── Send to Writing: a passage quoted with a link back to its source ──────────────────────────────────────────────
#
# Desktop's ``frontend/writing_excerpt_ui.send_to_writing`` and ``writing_dock.insert_linked_excerpt`` (Qt): the link is
# upstream's ``create_link``; the quote goes at the end of the text between two hidden markers that follow the passage
# through edits, as ``insert_linked_excerpt`` writes it.


def _source_label(source: dict[str, Any]) -> str:
    """Mirrors ``source_label`` in ``frontend/writing_excerpt_ui.py``."""
    from incremento.backend.i18n import t

    if source["kind"] == "pdf":
        return t("study_pdf_page", page=source["page"])
    if source["kind"] == "epub":
        return t("study_epub_section", section=source["section"] + 1)
    return t("study_card")


def _writing_title(card_id: int) -> str:
    note = runtime.current().col.get_card(int(card_id)).note()
    return str(note["Title"]) if "Title" in note else ""


def send_excerpt(source: dict[str, Any], writing_card_id: int) -> dict[str, Any]:
    """Quote ``source`` ("kind", "card_id", "quote" and its place) into a writing topic; returns its anchor."""
    from incremento.backend.content_safety import external_plain_text_to_markdown
    from incremento.backend.recording_links import normalize_source
    from incremento.backend.study_resources import resolve_document
    from incremento.backend.writing_links import create_link, marker

    rt = runtime.current()
    source = normalize_source(source)
    if not source["quote"].strip():
        raise ValueError("Select a passage first")
    note = rt.col.get_card(int(writing_card_id)).note()
    if note.note_type()["name"] != "Incremento Writing":
        raise ValueError("Writing card unavailable")
    if source["kind"] == "card":
        title = _writing_title(source["card_id"])
    else:
        title = resolve_document(str(rt.addon_dir), rt.profile, rt.col, source)["title"]
    anchor = create_link(str(rt.addon_dir), rt.profile, source, int(writing_card_id), source_title=str(title)[:240])
    current = open_text(int(writing_card_id))["text"]
    quote = external_plain_text_to_markdown(source["quote"], max_chars=2000)
    # Mirrors ``insert_linked_excerpt``: a blank line, the start marker, the quote, the end marker, a blank line.
    save_text(
        int(writing_card_id),
        current + "\n\n" + marker(anchor, start=True) + "\n" + quote + "\n" + marker(anchor) + "\n\n",
    )
    return {"anchor": anchor, "title": _writing_title(writing_card_id)}


def _excerpt(link: dict[str, Any]) -> dict[str, Any]:
    source = link["source"]
    return {
        "anchor": link["anchor"],
        "kind": source["kind"],
        "card_id": source["card_id"],
        "page": source.get("page", 0),
        "section": source.get("section", 0),
        "offset": source.get("offset", -1),
        "quote": source["quote"],
        "label": _source_label(source),
        "source_title": link["source_title"],
        "writing_card_id": link["writing_card_id"],
    }


def excerpts(writing_card_id: int) -> list[dict[str, Any]]:
    """The passages quoted in a writing topic that are still in its text, newest first."""
    from incremento.backend.writing_links import list_links

    rt = runtime.current()
    return [_excerpt(link) for link in list_links(str(rt.addon_dir), rt.profile, writing_card_id=int(writing_card_id))]


def source_excerpts(kind: str, card_id: int, position: int) -> list[dict[str, Any]]:
    """The writing topics that quote the page (PDF, 1-based) or chapter (EPUB, 0-based) ``position`` of a document."""
    from incremento.backend.writing_links import list_links

    rt = runtime.current()
    source = {"kind": kind, "card_id": int(card_id), "quote": ""}
    source["page" if kind == "pdf" else "section"] = int(position)
    result = []
    for link in list_links(str(rt.addon_dir), rt.profile, source=source):
        if link["writing_card_id"]:
            result.append({**_excerpt(link), "writing_title": _writing_title(link["writing_card_id"])})
    return result


# ── Formatting toolbar and preview ────────────────────────────────────────────
#
# Desktop's toolbar buttons rewrite the selection with ``_apply_markdown_transform``,
# which works on its Qt editor; the text helpers it calls are compiled from
# ``frontend/writing_dock.py`` alone (shipped to be read, never imported).

_TEXT_HELPERS = ("_normalize_selection_text", "_wrap_selection_text", "_prefix_lines_text")
_text_helpers: dict[str, Any] | None = None

# The toolbar's formatting buttons in desktop's order: kind, label (a message id or the text itself), tooltip.
_TOOLBAR = (
    ("h1", "H1", "writing_heading_one"),
    ("h2", "H2", "writing_heading_two"),
    ("h3", "H3", "writing_heading_three"),
    ("bold", "B", "writing_bold"),
    ("italic", "I", "writing_italic"),
    ("strike", "S", "writing_strike"),
    ("bullet", "•", "writing_bullet_list"),
    ("number", "1.", "writing_numbered_list"),
    ("quote", "writing_quote", "writing_block_quote"),
    ("inline_code", "`", "writing_inline_code"),
    ("code", "</>", "writing_code_block"),
    ("rule", "HR", "writing_horizontal_rule"),
    ("table", "writing_table", "writing_table"),
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
    # Desktop applies a heading or paragraph style to whole blocks, even when only a word is selected; the editor
    # passes the lines it wants restyled, so the text here is already whole lines.
    if kind in ("h1", "h2", "h3", "paragraph"):
        body = re.sub(r"(?m)^ {0,3}#{1,6} +", "", selected)
        if kind == "paragraph":
            return body
        return helpers["_prefix_lines_text"](body, "#" * int(kind[1]) + " ", t("writing_placeholder_heading"))
    if kind == "bold":
        return helpers["_wrap_selection_text"](selected, "**", "**", t("writing_placeholder_bold"))
    if kind == "italic":
        return helpers["_wrap_selection_text"](selected, "*", "*", t("writing_placeholder_italic"))
    if kind == "strike":
        return helpers["_wrap_selection_text"](selected, "~~", "~~", t("writing_placeholder_text"))
    if kind == "inline_code":
        body = selected or t("writing_placeholder_code")
        fence = "`" * (max((len(run) for run in re.findall(r"`+", body)), default=0) + 1)
        body = f" {body} " if body.startswith("`") or body.endswith("`") else body
        return f"{fence}{body}{fence}"
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
        fence = "`" * max(3, max((len(run) for run in re.findall(r"`+", body)), default=0) + 1)
        return f"\n\n{fence}\n{body}\n{fence}\n\n"
    if kind == "rule":
        return "\n\n---\n\n"
    if kind == "table":
        heading, cell = t("writing_placeholder_heading"), t("writing_placeholder_text")
        return f"\n\n| {heading} | {heading} |\n| --- | --- |\n| {cell} | {cell} |\n\n"
    raise ValueError(f"unknown formatting: {kind!r}")


def preview(text: str) -> str:
    """Desktop's inert Markdown preview of the writing."""
    from incremento.backend.markdown_manager import render_markdown_text_preview

    return render_markdown_text_preview(text)
