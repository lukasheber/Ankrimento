"""``QPdfDocument`` stand-in that reports an empty document.

Upstream uses ``QPdfDocument`` as a fallback for PDF text extraction and
cover rendering. Reporting zero pages makes those paths return their normal
"nothing extracted" results. Android's ``PdfRenderer`` can back this later.
"""

from __future__ import annotations

from typing import Any


class _Selection:
    def isValid(self) -> bool:
        return False

    def text(self) -> str:
        return ""


class QPdfDocument:
    def __init__(self, parent: Any = None) -> None:
        self._path: str | None = None

    def load(self, path: str) -> None:
        self._path = path

    def pageCount(self) -> int:
        return 0

    def getAllText(self, page: int) -> _Selection:
        return _Selection()

    def close(self) -> None:
        self._path = None
