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


def profile_name() -> str:
    """The profile's storage name (user_files/<name>), as upstream sanitizes it."""
    return runtime.current().profile


def start_session() -> None:
    """Start an Incremento session with upstream's ``learnFunction``.

    Runs in the background; when the deck is ready the host receives a
    ``move_to_state`` event with ``state == "review"``, or a message if no
    cards were found.
    """
    from ankrimento import session

    session.start()


def session_preview() -> str:
    """The cards the next session would get (upstream's selection); starting then uses them."""
    from ankrimento import session

    return _dumps(session.preview())


def session_settings() -> str:
    """The session settings of desktop's Basic view, as plain percentages.

    Upstream stores the sliders as complements: ``topics_slider`` is the item
    share, ``pdf_slider`` the non-document share of topics.
    """
    from aqt import mw
    from incremento.backend.config_service import load_addon_config, normalize_document_mix

    dialog = normalize_document_mix(load_addon_config(mw.addonManager, "incremento").get("dialog", {}))
    return _dumps(
        {
            "card_count": int(dialog.get("session_card_count", 50)),
            "topics_percent": round(100 - float(dialog.get("topics_slider", 10))),
            "documents_percent": round(100 - float(dialog.get("pdf_slider", 100))),
            "random_percent": round(float(dialog.get("random_slider", 99))),
            "include_new": bool(dialog.get("include_new", True)),
            "include_learning": bool(dialog.get("include_learning", True)),
            "include_due": bool(dialog.get("include_due", True)),
            "auto_refill": bool(dialog.get("auto_refill_session", False)),
        }
    )


def save_session_settings(settings_json: str) -> None:
    """Save session settings into Incremento's config, as desktop's dialog does."""
    from aqt import mw
    from incremento.backend.config_service import load_addon_config, save_addon_config

    settings = json.loads(settings_json)
    config = load_addon_config(mw.addonManager, "incremento")
    dialog = dict(config.get("dialog") or {})
    dialog.update(
        {
            "session_card_count": int(settings["card_count"]),
            "topics_slider": 100 - int(settings["topics_percent"]),
            "pdf_slider": 100 - int(settings["documents_percent"]),
            "random_slider": int(settings["random_percent"]),
            "include_new": bool(settings["include_new"]),
            "include_learning": bool(settings["include_learning"]),
            "include_due": bool(settings["include_due"]),
            "auto_refill_session": bool(settings["auto_refill"]),
        }
    )
    config["dialog"] = dialog
    save_addon_config(mw.addonManager, "incremento", config)


def statistics(days: int = 7) -> str:
    """Incremento's statistics: today, lifetime, reading/watching and a daily trend.

    Read with upstream's ``load_stats``, ``load_media_activity`` and
    ``load_daily_history``, as desktop's statistics dialog reads them.
    """
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.statistics import load_daily_history, load_media_activity, load_stats

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    day_end = load_scheduler_config().day_end_time
    stats = load_stats(addon_dir, profile)
    time_block = stats.get("time", {})
    history = load_daily_history(addon_dir, profile, days=int(days), day_end_time=day_end)
    return _dumps(
        {
            "today": {
                "cards": stats.get("daily", {}).get("counts", {}).get("type", {}),
                "seconds": time_block.get("daily", {}).get("seconds", {}).get("type", {}),
            },
            "lifetime": {
                "cards": stats.get("lifetime", {}).get("type", {}),
                "seconds": time_block.get("lifetime", {}).get("type", {}),
            },
            "media": load_media_activity(addon_dir, profile, days=int(days), day_end_time=day_end),
            "history": [
                {
                    "date": row["date"],
                    "cards": sum(row["counts"].get("type", {}).values()),
                    "seconds": sum(row["seconds"].get("type", {}).values()),
                    "pages": row["reading"]["pages"],
                }
                for row in history
            ],
        }
    )


def statistics_view(scope: str) -> str:
    """The statistics screen for one of desktop's scopes (see ``statistics_view``)."""
    from ankrimento import statistics_view as stats_view

    return _dumps(stats_view.view(str(scope)))


def knowledge_tree() -> str:
    """The knowledge tree in display order, as desktop's tree dialog loads it."""
    from incremento.backend.knowledge_tree import load_knowledge_tree_snapshot

    rt = runtime.current()
    snapshot = load_knowledge_tree_snapshot(str(rt.addon_dir), rt.profile, rt.col)
    parents = {int(row["card_id"]): row.get("parent_card_id") for row in snapshot.ordered_rows}

    def depth(card_id: int) -> int:
        level, parent = 0, parents.get(card_id)
        while parent is not None and parent in parents and level < 64:
            level, parent = level + 1, parents.get(parent)
        return level

    keys = ("card_id", "parent_card_id", "title", "node_kind", "priority")
    return _dumps(
        readable_titles(
            [{**{key: row.get(key) for key in keys}, "depth": depth(int(row["card_id"]))} for row in snapshot.ordered_rows]
        )
    )


def tree_search(query: str) -> str:
    """Knowledge tree nodes matching ``query`` in their title or note text, in tree order."""
    from ankrimento import tree

    return _dumps(readable_titles(tree.search(query)))


def tree_postpone_plan(root_card_id: int) -> str:
    """Upstream's simulation of postponing a branch: preset, summary lines, count."""
    from ankrimento import tree

    return _dumps(tree.postpone_plan(root_card_id))


def tree_postpone_branch(root_card_id: int) -> str:
    """Postpones a branch's qualifying cards as desktop's postpone dialog does."""
    from ankrimento import tree

    return _dumps(tree.postpone_branch(root_card_id))


def document_review_preview(card_id: int, kind: str, position: float, options_json: str) -> str:
    """How many of a document's cards a review would take with these choices (desktop's Review All)."""
    from ankrimento import document_review

    return _dumps(document_review.preview(int(card_id), str(kind), float(position), json.loads(options_json or "{}")))


def document_review_start(card_id: int, kind: str, position: float, options_json: str) -> bool:
    """Starts reviewing a document's cards; the host enters review when the deck is ready."""
    from ankrimento import document_review

    return document_review.start(int(card_id), str(kind), float(position), json.loads(options_json or "{}"))


