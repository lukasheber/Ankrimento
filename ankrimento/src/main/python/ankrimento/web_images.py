"""Images for web articles imported as documents.

Upstream's page-to-Markdown converter (``backend/webpage_markdown.py``) writes
each image as a link, ``[Image: alt](<url>)``; its Markdown import
(``backend/markdown_manager.py``) stores local images placed beside the
document. The phone downloads each linked image beside the Markdown file
first, through upstream's public-network fetch, so upstream's import keeps it
with the document (and desktop shows it too). An image that cannot be
fetched, or is not a picture upstream accepts, stays the link upstream wrote.
"""

from __future__ import annotations

import re
from pathlib import Path

_IMAGE_LINK = re.compile(r"\[Image: (?P<alt>[^\]\n]*)\]\(<(?P<url>https?://[^>\s]+)>\)")
FOLDER = "web-images"
_TIMEOUT_SECONDS = 15.0


def _download(url: str) -> bytes:
    from urllib.request import Request

    from incremento.backend.markdown_manager import MAX_IMAGE_BYTES
    from incremento.backend.network_safety import open_public_http, read_response_limited

    request = Request(url, headers={"User-Agent": "Mozilla/5.0 (Android) Ankrimento", "Accept": "image/*"})
    with open_public_http(request, timeout=_TIMEOUT_SECONDS) as response:
        return read_response_limited(response, max_bytes=MAX_IMAGE_BYTES)


def localize(markdown: str, folder: Path) -> str:
    """The Markdown with each downloadable image link made a local image under ``folder``,
    within upstream's import limits (count, size each, size in all)."""
    from incremento.backend.markdown_manager import MAX_IMAGE_BYTES, MAX_IMAGES, MAX_TOTAL_IMAGE_BYTES, _image_type

    saved: dict[str, str | None] = {}
    total = 0

    def local(match: re.Match) -> str:
        nonlocal total
        url = match.group("url")
        if url not in saved:
            saved[url] = None
            if sum(1 for name in saved.values() if name) < MAX_IMAGES:
                try:
                    data = _download(url)
                except Exception:  # noqa: BLE001 - an image that can't be fetched stays a link
                    data = b""
                kind = _image_type(data) if data else None
                if kind and len(data) <= MAX_IMAGE_BYTES and total + len(data) <= MAX_TOTAL_IMAGE_BYTES:
                    name = f"image-{sum(1 for n in saved.values() if n) + 1}.{kind[0]}"
                    (folder / FOLDER).mkdir(exist_ok=True)
                    (folder / FOLDER / name).write_bytes(data)
                    total += len(data)
                    saved[url] = f"{FOLDER}/{name}"
        target = saved[url]
        return f"![{match.group('alt')}]({target})" if target else match.group(0)

    return _IMAGE_LINK.sub(local, markdown)
