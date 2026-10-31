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
        [{**{key: row.get(key) for key in keys}, "depth": depth(int(row["card_id"]))} for row in snapshot.ordered_rows]
    )


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

    from incremento.backend import webpage_markdown
    from incremento.backend.markdown_manager import add_markdown_card
    from incremento.backend.priority_manager import set_priority

    rt = runtime.current()
    page = webpage_markdown.fetch_webpage_markdown(str(url), content_scope="main")
    title = str(page.get("title") or "").strip() or str(url)
    with tempfile.TemporaryDirectory() as folder:
        stem = re.sub(r"[^\w\- ]+", "", title)[:80].strip() or "Web page"
        path = os.path.join(folder, f"{stem}.md")
        with open(path, "w", encoding="utf8") as file:
            file.write(page["markdown_document"])
        card_id = add_markdown_card(str(rt.addon_dir), rt.profile, rt.col, path, title, deck_name=deck_name)
    set_priority(str(rt.addon_dir), rt.profile, card_id, float(priority))
    return _dumps({"card_id": int(card_id), "title": title})


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


def library() -> str:
    """Every Incremento document, with kind, title, priority and reading position."""
    from ankrimento import library as documents

    return _dumps(documents.entries())


def card_title(card_id: int) -> str:
    """The title the knowledge tree shows for this card (its first field, as text)."""
    from incremento.backend.knowledge_tree import _card_title

    return _card_title(runtime.current().col.get_card(int(card_id)))


def video_extract(card_id: int, seconds: float) -> None:
    from ankrimento import video_reader

    video_reader.extract_at(int(card_id), float(seconds))


def writing_open(card_id: int) -> str:
    from ankrimento import writing

    return _dumps(writing.open_text(int(card_id)))


def writing_save(card_id: int, text: str) -> int:
    from ankrimento import writing

    return writing.save_text(int(card_id), text)


def create_writing(title: str) -> int:
    from incremento.backend.priority_manager import set_priority

    from ankrimento import writing

    rt = runtime.current()
    card_id = writing.create(title)
    set_priority(str(rt.addon_dir), rt.profile, card_id, 50.0)
    return card_id


def video_open(card_id: int) -> str:
    from ankrimento import video_reader

    return _dumps(video_reader.open_video(int(card_id)))


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


def web_extract(card_id: int, text: str) -> bool:
    from ankrimento import web_reader

    return web_reader.extract(int(card_id), text)


def epub_open(card_id: int, section_index: int = -1) -> str:
    """The chapter file to load (saved chapter when ``section_index`` < 0)."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.open_section(int(card_id), None if int(section_index) < 0 else int(section_index)))


def epub_script(card_id: int, section_index: int, nonce: str, scroll_ratio: float = -1.0) -> str:
    """The page script for a loaded chapter (saved position when ``scroll_ratio`` < 0)."""
    from ankrimento import epub_reader

    ratio = None if float(scroll_ratio) < 0 else float(scroll_ratio)
    return epub_reader.section_script(int(card_id), int(section_index), nonce, ratio)


def epub_message(card_id: int, section_index: int, message: str) -> str:
    """Apply a message from the EPUB page script; may ask to navigate."""
    from ankrimento import epub_reader

    return _dumps(epub_reader.handle(int(card_id), int(section_index), message))


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


def pdf_message(card_id: int, message: str) -> str:
    """Apply a ``pycmd`` message from the PDF viewer; returns JavaScript to run."""
    from ankrimento import pdf_reader

    return "\n".join(pdf_reader.handle(int(card_id), message))


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


def sync(store: Any, device_name: str, action: str = "sync") -> str:
    """Sync Incremento's data with the sync folder ``store`` (its profile folder).

    ``action``: ``"status"``, ``"sync"`` (pull or push as needed, then hold the
    lease), ``"keep_device"`` / ``"keep_folder"`` (resolve a conflict),
    ``"release"`` (push if needed and release the lease, when closing).
    Returns the resulting status.
    """
    from aqt import mw
    from incremento.backend.topic_scheduler import reset_topic_answer_runtime_state

    from ankrimento.syncfolder import SyncFolder

    rt = runtime.current()
    folder = SyncFolder(
        store,
        rt.addon_dir,
        rt.profile,
        read_config=lambda: dict(mw.addonManager.addonMeta("incremento").get("config", {})),
        write_config=lambda config: mw.addonManager.writeConfig("incremento", config),
        device_name=device_name,
    )
    status = folder.status()
    if action == "sync":
        status = folder.sync()
        if status.state in {"pull", "push"}:
            folder.acquire_lease()
    elif action == "keep_device":
        folder.push(force=True)
    elif action == "keep_folder":
        folder.pull(force=True)
    elif action == "release":
        if status.state == "push":
            folder.push(force=True)
        folder.release_lease()
    if action in {"sync", "keep_folder"} and status.state in {"pull", "conflict"}:
        # Incremento's in-memory answer state referred to the replaced database.
        reset_topic_answer_runtime_state()
    return _dumps(
        {"state": status.state, "remote_device": status.remote_device, "lease_holder": status.lease_holder}
    )


def get_priority(card_id: int) -> float:
    from incremento.backend.priority_manager import get_priority as _get_priority

    rt = runtime.current()
    return float(_get_priority(str(rt.addon_dir), rt.profile, int(card_id)))


def set_priority(card_id: int, priority: float) -> None:
    from incremento.backend.priority_manager import set_priority as _set_priority

    rt = runtime.current()
    _set_priority(str(rt.addon_dir), rt.profile, int(card_id), float(priority))