def start_branch_session(root_card_id: int) -> bool:
    """A session limited to one branch of the tree, as desktop's "Study branch" starts it."""
    from incremento.backend.knowledge_tree import build_branch_study_scope

    from ankrimento import session

    rt = runtime.current()
    scope = build_branch_study_scope(str(rt.addon_dir), rt.profile, int(root_card_id), col=rt.col)
    if scope is None:
        return False
    session.start(branch_scope=scope)
    return True


def question_shown(card_id: int) -> None:
    """Desktop wiring: ``reviewer_did_show_question``."""
    gui_hooks.reviewer_did_show_question(runtime.current().col.get_card(int(card_id)))


def answer_shown(card_id: int) -> None:
    """Desktop wiring: ``reviewer_did_show_answer``."""
    gui_hooks.reviewer_did_show_answer(runtime.current().col.get_card(int(card_id)))


def reviewer_closed() -> None:
    """Desktop wiring: ``reviewer_will_end``, then leaving the review state."""
    from aqt import mw

    gui_hooks.reviewer_will_end()
    if mw.state == "review":
        mw.moveToState("overview")


def session_counts() -> str:
    """Cards answered in the current session, by kind, as upstream counts them."""
    from incremento.backend.session import get_session_counts

    return _dumps(get_session_counts())


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


def session_deck_name() -> str:
    """The filtered deck sessions fill, as upstream names it (one per selected preset)."""
    from ankrimento import presets
    from incremento.backend.session_selection import incremento_session_deck_name

    return incremento_session_deck_name(presets.selected())


def search_all(query: str) -> str:
    """Upstream's Search ALL with the saved filters: sections of documents and results, each with where it opens."""
    from ankrimento import search_all

    return _dumps(search_all.search(query))


def search_all_filters() -> str:
    from ankrimento import search_all

    return _dumps(search_all.filters())


def set_search_all_filter(name: str, enabled: bool) -> None:
    from ankrimento import search_all

    search_all.set_filter(name, enabled)


def session_options() -> str:
    """The advanced session options: filters, tag and content-type quotas, enforcement, scope."""
    from ankrimento import session_options

    return _dumps(session_options.get())


def set_session_options(changes_json: str) -> str:
    """Writes some advanced session options; returns them all as upstream reads them back."""
    from ankrimento import session_options

    return _dumps(session_options.set_options(json.loads(changes_json)))


def session_presets() -> str:
    """The saved session presets and the one the current settings came from (or null)."""
    from ankrimento import presets

    return _dumps(presets.listing())


def load_session_preset(name: str | None) -> None:
    """Applies a preset's settings; null keeps the current settings without a preset."""
    from ankrimento import presets

    presets.load(name)


def add_session_preset(name: str) -> None:
    """Saves the current settings as a new preset, which becomes the selected one."""
    from ankrimento import presets

    presets.add(name)


def save_session_preset() -> None:
    """Updates the selected preset with the current settings."""
    from ankrimento import presets

    presets.save()


def rename_session_preset(old_name: str, new_name: str) -> None:
    from ankrimento import presets

    presets.rename(old_name, new_name)


def delete_session_preset(name: str) -> None:
    from ankrimento import presets

    presets.delete(name)


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
    # Desktop's next will-answer handler sees the remapped ease.
    from ankrimento import wiring

    wiring.prepare_learning_leech(card, int(ease))
    return _dumps({"mode": mode, "ease": int(ease)})


def after_answer(card_id: int, ease: int) -> None:
    """Run Incremento's post-answer handlers for a card AnkiDroid just answered.

    Fires ``reviewer_did_answer_card``; ``wiring.py`` connects desktop's
    Qt-free handlers in desktop's order (review time, topic A-factor, custom
    schedule, learning leech), plus an active session's own handler.
    """
    card = runtime.current().col.get_card(int(card_id))
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


def is_topic(card_id: int) -> bool:
    """Whether Incremento treats the card as a topic (More/Same/Less)."""
    from incremento.backend.reviewer_buttons import reviewer_button_mode

    return reviewer_button_mode(runtime.current().col.get_card(int(card_id))) == "topic"


def _publish(changes: Any) -> None:
    from aqt import mw
    from aqt.operations import on_op_finished

    on_op_finished(mw, changes, None)


def topic_done(card_id: int) -> str:
    """Mark a topic done: suspend it and tag its note in one undo step.

    Desktop wiring: the reviewer's Done action (``TopicReviewActions``).
    """
    from aqt import mw
    from incremento.backend.config_service import configured_topic_done_tag, load_addon_config
    from incremento.backend.topic_review_actions import complete_topic

    done_tag = configured_topic_done_tag(load_addon_config(mw.addonManager, "incremento"))
    result = complete_topic(runtime.current().col, int(card_id), done_tag=done_tag)
    _publish(result.changes)
    return result.message


def topic_revisit(card_id: int, months: int) -> str:
    """Show a topic again only after 3, 6 or 12 months (desktop's "Revisit in…")."""
    from incremento.backend.topic_review_actions import revisit_topic

    result = revisit_topic(runtime.current().col, int(card_id), months=int(months))
    _publish(result.changes)
    return result.message


def topic_revisit_on(card_id: int, iso_date: str) -> str:
    """Desktop's "Revisit in… → Choose date…": show the topic again on that date."""
    import datetime

    from incremento.backend.topic_review_actions import revisit_topic

    result = revisit_topic(runtime.current().col, int(card_id), on_date=datetime.date.fromisoformat(str(iso_date)))
    _publish(result.changes)
    return result.message


def topic_postpone(card_id: int) -> str:
    """Postpone a topic for this session or for the configured minutes.

    Desktop wiring: the reviewer's postpone button (``_perform_topic_postpone``).
    """
    from anki.collection import OpChanges
    from incremento.backend.topic_postpone import postpone_topic_card, topic_postpone_due_label

    mode = postpone_topic_card(runtime.current().col.get_card(int(card_id)))
    # Upstream buries the card directly; tell the host its queue changed.
    _publish(OpChanges(card=True, study_queues=True))
    return f"Topic postponed ({topic_postpone_due_label()})" if mode == "timed" else "Topic postponed for this session"


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


