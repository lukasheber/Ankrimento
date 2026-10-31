"""Titles for shared links, as the page or the video service names them.

A link shared from another app usually arrives without a title. Desktop's
Add Video / Web Page dialogs ask for one; on a phone the title is looked up
instead: YouTube's public oEmbed for videos, the page's own ``<title>`` for
anything else. Requests go through upstream's public-HTTP guard. Any failure
gives "" (the caller keeps the URL as the title).
"""

from __future__ import annotations

import html
import json
import re
from urllib.parse import quote
from urllib.request import Request

_YOUTUBE = re.compile(r"(?:^|//)(?:www\.|m\.)?(?:youtube\.com|youtu\.be)/", re.IGNORECASE)
_TITLE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)
_MAX_BYTES = 512_000
_TIMEOUT_SEC = 8.0


def _fetch(url: str) -> str:
    from incremento.backend.network_safety import open_public_http, read_response_limited

    request = Request(url, headers={"User-Agent": "Mozilla/5.0 (Android) Ankrimento", "Accept": "text/html,application/json"})
    with open_public_http(request, timeout=_TIMEOUT_SEC) as response:
        return read_response_limited(response, max_bytes=_MAX_BYTES).decode("utf-8", errors="replace")


def title(url: str) -> str:
    try:
        if _YOUTUBE.search(url):
            data = json.loads(_fetch("https://www.youtube.com/oembed?format=json&url=" + quote(url, safe="")))
            return " ".join(str(data.get("title") or "").split())
        match = _TITLE.search(_fetch(url))
        return " ".join(html.unescape(match.group(1)).split()) if match else ""
    except Exception:  # noqa: BLE001 - any failure keeps the URL as the title
        return ""
