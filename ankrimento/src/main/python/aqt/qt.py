"""The few ``aqt.qt`` names Incremento's backend uses without a GUI.

Only ``QTimer.singleShot`` is needed outside dialogs. Importing any Qt widget
fails with ``ImportError``, which is how desktop-only code paths are kept off
mobile.
"""

from __future__ import annotations

import threading
from collections.abc import Callable


class QTimer:
    @staticmethod
    def singleShot(msec: int, callback: Callable[[], None]) -> None:
        from aqt import mw

        def fire() -> None:
            mw.taskman.run_on_main(callback)

        if msec <= 0:
            fire()
            return
        timer = threading.Timer(max(0, msec) / 1000.0, fire)
        timer.daemon = True
        timer.start()