def import_pdf(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0) -> int:
    """Add a PDF as an Incremento PDF topic card, as desktop's Add PDF does.

    The file is copied into ``user_files/<profile>/pdfs``; returns the card id.
    """
    from incremento.backend.pdf_manager import add_pdf_card
    from incremento.backend.priority_manager import set_priority

    rt = runtime.current()
    card_id = add_pdf_card(str(rt.addon_dir), rt.col, path, title, deck_name=deck_name, profile=rt.profile)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return int(card_id)


def import_epub(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0) -> int:
    """Add an EPUB as an Incremento EPUB topic card, as desktop's Add EPUB does."""
    from incremento.backend.epub_manager import add_epub_card
    from incremento.backend.priority_manager import set_priority

    rt = runtime.current()
    card_id = add_epub_card(str(rt.addon_dir), rt.col, path, title, deck_name=deck_name, profile=rt.profile)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return int(card_id)


def import_markdown(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0) -> int:
    """Add a Markdown file as a study document (read as an EPUB), as desktop's Markdown import does."""
    from incremento.backend.markdown_manager import add_markdown_card
    from incremento.backend.priority_manager import set_priority

    rt = runtime.current()
    card_id = add_markdown_card(str(rt.addon_dir), rt.profile, rt.col, path, title, deck_name=deck_name)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return int(card_id)


def import_audio(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0) -> int:
    """Add an audio file as an Incremento Audio topic, as desktop's audio import does."""
    from incremento.backend.audio_manager import add_audio_card
    from incremento.backend.priority_manager import set_priority

    rt = runtime.current()
    card_id = add_audio_card(str(rt.addon_dir), rt.profile, rt.col, source_path=path, title=title, deck_name=deck_name)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return int(card_id)


def writing_toolbar() -> str:
    """Desktop's writing formatting buttons (see ``writing.toolbar``)."""
    from ankrimento import writing

    return _dumps(writing.toolbar())


def writing_transform(kind: str, selected: str) -> str:
    from ankrimento import writing

    return writing.transform(str(kind), str(selected))


def writing_preview(text: str) -> str:
    from ankrimento import writing

    return writing.preview(str(text))


def support_bundle(path: str) -> str:
    """Desktop's support bundle written to ``path`` (see ``upkeep``)."""
    from ankrimento import upkeep

    return _dumps(upkeep.support_bundle(str(path)))


def card_format_updates() -> str:
    from ankrimento import upkeep

    return _dumps(upkeep.card_format_updates())


def apply_card_format_updates() -> str:
    from ankrimento import upkeep

    return _dumps(upkeep.apply_card_format_updates())


def import_web(url: str, title: str = "", deck_name: str = "Topics", priority: float = 50.0) -> int:
    """Add a web page as an Incremento Web topic card, as desktop's Web Page does."""
    from incremento.backend.priority_manager import set_priority
    from incremento.backend.web_manager import add_web_card

    rt = runtime.current()
    card_id = add_web_card(rt.col, url, title, deck_name=deck_name)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return int(card_id)


def import_web_document(url: str, deck_name: str = "Topics", priority: float = 50.0) -> str:
    """A web page as a document to read: upstream's page-to-Markdown fetch, then its
    Markdown document import, which makes an EPUB study document of it (read and
    extracted like any EPUB, on desktop too)."""
    import os
    import re
    import tempfile
    from pathlib import Path

    from incremento.backend import webpage_markdown
    from incremento.backend.markdown_manager import add_markdown_card
    from incremento.backend.priority_manager import set_priority

    rt = runtime.current()
    page = webpage_markdown.fetch_webpage_markdown(str(url), content_scope="main")
    title = str(page.get("title") or "").strip() or str(url)
    with tempfile.TemporaryDirectory() as folder:
        stem = re.sub(r"[^\w\- ]+", "", title)[:80].strip() or "Web page"
        path = os.path.join(folder, f"{stem}.md")
        from ankrimento import web_images

        with open(path, "w", encoding="utf8") as file:
            # Its images, downloaded beside it, so upstream's import keeps them with the document.
            file.write(web_images.localize(page["markdown_document"], Path(folder)))
        card_id = add_markdown_card(str(rt.addon_dir), rt.profile, rt.col, path, title, deck_name=deck_name)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return _dumps({"card_id": int(card_id), "title": title})


def import_web_snapshot(
    url: str, fmt: str, deck_name: str = "Topics", priority: float = 50.0, html: str = "", title: str = ""
) -> str:
    """Save a web page for offline reading, as desktop's "Webpage as a document" does (``backend/webpage_document.py``).

    ``fmt`` "html" keeps the site's own appearance in a self-contained snapshot, "markdown" makes an editable reading
    copy; both are EPUB study documents (read, highlighted and extracted like a book, on desktop too). ``html`` is the
    page's source when the caller already has it; otherwise it is fetched through upstream's public-network guard.
    """
    from incremento.backend import epub_manager
    from incremento.backend.note_metadata import build_incremento_metadata
    from incremento.backend.priority_manager import set_priority
    from incremento.backend.webpage_document import prepare_webpage_document

    if fmt not in ("html", "markdown"):
        raise ValueError("fmt must be html or markdown")
    rt = runtime.current()
    prepared = prepare_webpage_document(str(url), html=str(html or ""), title=str(title or ""), capture_format=fmt)
    try:
        source_type = "HTML" if fmt == "html" else "Markdown"
        card_id = epub_manager.add_epub_card(
            str(rt.addon_dir),
            rt.col,
            prepared.path,
            prepared.title,
            deck_name=deck_name,
            profile=rt.profile,
            source_type=source_type,
            source_metadata=build_incremento_metadata(
                source_type=source_type,
                source_title=prepared.title,
                source_link=prepared.capture["url"],
                imported_at=prepared.capture["captured_at"],
            ),
        )
        omitted = int(prepared.capture.get("omitted_images", 0))
        title_saved = prepared.title
    finally:
        prepared.close()
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return _dumps({"card_id": int(card_id), "title": title_saved, "format": fmt, "omitted_images": omitted})


