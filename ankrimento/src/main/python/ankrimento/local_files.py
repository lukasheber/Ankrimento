"""Local file cards: any file kept in the profile and opened with another app.

Desktop's card (``backend/local_file_manager.py``) links one file, either by its path on that computer (``reference``)
or as a copy inside the profile (``managed_copy``). A phone cannot keep a path to a file outside its own folders, and a
path made on a computer means nothing here, so the phone only makes managed copies, and says so when a card made on
desktop points at a path: "Attach a copy" then turns it into a managed copy, which the sync engine shares with every
device. Importing, storing and relinking are upstream's functions; this module reads the card for the screen and adds
the limits a phone needs.
"""

from __future__ import annotations

import html
import mimetypes
import os
import re
from typing import Any

from ankrimento import runtime

#: The sync engine carries a file up to this size (``MAX_BLOB_BYTES``); a larger one could not reach the other devices.
MAX_BYTES = 512 * 1024 * 1024

#: Installable packages are kept like any file but never handed to another app from here.
BLOCKED_EXTENSIONS = {".apk": "package", ".apks": "package", ".xapk": "package", ".aab": "package"}

_TAG = re.compile(r"<[^>]*>")


def suggests(name: str) -> str:
    """"pdf" or "epub" when the file is better added as that kind of document, otherwise ""."""
    extension = os.path.splitext(str(name or ""))[1].lower()
    return {".pdf": "pdf", ".epub": "epub"}.get(extension, "")


def _plain(markup: str) -> str:
    """A field's text: upstream stores plain text as escaped HTML with <br> for line breaks."""
    return html.unescape(_TAG.sub("", re.sub(r"<br\s*/?>", "\n", str(markup or "")))).strip()


def _check(source_path: str) -> str:
    path = os.path.abspath(str(source_path or "").strip())
    if not os.path.isfile(path):
        raise FileNotFoundError("Selected file does not exist.")
    if os.path.getsize(path) > MAX_BYTES:
        raise ValueError(f"The file is too large to keep and share between devices (the limit is {MAX_BYTES // (1024 * 1024)} MB).")
    return path


def add(source_path: str, title: str, deck_name: str = "Topics", note_text: str = "") -> int:
    """A new local file card with a managed copy of the file."""
    from incremento.backend.local_file_manager import LOCAL_FILE_MODE_MANAGED_COPY, add_local_file_card

    rt = runtime.current()
    path = _check(source_path)
    return int(
        add_local_file_card(
            str(rt.addon_dir),
            rt.profile,
            rt.col,
            source_path=path,
            title=title or os.path.splitext(os.path.basename(path))[0],
            deck_name=deck_name,
            mode=LOCAL_FILE_MODE_MANAGED_COPY,
            note_text=note_text,
        )
    )


def info(card_id: int) -> dict[str, Any]:
    """What the card's screen shows: the file's name, where it is, whether this device has it, and whether to open it."""
    from incremento.backend import local_file_manager as manager

    rt = runtime.current()
    note = rt.col.get_card(int(card_id)).note()
    name = _plain(note[manager.LOCAL_FILE_NAME_FIELD])
    stored = _plain(note[manager.LOCAL_FILE_PATH_FIELD])
    mode = manager.normalize_local_file_mode(note[manager.LOCAL_FILE_MODE_FIELD])
    result: dict[str, Any] = {
        "card_id": int(card_id),
        "title": _plain(note["Title"]),
        "name": name,
        "mode": mode,
        "note": _plain(note[manager.LOCAL_FILE_NOTE_FIELD]),
        "available": False,
        "path": "",
        "original_path": "",
        "size": 0,
        "mime": "application/octet-stream",
        "can_open": False,
        "blocked": "",
    }
    if mode != manager.LOCAL_FILE_MODE_MANAGED_COPY:
        # A path on the computer that made the card: it means nothing on this device, even if it exists here.
        result["original_path"] = stored
        return result
    path = manager.managed_local_file_abspath(str(rt.addon_dir), rt.profile, stored)
    if not os.path.isfile(path):
        return result
    extension = os.path.splitext(name or path)[1].lower()
    blocked = BLOCKED_EXTENSIONS.get(extension, "")
    result.update(
        available=True,
        path=path,
        size=os.path.getsize(path),
        mime=mimetypes.guess_type(name or path)[0] or "application/octet-stream",
        can_open=not blocked,
        blocked=blocked,
    )
    return result


def attach(card_id: int, source_path: str) -> dict[str, Any]:
    """Keep a copy of ``source_path`` on this device as the card's file; a reference becomes a managed copy."""
    from incremento.backend import local_file_manager as manager

    rt = runtime.current()
    path = _check(source_path)
    note = rt.col.get_card(int(card_id)).note()
    # Upstream's relink follows the note's mode: made a managed copy first, so the new file is copied into the profile.
    note[manager.LOCAL_FILE_MODE_FIELD] = manager.LOCAL_FILE_MODE_MANAGED_COPY
    manager.relink_local_file(str(rt.addon_dir), rt.profile, note, new_source_path=path)
    rt.col.update_note(note)
    return info(card_id)
