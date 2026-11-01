"""The web reader: a page in a WebView with desktop's web bridge script.

Desktop's web dock (``frontend/web_dock.py``, Qt) injects ``web/web_dock_bridge.js``
into the page; it reports reading progress through ``console.log`` and
restores the saved position with ``incrementoApplyRestoreState``. The host
app injects the script from :func:`bridge_script`, passes each console
message to :func:`handle` and offers selections to :func:`extract`.
"""

from __future__ import annotations

import json
import secrets
from typing import Any

from aqt import mw

from ankrimento import runtime

MSG_PREFIX = "__incremento_webdock_pycmd__:"  # upstream's _PYCMD_BRIDGE in web_dock.py
MSG_PROGRESS = "incremento_web_progress:"


def _context() -> tuple[str, str]:
    rt = runtime.current()
    return str(rt.addon_dir), rt.profile


def _config() -> dict[str, Any]:
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, "incremento")


def open_page(card_id: int) -> dict[str, Any]:
    """The page to load (the last one read, else the card's URL) and a bridge nonce."""
    from incremento.backend.web_manager import get_web_url

    addon_dir, profile = _context()
    note = runtime.current().col.get_card(int(card_id)).note()
    url = get_web_url(addon_dir, profile, int(card_id)) or str(note["URL"])
    return {"url": url, "nonce": secrets.token_hex(8)}


def bridge_script(card_id: int, nonce: str, current_url: str) -> str:
    """Desktop's bridge plus its restore call; mirrors ``_build_web_bridge_js``
    and ``_apply_web_restore_state``."""
    from incremento.backend.web_manager import (
        build_web_restore_payload,
        configured_remember_browser_card_scroll,
        get_web_progress,
    )

    addon_dir, profile = _context()
    template = (runtime.current().addon_dir / "web" / "web_dock_bridge.js").read_text(encoding="utf8")
    script = (
        template.replace("__PYCMD_PREFIX__", json.dumps(f"{MSG_PREFIX}{nonce}:"))
        .replace("__CARD_ID__", str(max(0, int(card_id))))
        .replace("__MSG_PROGRESS__", json.dumps(MSG_PROGRESS))
    )
    payload = build_web_restore_payload(
        get_web_progress(addon_dir, profile, int(card_id)),
        current_url,
        allow_bookmark=True,
        allow_scroll=True,
        remember_scroll=configured_remember_browser_card_scroll(_config()),
    )
    return script + f"\nwindow.incrementoApplyRestoreState && window.incrementoApplyRestoreState({json.dumps(payload)});"


def handle(card_id: int, msg: str) -> None:
    """Apply one bridge message (prefix and nonce removed); mirrors ``_persist_web_scroll``."""
    from incremento.backend.web_manager import (
        configured_remember_browser_card_scroll,
        set_web_scroll_position,
        set_web_url,
    )

    if not msg.startswith(MSG_PROGRESS):
        return
    data = json.loads(msg[len(MSG_PROGRESS):])
    url = str((data or {}).get("url") or "").strip()
    if not url or url == "about:blank":
        return
    addon_dir, profile = _context()
    set_web_url(addon_dir, profile, int(card_id), url)
    if configured_remember_browser_card_scroll(_config()):
        ratio = max(0.0, min(float(data.get("scrollRatio", 0.0) or 0.0), 1.0))
        set_web_scroll_position(addon_dir, profile, int(card_id), url, ratio)


def extract(card_id: int, text: str, url: str = "") -> bool:
    """Start an extract from selected page text; False when nothing is selected.

    With the page's ``url``, the extract links back to it as desktop's does (``WebController.citation``,
    added by ``fill_dock_field`` unless the extract source links setting turns web links off).
    """
    from ankrimento import extract as extracts

    text = str(text or "").strip()
    if not text:
        return False
    citation = _citation(int(card_id), str(url or "").strip()) if extracts.source_link_on("web") else ""
    extracts.add_with_citation(int(card_id), 0, {"text": text}, citation, page=0)
    mw.notify_host("extract_draft", card_id=int(card_id))
    return True


def _citation(card_id: int, url: str) -> str:
    """Mirrors ``WebController.citation`` and ``_source_url_label`` in frontend/web_dock.py."""
    from html import escape
    from urllib.parse import quote, urlparse

    if not url or not card_id:
        return ""
    try:
        parsed = urlparse(url)
        label = f"{parsed.netloc}{parsed.path or ''}" + (f"#{parsed.fragment}" if parsed.fragment else "")
    except ValueError:
        label = url
    label = label.strip() or url
    label = label if len(label) <= 72 else label[:69] + "..."
    command = f"incremento_open_web:{card_id}:{quote(url, safe='')}"
    return (
        f"<a onclick=\"pycmd('{command}'); return false;\" "
        f"style=\"cursor:pointer; color:#4a90d9; text-decoration:none;\">"
        f"{escape(label, quote=False)}</a>"
    )