def import_local_file(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0, note_text: str = "") -> str:
    """Add any file as a local file card with a managed copy (desktop's Add Local File, "import a managed copy")."""
    from incremento.backend.priority_manager import set_priority

    from ankrimento import local_files

    rt = runtime.current()
    card_id = local_files.add(str(path), str(title), str(deck_name), str(note_text))
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return _dumps({"card_id": card_id, "title": local_files.info(card_id)["title"]})


def local_file_info(card_id: int) -> str:
    """A local file card for its screen: the file's name, size and type, whether this device has it, and the note."""
    from ankrimento import local_files

    return _dumps(local_files.info(int(card_id)))


def local_file_attach(card_id: int, path: str) -> str:
    """Keep a copy of a file on this device as the card's file (a desktop reference becomes a managed copy)."""
    from ankrimento import local_files

    return _dumps(local_files.attach(int(card_id), str(path)))


def local_file_suggests(name: str) -> str:
    """"pdf" or "epub" when a file is better added as that kind of document, otherwise ""."""
    from ankrimento import local_files

    return local_files.suggests(str(name))


def import_link(url: str, title: str = "", priority: float = 50.0) -> str:
    """Add a shared link: a video link as a video topic, anything else as a web topic."""
    from incremento.backend.priority_manager import set_priority

    from ankrimento import links, video_reader

    # Shared links usually come without a title: look it up (the URL stays the title if that fails).
    title = title or links.title(url)
    if video_reader.is_video_url(url):
        card_id, kind = video_reader.import_video(url, title), "video"
    else:
        card_id, kind = import_web(url, title, priority=priority), "web"
    rt = runtime.current()
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return _dumps({"card_id": card_id, "kind": kind})


def card_reader(card_id: int) -> str:
    """Which reader opens this card: a document's ("pdf", "epub", "web", "video",
    "writing"), "text" for any other topic, or "" for an item."""
    note = runtime.current().col.get_card(int(card_id)).note()
    readers = {
        "Incremento PDF": "pdf",
        "Incremento EPUB": "epub",
        "Incremento Web": "web",
        "Incremento Video": "video",
        "Incremento Writing": "writing",
        "Incremento Local File": "file",
    }
    reader = readers.get(note.note_type()["name"], "")
    return reader or ("text" if is_topic(card_id) else "")


def settings_values() -> str:
    """The Incremento settings shown on mobile, from upstream's config."""
    from ankrimento import settings

    return _dumps(settings.values())


def set_setting(key: str, value_json: str) -> str:
    """Saves one mobile setting (value as JSON); returns the value upstream kept, as JSON."""
    from ankrimento import settings

    return _dumps(settings.set_value(str(key), json.loads(value_json)))


def review_buttons() -> str:
    """Desktop's review buttons (group, done, postpone, extract) and whether each is shown."""
    from ankrimento import settings

    return _dumps(settings.review_buttons())


def set_review_button(name: str, visible: bool) -> None:
    from ankrimento import settings

    settings.set_review_button(name, visible)


def library() -> str:
    """Every Incremento document, with kind, title, priority and reading position."""
    from ankrimento import library as documents

    return _dumps(documents.entries())


def card_title(card_id: int) -> str:
    """The title the knowledge tree shows for this card (its first field, as text), made readable for
    the readers' top bar: a cloze hides its own answer, sound tags are left out, image occlusion shows its header."""
    from incremento.backend.knowledge_tree import _card_title

    card = runtime.current().col.get_card(int(card_id))
    return _readable_title(card, str(_card_title(card)))


def _has_markup(title: str) -> bool:
    return "{{c" in title or "[sound:" in title or "image-occlusion:" in title


def _readable_title(card, title: str) -> str:
    """``title`` without card markup (see ``card_title``)."""
    import re

    if "image-occlusion:" in title:
        note = card.note()
        header = re.sub(r"<[^>]+>", "", note["Header"]).strip() if "Header" in note else ""
        return header or str(note.note_type()["name"])
    # As the card's question shows it: this card's own deletion hidden ("[hint]" or "[...]"), the others shown.
    own = card.ord + 1

    def cloze(match: re.Match) -> str:
        if int(match.group(1)) != own:
            return match.group(2)
        return f"[{match.group(3) or '...'}]"

    readable = re.sub(r"\{\{c(\d+)::(.*?)(?:::([^}]*))?\}\}", cloze, title, flags=re.S)
    readable = re.sub(r"\[sound:[^\]]*\]", "", readable)
    return " ".join(readable.split()) or title


def readable_titles(rows: list[dict]) -> list[dict]:
    """``rows`` (with ``card_id`` and ``title``) with their titles readable; cards are loaded only for markup."""
    col = runtime.current().col
    for row in rows:
        title = str(row.get("title") or "")
        if _has_markup(title):
            row["title"] = _readable_title(col.get_card(int(row["card_id"])), title)
    return rows


def video_extract(card_id: int, seconds: float, end: float = -1.0) -> None:
    """An extract at a moment, or (``end`` >= 0) one that marks a segment of the video (see ``video_reader.extract_at``)."""
    from ankrimento import video_reader

    video_reader.extract_at(int(card_id), float(seconds), float(end) if end >= 0 else None)


def writing_open(card_id: int) -> str:
    from ankrimento import writing

    return _dumps(writing.open_text(int(card_id)))


def writing_save(card_id: int, text: str) -> int:
    from ankrimento import writing

    return writing.save_text(int(card_id), text)


def send_to_writing(source_json: str, writing_card_id: int) -> str:
    """Quote a passage of a PDF or EPUB into a writing topic, linked back to its place; returns ``{"anchor", "title"}``."""
    from ankrimento import writing

    return _dumps(writing.send_excerpt(json.loads(source_json), int(writing_card_id)))


def writing_excerpts(writing_card_id: int) -> str:
    """The passages quoted in a writing topic that are still in its text."""
    from ankrimento import writing

    return _dumps(writing.excerpts(int(writing_card_id)))


