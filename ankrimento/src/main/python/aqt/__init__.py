"""Minimal stand-in for Anki desktop's ``aqt`` package.

Incremento's backend is written against Anki desktop. Ankrimento runs that
backend unchanged, so this package provides only the parts of ``aqt`` the
backend touches, backed by the host app (AnkiDroid) instead of Qt.

``mw`` exists from import time because upstream modules bind it with
``from aqt import mw``; :func:`ankrimento.runtime.start` attaches the
collection, profile and host callbacks to it later.
"""

from __future__ import annotations

from aqt.main import MainWindow

mw = MainWindow()
