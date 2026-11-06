"""Video topics: what to play, where playback stopped, and watch time.

Desktop's video dock (``frontend/video_dock.py``, Qt) plays YouTube through
``web/video_player.html`` and downloaded videos with Qt's media player, saving
the position with ``set_video_position`` and observed playback with
``record_video_watch``. The host app plays the same sources and reports here.
"""

from __future__ import annotations

import html
import os
from typing import Any

from ankrimento import runtime

# record_video_watch accepts bounded samples.
_MAX_WATCH_SAMPLE_SECONDS = 300.0


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def open_video(card_id: int) -> dict[str, Any]:
    """``kind`` is "local" (a downloaded file), "youtube" or "none"."""
    from incremento.backend.video_manager import (
        extract_video_id,
        get_video_note_media,
        get_video_position,
        local_video_abspath,
    )

    addon_dir, profile = _context()
    note = runtime.current().col.get_card(int(card_id)).note()
    position = float(get_video_position(addon_dir, profile, int(card_id)) or 0.0)
    local = get_video_note_media(note)["local_video_file"]
    if local:
        path = local_video_abspath(addon_dir, profile, local)
        if path and os.path.isfile(path):
            return {"kind": "local", "path": path, "position": position}
    url = str(note["YouTube_URL"] or "")
    video_id = extract_video_id(url)
    if video_id:
        return {"kind": "youtube", "video_id": video_id, "url": url, "position": position}
    # Desktop plays any other supported provider (Vimeo) in the provider's own player page.
    from incremento.backend.video_providers import build_remote_video_watch_url, detect_video_provider

    if detect_video_provider(url) == "vimeo":
        player = build_remote_video_watch_url(url, start_sec=int(position))
        if player:
            return {"kind": "vimeo", "url": player, "position": position}
    return {"kind": "none", "url": url, "position": position}


def save_position(card_id: int, position: float, watched_seconds: float = 0.0) -> None:
    """Save where playback is and add observed playback to the day's watch time."""
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.statistics import record_video_watch
    from incremento.backend.video_manager import set_video_position

    addon_dir, profile = _context()
    set_video_position(addon_dir, profile, int(card_id), max(0.0, float(position)))
    watched = min(float(watched_seconds), _MAX_WATCH_SAMPLE_SECONDS)
    if watched > 0:
        record_video_watch(addon_dir, profile, int(card_id), watched, day_end_time=load_scheduler_config().day_end_time)


def extract_at(card_id: int, seconds: float) -> None:
    """Start an extract tied to this moment; mirrors ``frontend/video_dock.py:_do_video_add_card``:
    the front links back to the video at that second ("▶ <time> – <title>")."""
    from aqt import mw
    from incremento.backend.video_manager import fmt_time

    from ankrimento import extract

    card_id, t = int(card_id), float(seconds)
    note = runtime.current().col.get_card(card_id).note()
    title = note.fields[0][:60].strip() if note.fields else ""
    label = f"&#9654; {fmt_time(t)}" + (f" – {title}" if title else "")
    link = f"<a href=\"#\" onclick=\"pycmd('incremento_open_video:{card_id}:{t}')\" style=\"color:#4a90d9;\">{label}</a>"
    extract.add_html_to_draft(card_id, 0, link, page=int(t))
    mw.notify_host("extract_draft", card_id=card_id)


def import_video(url: str, title: str = "", deck_name: str = "Topics") -> int:
    """Add a YouTube/Vimeo link as an Incremento Video topic, as desktop's Add Video does."""
    from incremento.backend.video_manager import add_video_card, canonicalize_video_url

    canonical = canonicalize_video_url(url)
    return int(add_video_card(runtime.current().col, canonical, title or canonical, deck_name=deck_name))


def is_video_url(url: str) -> bool:
    """A link desktop adds as a video: any provider it supports (YouTube, Vimeo)."""
    from incremento.backend.video_providers import is_supported_video_url

    return bool(is_supported_video_url(url))


def bookmarks(card_id: int) -> list[dict[str, Any]]:
    """The video's bookmarks by time: desktop's reader bookmarks of kind "video", with its labels and comments."""
    from incremento.backend.reader_bookmarks import list_reader_bookmarks

    addon_dir, profile = _context()
    rows = [
        {
            "id": row["id"],
            "seconds": float((row.get("location") or {}).get("seconds") or 0.0),
            "label": row.get("label") or "",
            "comment": row.get("comment_text") or "",
        }
        for row in list_reader_bookmarks(addon_dir, profile, int(card_id), "video")
    ]
    return sorted(rows, key=lambda row: row["seconds"])