def source_excerpts(card_id: int, kind: str, position: int) -> str:
    """The writing topics quoting a page (PDF) or chapter (EPUB) of a document."""
    from ankrimento import writing

    return _dumps(writing.source_excerpts(str(kind), int(card_id), int(position)))


def create_writing(title: str) -> int:
    from incremento.backend.priority_manager import set_priority

    from ankrimento import writing

    rt = runtime.current()
    card_id = writing.create(title)
    set_priority(str(rt.addon_dir), rt.profile, card_id, 50.0)
    return card_id


def video_open(card_id: int, start: float = -1.0) -> str:
    """The video to play; ``start`` >= 0 (an extract's link) opens it there instead of where playback stopped."""
    from ankrimento import video_reader

    return _dumps(video_reader.open_video(int(card_id), float(start) if start >= 0 else None))


def video_bookmarks(card_id: int) -> str:
    from ankrimento import video_reader

    return _dumps(video_reader.bookmarks(int(card_id)))


def video_add_bookmark(card_id: int, seconds: float) -> str:
    from ankrimento import video_reader

    return _dumps(video_reader.add_bookmark(int(card_id), float(seconds)))


def video_delete_bookmark(card_id: int, bookmark_id: str) -> str:
    from ankrimento import video_reader

    return _dumps(video_reader.delete_bookmark(int(card_id), str(bookmark_id)))


def video_bookmark_comment(card_id: int, bookmark_id: str, comment: str) -> str:
    from ankrimento import video_reader

    return _dumps(video_reader.bookmark_comment(int(card_id), str(bookmark_id), str(comment)))


def video_progress(card_id: int, position: float, watched_seconds: float) -> None:
    from ankrimento import video_reader

    video_reader.save_position(int(card_id), float(position), float(watched_seconds))


def web_open(card_id: int) -> str:
    from ankrimento import web_reader

    return _dumps(web_reader.open_page(int(card_id)))


def web_script(card_id: int, nonce: str, current_url: str) -> str:
    from ankrimento import web_reader

    return web_reader.bridge_script(int(card_id), nonce, current_url)


def web_message(card_id: int, message: str) -> None:
    from ankrimento import web_reader

    web_reader.handle(int(card_id), message)


def web_extract(card_id: int, text: str, url: str = "") -> bool:
    """Starts an extract from selected text; with the page's ``url`` it links back to it, as desktop's does."""
    from ankrimento import web_reader

    return web_reader.extract(int(card_id), text, url)


def epub_open(card_id: int, section_index: int = -1) -> str:
    """The chapter file to load (saved chapter when ``section_index`` < 0)."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.open_section(int(card_id), None if int(section_index) < 0 else int(section_index)))


def epub_script(
    card_id: int,
    section_index: int,
    nonce: str,
    scroll_ratio: float = -1.0,
    search_query: str = "",
    link_fragment: str = "",
    passage_json: str = "",
) -> str:
    """The page script for a loaded chapter (saved position when ``scroll_ratio`` < 0), finding
    ``search_query``, opening at ``link_fragment``, or revealing the passage a followed connection leads to."""
    from ankrimento import epub_reader

    ratio = None if float(scroll_ratio) < 0 else float(scroll_ratio)
    passage = json.loads(passage_json) if passage_json else None
    return epub_reader.section_script(int(card_id), int(section_index), nonce, ratio, search_query, link_fragment, passage)


def epub_connections(card_id: int, section_index: int) -> str:
    """The chapter's connections to other passages, from either end."""
    from ankrimento import epub_links

    return _dumps(epub_links.links(int(card_id), int(section_index)))


def epub_connection_target(card_id: int, section_index: int, link_id: int) -> str:
    """Where a connection opens; raises when its book or chapter is unavailable."""
    from ankrimento import epub_links

    return _dumps(epub_links.target(int(card_id), int(section_index), int(link_id)))


def epub_connection_remove(card_id: int, section_index: int, link_id: int) -> bool:
    from ankrimento import epub_links

    return epub_links.remove(int(card_id), int(section_index), int(link_id))


def epub_marks(card_id: int) -> str:
    """The book's highlights in book order, each with its chapter."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.marks(card_id))


def epub_highlight_color(card_id: int, highlight_id: str, color: str) -> str:
    """Recolours a highlight; returns the script that updates the page."""
    from ankrimento import epub_reader

    return epub_reader.highlight_color(card_id, highlight_id, color)


def epub_highlight_style(card_id: int, highlight_id: str, style: str) -> str:
    """Restyles a highlight (underline, strike-out…); returns the script that updates the page."""
    from ankrimento import epub_reader

    return epub_reader.highlight_style(card_id, highlight_id, style)


def pdf_highlight_style(card_id: int, highlight_id: str, style: str) -> str:
    """Restyles a PDF highlight as the viewer does; returns the script that redraws them."""
    from ankrimento import pdf_reader

    return pdf_reader.highlight_style(card_id, highlight_id, style)


def pdf_bookmark_comment(card_id: int, bookmark_id: str, comment: str) -> str:
    """Saves a PDF bookmark's comment; returns the script that updates the viewer's bookmarks."""
    from ankrimento import pdf_reader

    return pdf_reader.bookmark_comment(card_id, bookmark_id, comment)


def epub_highlight_note(card_id: int, highlight_id: str, note: str) -> str:
    from ankrimento import epub_reader

    return epub_reader.highlight_note(card_id, highlight_id, note)


def epub_delete_highlight(card_id: int, highlight_id: str, section_index: int) -> str:
    """Deletes a highlight; returns the script giving the shown chapter its remaining highlights."""
    from ankrimento import epub_reader

    return epub_reader.delete_highlight(card_id, highlight_id, section_index)


def epub_search(card_id: int, query: str) -> str:
    """The sections of the book matching ``query``: section, title and excerpt."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.search(card_id, query))


