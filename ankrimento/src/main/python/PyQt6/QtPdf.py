"""``QPdfDocument`` stand-in: what desktop's PDF checks ask of Qt, answered from the file itself.

Upstream asks Qt for three things:

* before importing a PDF (``pdf_manager.validate_pdf_file``) and without PyMuPDF: whether the file opens, how many
  pages it has, and whether it needs a password (``load``, ``error``, ``pageCount``);
* as a fallback for text extraction: the text of each page (``getAllText``);
* for covers: a rendered page (``render``).

The first is answered with ``pypdf`` (pure Python), so a corrupt or locked PDF is refused before it is imported, as on
desktop. Text and page images stay unavailable: the reader's pdf.js and the phone's own page text do that work, so those
paths return their normal "nothing extracted" results. Android's ``PdfRenderer`` can back them later.
"""

from __future__ import annotations

import enum
import re
from typing import Any


class _Selection:
    def isValid(self) -> bool:
        return False

    def text(self) -> str:
        return ""


_PAGE_OBJECT = re.compile(rb"/Type\s*/Page(?![A-Za-z])")


def _count_page_objects(path: str) -> int:
    try:
        with open(path, "rb") as stream:
            data = stream.read(64 * 1024 * 1024)
    except OSError:
        return 0
    return len(_PAGE_OBJECT.findall(data)) if data.startswith(b"%PDF-") or b"%PDF-" in data[:1024] else 0


class QPdfDocument:
    class Error(enum.Enum):
        """The names of Qt's ``QPdfDocument.Error``; upstream compares against ``IncorrectPassword``."""

        None_ = 0
        Unknown = 1
        DataNotYetAvailable = 2
        FileNotFound = 3
        InvalidFileFormat = 4
        IncorrectPassword = 5
        UnsupportedSecurityScheme = 6

    def __init__(self, parent: Any = None) -> None:
        self._pages = 0
        self._error = QPdfDocument.Error.None_

    def load(self, path: str) -> Any:
        self._pages, self._error = 0, QPdfDocument.Error.None_
        try:
            from pypdf import PdfReader
            from pypdf.errors import DependencyError

            with open(path, "rb") as stream:
                reader = PdfReader(stream, strict=False)
                if reader.is_encrypted and not reader.decrypt(""):
                    # Locked with a password we do not have. (A file with only an owner password opens, as in Qt.)
                    self._error = QPdfDocument.Error.IncorrectPassword
                    return self._error
                self._pages = len(reader.pages)
        except FileNotFoundError:
            self._error = QPdfDocument.Error.FileNotFound
        except DependencyError:
            self._error = QPdfDocument.Error.UnsupportedSecurityScheme  # AES needs a crypto library the phone lacks
        except Exception:
            # pypdf wants an intact cross-reference table; Qt, MuPDF and pdf.js rebuild a damaged one. Accept what
            # they would: a file with page objects in it (uncompressed ones; a damaged compressed file is refused).
            self._pages = _count_page_objects(path)
            if self._pages == 0:
                self._error = QPdfDocument.Error.InvalidFileFormat
        if self._error is not QPdfDocument.Error.None_ or self._pages < 0:
            self._pages = 0
        return self._error

    def error(self) -> Error:
        return self._error

    def pageCount(self) -> int:
        return self._pages

    def getAllText(self, page: int) -> _Selection:
        return _Selection()

    def close(self) -> None:
        self._pages, self._error = 0, QPdfDocument.Error.None_