def add_bookmark(card_id: int, seconds: float) -> list[dict[str, Any]]:
    """Mirrors ``_add_current_video_bookmark``."""
    from incremento.backend.reader_bookmarks import add_reader_bookmark

    addon_dir, profile = _context()
    add_reader_bookmark(addon_dir, profile, int(card_id), "video", {"seconds": max(0.0, float(seconds))})
    return bookmarks(card_id)


def delete_bookmark(card_id: int, bookmark_id: str) -> list[dict[str, Any]]:
    from incremento.backend.reader_bookmarks import delete_reader_bookmark

    addon_dir, profile = _context()
    delete_reader_bookmark(addon_dir, profile, int(card_id), "video", str(bookmark_id))
    return bookmarks(card_id)


def bookmark_comment(card_id: int, bookmark_id: str, comment: str) -> list[dict[str, Any]]:
    """Mirrors ``_edit_video_bookmark_comment``."""
    from incremento.backend.reader_bookmarks import update_reader_bookmark_comment

    addon_dir, profile = _context()
    update_reader_bookmark_comment(addon_dir, profile, int(card_id), "video", str(bookmark_id), str(comment or ""))
    return bookmarks(card_id)


def clip(card_id: int, start: float, end: float, clip_path: str, duration: float) -> None:
    """A segment of the video, cut on the device into ``clip_path`` (an MP4), as a new card. Mirrors
    ``frontend/video_dock.py:_create_mobile_video_clip`` after its rendering: desktop's range limits, its
    media name, and ``_fill_mobile_video_clip_card``'s front (the clip's player and the link back)."""
    from aqt import mw
    from incremento.backend.video_manager import (
        add_mobile_video_clip_to_anki_media,
        fmt_time,
        mobile_video_clip_html,
        validate_mobile_video_clip_range,
    )

    from ankrimento import extract

    card_id = int(card_id)
    start, end = validate_mobile_video_clip_range(float(start), float(end), float(duration) if duration > 0 else None)
    note = runtime.current().col.get_card(card_id).note()
    title = note.fields[0].strip() if note.fields else ""
    name = add_mobile_video_clip_to_anki_media(
        runtime.current().col, clip_path, source_title=title, start_sec=start, end_sec=end
    )
    player = mobile_video_clip_html(name, start_sec=start, end_sec=end, source_title=title)
    label = f"&#9654; {fmt_time(start)}" + (f" – {html.escape(title[:60])}" if title else "")
    link = f"<a href=\"#\" onclick=\"pycmd('incremento_open_video:{card_id}:{float(start)}')\" style=\"color:#4a90d9;\">{label}</a>"
    extract.add_html_to_draft(card_id, 0, f"{player}<br>{link}", page=int(start))
    mw.notify_host("extract_draft", card_id=card_id)


def import_local(path: str, title: str, deck_name: str = "Topics", priority: float = 50.0) -> int:
    """A video file from the device as an Incremento Video topic. Mirrors desktop's Add Video from a file
    (``__init__.py``, ``source_mode == "local"``) with "Original quality (no re-encoding)", the one mode
    without FFmpeg: the file is copied into the profile's videos, the topic made, the import journaled."""
    from incremento.backend.note_metadata import build_incremento_metadata
    from incremento.backend.operation_journal import ImportOperation
    from incremento.backend.priority_manager import set_priority
    from incremento.backend.video_manager import add_video_card, import_local_video_file

    rt = runtime.current()
    addon_dir, profile = _context()
    operation = ImportOperation(addon_dir, profile, "video")
    try:
        relpath = import_local_video_file(
            addon_dir, profile, str(path), encode_mode="original", created_relpath_cb=operation.track_created_relpath
        )
        metadata = build_incremento_metadata(
            source_type="Video", source_title=title, source_link=relpath, content_id=operation.content_id
        )
        card_id = int(
            add_video_card(rt.col, "", title, deck_name=deck_name, local_video_file=relpath, metadata=metadata)
        )
        operation.bind_anki(card_id=card_id, note_id=int(rt.col.get_card(card_id).nid))
        operation.commit(storage_key=relpath)
    except Exception as error:
        operation.rollback(error_code=type(error).__name__)
        raise
    set_priority(addon_dir, profile, card_id, float(priority))
    return card_id