def epub_message(card_id: int, section_index: int, message: str) -> str:
    """Apply a message from the EPUB page script; may ask to navigate."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.handle(int(card_id), int(section_index), message))


def parent_context(card_id: int) -> str:
    """The card an extract came from, read-only: title and visible fields (``{}`` without a parent)."""
    from ankrimento import parent_context

    return _dumps(parent_context.load(int(card_id)))


def coverage_summary(card_id: int) -> str:
    """Viewed and marked read for a PDF or EPUB, with desktop's label (``{}`` for any other card)."""
    from ankrimento import coverage

    return _dumps(coverage.summary(int(card_id)))


def coverage_mark(card_id: int, mode: str, position: int, start: int, end: int) -> str:
    """Mark a page range or passage read ("mark") or unread ("unmark"); returns the new figures."""
    from ankrimento import coverage

    return _dumps(coverage.mark(int(card_id), str(mode), int(position), int(start), int(end)))


def epub_reading(card_id: int) -> str:
    """Reading settings desktop keeps per book (text scale, appearance) and the chapter titles."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.reading(int(card_id)))


def epub_set_text_scale(card_id: int, scale: float) -> float:
    """Desktop's text size control: stored per book, within upstream's limits; returns the stored scale."""
    from incremento.backend.epub_manager import set_epub_font_scale

    rt = runtime.current()
    return set_epub_font_scale(str(rt.addon_dir), rt.profile, int(card_id), float(scale))


def epub_set_appearance(card_id: int, mode: str) -> None:
    """Desktop's appearance choice ("original", "dark", "night"), stored per book."""
    from incremento.backend.epub_appearance import set_appearance

    rt = runtime.current()
    set_appearance(str(rt.addon_dir), rt.profile, int(card_id), str(mode))


def pdf_path(card_id: int) -> str:
    from ankrimento import pdf_reader

    return pdf_reader.pdf_path(int(card_id))


def pdf_start_script(card_id: int, file_url: str, worker_url: str) -> str:
    from ankrimento import pdf_reader

    return pdf_reader.start_script(int(card_id), file_url, worker_url)


def pdf_jump_script(
    card_id: int, file_url: str, worker_url: str, page: int, scroll: float, page_y: float, passage_json: str, back: bool
) -> str:
    """Starts the viewer at a followed link's place (``page_y`` < 0: none), its passage revealed, with a way back."""
    from ankrimento import pdf_reader

    jump = {
        "page": int(page),
        "scroll": float(scroll),
        "page_y": None if float(page_y) < 0 else float(page_y),
        "passage": json.loads(passage_json) if passage_json else None,
        "back": bool(back),
    }
    return pdf_reader.start_script(int(card_id), file_url, worker_url, jump)


def pdf_index_state(card_id: int) -> str:
    """The PDF's text index for find: "current", "empty", "stale" or "missing"."""
    from ankrimento import pdf_reader

    return pdf_reader.index_state(card_id)


def pdf_index_pages(card_id: int, pages_json: str) -> None:
    """Indexes the page texts the viewer extracted, through upstream's indexer."""
    from ankrimento import pdf_reader

    pdf_reader.index_pages(card_id, json.loads(pages_json))


def pdf_search(card_id: int, query: str) -> str:
    """The pages matching ``query``: page, snippet and the longer excerpt the viewer highlights."""
    from ankrimento import pdf_reader

    return _dumps(pdf_reader.search(card_id, query))


def pdf_marks(card_id: int) -> str:
    """The PDF's bookmarks and highlights, in page order."""
    from ankrimento import pdf_reader

    return _dumps(pdf_reader.marks(card_id))


def pdf_add_bookmark(card_id: int, page: int) -> str:
    """Bookmarks ``page``; returns the script that updates the viewer."""
    from ankrimento import pdf_reader

    return pdf_reader.add_bookmark(card_id, page)


def pdf_delete_bookmark(card_id: int, bookmark_id: str) -> str:
    from ankrimento import pdf_reader

    return pdf_reader.delete_bookmark(card_id, bookmark_id)


def pdf_highlight_color(card_id: int, highlight_id: str, color: str) -> str:
    """Recolours a highlight; returns the script that updates the viewer."""
    from ankrimento import pdf_reader

    return pdf_reader.highlight_color(card_id, highlight_id, color)


def pdf_highlight_note(card_id: int, highlight_id: str, note: str) -> str:
    from ankrimento import pdf_reader

    return pdf_reader.highlight_note(card_id, highlight_id, note)


def pdf_delete_highlight(card_id: int, highlight_id: str) -> str:
    from ankrimento import pdf_reader

    return pdf_reader.delete_highlight(card_id, highlight_id)


def pdf_page_links(card_id: int, page: int) -> str:
    """The page's links to other PDF pages, from either end (see ``pdf_links``)."""
    from ankrimento import pdf_links

    return _dumps(pdf_links.links(int(card_id), int(page)))


def pdf_page_link_target(card_id: int, page: int, link_id: int, direction: str) -> str:
    """Where a page link opens; raises when its document or page is unavailable."""
    from ankrimento import pdf_links

    return _dumps(pdf_links.target(int(card_id), int(page), int(link_id), str(direction)))


def pdf_page_link_remove(card_id: int, page: int, link_id: int) -> bool:
    from ankrimento import pdf_links

    return pdf_links.remove(int(card_id), int(page), int(link_id))


def pdf_page_link_limit(card_id: int) -> int:
    """The last page of ``card_id`` a link may point to."""
    from ankrimento import pdf_links

    return pdf_links.limit(int(card_id))


def pdf_page_link_add(
    source_card_id: int,
    source_page: int,
    source_y: float | None,
    target_card_id: int,
    target_page: int,
    target_scroll: float,
    target_y: float | None,
) -> int:
    from ankrimento import pdf_links

    return pdf_links.add(source_card_id, source_page, source_y, target_card_id, target_page, target_scroll, target_y)


def pdf_message(card_id: int, message: str) -> str:
    """Apply a ``pycmd`` message from the PDF viewer; returns JavaScript to run."""
    from ankrimento import pdf_reader

    return "\n".join(pdf_reader.handle(int(card_id), message))


