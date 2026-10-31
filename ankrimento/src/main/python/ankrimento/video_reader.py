"""Video topics: what to play, where playback stopped, and watch time.

Desktop's video dock (``frontend/video_dock.py``, Qt) plays YouTube through
``web/video_player.html`` and downloaded videos with Qt's media player, saving
the position with ``set_video_position`` and observed playback with
``record_video_watch``. The host app plays the same sources and reports here.
"""

from __future__ import annotations

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


def import_video(url: str, title: str = "", deck_name: str = "Topics") -> int:
    """Add a YouTube/Vimeo link as an Incremento Video topic, as desktop's Add Video does."""
    from incremento.backend.video_manager import add_video_card, canonicalize_video_url

    canonical = canonicalize_video_url(url)
    return int(add_video_card(runtime.current().col, canonical, title or canonical, deck_name=deck_name))


def is_video_url(url: str) -> bool:
    from incremento.backend.video_manager import extract_video_id

    return bool(extract_video_id(url))