def extract_cloze(source_card_id: int, before: str, selected: str, after: str) -> None:
    """A cloze from the reader's selection (see ``extract.cloze``); the host shows the extract sheet."""
    from aqt import mw

    from ankrimento import extract

    extract.cloze(int(source_card_id), str(before), str(selected), str(after))
    mw.notify_host("extract_draft", card_id=int(source_card_id))


def extract_draft(source_card_id: int) -> str:
    """The pending extract from a document, with the dialog's defaults."""
    from ankrimento import extract

    return _dumps(extract.draft(int(source_card_id)))


def create_extract(source_card_id: int, fields_json: str, note_type: str, kind: str, priority: float) -> str:
    """Add the extract as a note linked to its source document.

    Returns the new note id and JavaScript refreshing the reader's page cards.
    """
    from ankrimento import extract, pdf_reader

    source_card_id = int(source_card_id)
    page = extract.draft(source_card_id)["page"] or 1
    note_id = extract.create(source_card_id, list(json.loads(fields_json)), note_type, kind, float(priority))
    script = pdf_reader.page_cards_script(source_card_id, page) if card_reader(source_card_id) == "pdf" else ""
    return _dumps({"note_id": note_id, "script": script})


def discard_extract(source_card_id: int) -> None:
    from ankrimento import extract

    extract.discard(int(source_card_id))


def new_card_defaults() -> str:
    """What a card from scratch starts with: upstream's note type and deck, the item/topic choice, priority 50."""
    from ankrimento import extract

    return _dumps(extract.scratch_defaults())


def create_card(fields_json: str, note_type: str, kind: str, priority: float, deck_name: str) -> int:
    """Adds a card from scratch with a priority, as desktop's Add Card dock does outside an extract."""
    from ankrimento import extract

    return extract.create_card(list(json.loads(fields_json)), note_type, kind, float(priority), deck_name)


def extract_batch_preview(text: str) -> str:
    """Desktop's parsing of pasted Q/A pairs (see ``extract.batch_preview``)."""
    from ankrimento import extract

    return _dumps(extract.batch_preview(str(text)))


def extract_batch_labels() -> str:
    from ankrimento import extract

    return _dumps(extract.batch_labels())


def extract_batch_create(source_card_id: int, text: str, note_type: str, kind: str, priority: float, deck_name: str) -> str:
    """One card per valid pair; ``source_card_id`` 0 makes them from scratch in ``deck_name``."""
    from ankrimento import extract

    return _dumps(extract.create_batch(int(source_card_id), str(text), str(note_type), str(kind), float(priority), str(deck_name)))


def queue_extract(source_card_id: int, fields_json: str, note_type: str, kind: str, priority: float) -> int:
    """Sets the extract aside to add later ("Add later"); returns how many are waiting."""
    from ankrimento import extract

    return extract.queue(int(source_card_id), list(json.loads(fields_json)), note_type, kind, float(priority))


def extract_queue(source_card_id: int) -> str:
    """The extracts waiting to be added from a document."""
    from ankrimento import extract

    return _dumps(extract.queued(int(source_card_id)))


def drop_queued_extract(source_card_id: int, index: int) -> None:
    from ankrimento import extract

    extract.drop_queued(int(source_card_id), int(index))


def discard_extract_queue(source_card_id: int) -> None:
    from ankrimento import extract

    extract.discard_queue(int(source_card_id))


def add_queued_extracts(source_card_id: int) -> str:
    """Adds the waiting extracts; how many, and JavaScript refreshing the PDF page's cards."""
    from ankrimento import extract, pdf_reader

    source_card_id = int(source_card_id)
    pages = extract.add_queued(source_card_id)
    is_pdf = card_reader(source_card_id) == "pdf"
    script = "".join(pdf_reader.page_cards_script(source_card_id, page or 1) for page in sorted(set(pages))) if is_pdf else ""
    return _dumps({"added": len(pages), "script": script})


def automatic_backup_plan(held_json: str, keep: int) -> str:
    """The automatic backup to write and those to remove, by upstream's rotation, from the folder's files."""
    from ankrimento import backup

    return _dumps(backup.automatic_plan([(str(name), int(ms)) for name, ms in json.loads(held_json)], int(keep)))


def export_full_backup(out_path: str) -> str:
    """Writes desktop's full backup ZIP of this profile to ``out_path``: cards, files and counts."""
    from ankrimento import backup

    return _dumps(backup.export(out_path))


def restore_stage(path: str) -> str:
    """Checks a full backup and stages it; nothing changes yet. What it holds, for confirmation."""
    from ankrimento import backup

    return _dumps(backup.stage_restore(path))


def restore_replace_files() -> str:
    """Puts the staged Incremento files in place; returns the Anki package for the collection restore."""
    from ankrimento import backup

    return backup.replace_files()


def restore_finish(succeeded: bool) -> None:
    """Keeps the restored files after the collection restore, or puts this device's back."""
    from ankrimento import backup

    backup.finish_restore(succeeded)


def restore_discard() -> None:
    """Drops a staged restore the user did not confirm."""
    from ankrimento import backup

    backup.discard_restore()


def engine_sync(store: Any, label: str, publish_only: bool = False, budget_seconds: float | None = None) -> str:
    """Sync Incremento's data with the sync folder ``store`` through the sync engine.

    ``label`` names the folder (its URI): the profile is set up for it the first time, or when the user picked
    another folder. Joining a library of another Anki collection raises ``SyncSetupError``. Returns the result as
    JSON: what was sent and received, how many records changed on two devices (this device's value is kept, both
    versions are saved), and files in the folder that were skipped. With ``publish_only`` this device's changes
    are sent and nothing is received: quick, for a screen that is closing. With ``budget_seconds`` a call transfers
    files for about that long and reports ``more`` when it stopped early; calling again continues.
    """
    from incremento.backend import sync_service

    rt = runtime.current()
    addon_dir, profile, query = str(rt.addon_dir), rt.profile, rt.col.db.all
    status = sync_service.status(addon_dir, profile)
    if not status.configured or status.folder != label:
        sync_service.configure(addon_dir, profile, label, query=query, store=store)
    report = sync_service.sync_now(addon_dir, profile, query, store=store, publish_only=publish_only,
                                   budget_seconds=budget_seconds)
    return _dumps(
        {
            "sent": report.published,
            "received": report.received,
            "applied": report.applied,
            "conflicts": sync_service.status(addon_dir, profile).conflicts,
            "problems": report.problems,
            "skipped": report.skipped,
            "more": report.more,
        }
    )


def engine_sync_hub(code: str, publish_only: bool = False, budget_seconds: float | None = None) -> str:
    """Sync through a hub on the network, paired with ``code`` (see ``incremento.backend.sync_http``).

    Raises ``ValueError`` for a code that is not a pairing code and ``StoreError`` when the hub cannot be reached or
    does not accept this device. The folder label is the device's identity and a hash of its key, so it survives the
    hub's address changing and changes when the device is paired with another hub.
    """
    import hashlib

    from incremento.backend.sync_http import HttpStore, Pairing

    pairing = Pairing.from_code(code)
    label = f"hub:{pairing.identity}:{hashlib.sha256(pairing.key).hexdigest()[:8]}"
    store = HttpStore.from_pairing(pairing)
    try:
        return engine_sync(store, label, publish_only, budget_seconds)
    finally:
        store.close()


def get_priority(card_id: int) -> float:
    from incremento.backend.priority_manager import get_priority as _get_priority

    rt = runtime.current()
    return float(_get_priority(str(rt.addon_dir), rt.profile, int(card_id)))


def set_priority(card_id: int, priority: float) -> None:
    from incremento.backend.priority_manager import set_priority as _set_priority

    rt = runtime.current()
    _set_priority(str(rt.addon_dir), rt.profile, int(card_id), float(priority))


def pdf_choose_color(card_id: int, data_json: str, selected: str) -> str:
    """Answers the PDF viewer's colour button with the phone's choice (see ``pdf_reader.choose_color``)."""
    from ankrimento import pdf_reader

    return pdf_reader.choose_color(int(card_id), json.loads(str(data_json) or "{}"), str(selected or ""))


def pdf_highlight_card(card_id: int, highlight_id: str) -> str:
    """The viewer's card button on a highlight (see ``pdf_cards.highlight_card``); a draft is shown by the host."""
    from aqt import mw

    from ankrimento import pdf_cards

    result = pdf_cards.highlight_card(int(card_id), str(highlight_id))
    if result.get("draft"):
        mw.notify_host("extract_draft", card_id=int(card_id))
    return _dumps(result)


def pdf_document_cards(card_id: int) -> str:
    """The browser search for the cards made from this PDF, or desktop's message when there are none."""
    from ankrimento import pdf_cards

    return _dumps(pdf_cards.cards_search(pdf_cards.document_note_ids(int(card_id)), "reader_pdf_no_cards"))


def pdf_note_cards(note_ids_json: str, scope: str) -> str:
    """The browser search for notes the viewer lists (a page's cards), or desktop's message."""
    from ankrimento import pdf_cards

    try:
        note_ids = json.loads(str(note_ids_json or "[]"))
    except ValueError:
        note_ids = []
    empty = "reader_pdf_no_page_cards" if scope == "page" else "reader_pdf_no_cards"
    return _dumps(pdf_cards.cards_search(note_ids if isinstance(note_ids, list) else [], empty))


def note_first_card(note_id: int) -> int:
    """The first card of a note, or 0 when the note is gone."""
    from ankrimento import pdf_cards

    return pdf_cards.first_card(int(note_id))


def pdf_highlight_cards_plan(card_id: int) -> str:
    """The viewer's "cards from highlights" button: what desktop's dialog would offer (``pdf_cards.bulk_plan``)."""
    from ankrimento import pdf_cards

    return _dumps(pdf_cards.bulk_plan(int(card_id)))


def pdf_highlight_cards_create(card_id: int, highlight_ids_json: str, note_type: str, kind: str) -> str:
    """One card per chosen highlight (``pdf_cards.bulk_create``); the summary desktop shows."""
    from ankrimento import pdf_cards

    ids = [str(h) for h in json.loads(str(highlight_ids_json or "[]"))]
    return _dumps(pdf_cards.bulk_create(int(card_id), ids, str(note_type), str(kind)))


def pdf_limit_settings(card_id: int) -> str:
    """The viewer's page limit button: desktop's dialog as data (``pdf_reader.limit_settings``)."""
    from ankrimento import pdf_reader

    return _dumps(pdf_reader.limit_settings(int(card_id)))


def pdf_save_limit(card_id: int, enabled: bool, daily_page_limit: int, mode: str) -> str:
    from ankrimento import pdf_reader

    return _dumps(pdf_reader.save_limit(int(card_id), bool(enabled), int(daily_page_limit), str(mode)))


def pdf_new_card(card_id: int) -> None:
    """The viewer's Add Card button (see ``pdf_cards.new_card``); the host shows the extract sheet."""
    from aqt import mw

    from ankrimento import pdf_cards

    pdf_cards.new_card(int(card_id))
    mw.notify_host("extract_draft", card_id=int(card_id))


def epub_snapshot(card_id: int, section_index: int, image: str) -> None:
    """A region of the chapter as a new card's image (see ``epub_reader.snapshot``)."""
    from ankrimento import epub_reader

    epub_reader.snapshot(int(card_id), int(section_index), str(image))


def import_local_video(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0) -> int:
    """A video file from the device as a video topic, kept as it is (see ``video_reader.import_local``)."""
    from ankrimento import video_reader

    return video_reader.import_local(str(path), str(title), str(deck_name), float(priority))


def video_subtitles(card_id: int) -> str:
    """The video's target and reference subtitles: label and cues (see ``video_reader.subtitles``)."""
    from ankrimento import video_reader

    return _dumps(video_reader.subtitles(int(card_id)))


def video_attach_subtitle(card_id: int, slot: str, path: str, label: str) -> None:
    from ankrimento import video_reader

    video_reader.attach_subtitle(int(card_id), str(slot), str(path), str(label))


def video_clear_subtitle(card_id: int, slot: str) -> None:
    from ankrimento import video_reader

    video_reader.clear_subtitle(int(card_id), str(slot))
