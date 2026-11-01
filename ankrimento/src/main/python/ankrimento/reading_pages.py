"""Pages read, recorded for today's statistics as desktop's readers record them.

Desktop's PDF and EPUB docks call ``timer_widget.record_pdf_page_read`` and
``record_epub_page_read``, which persist each page through the add-on's
``_persist_reader_page_stat`` (``statistics.record_reading_page``) with the
scheduler's day end; ``timer_widget`` imports Qt, so this is the same call.
"""

from __future__ import annotations

from ankrimento import runtime


def record(kind: str, card_id: int, page: int) -> None:
    """One page of a PDF (from 1) or EPUB (page index plus one), once per day; best effort, as on desktop."""
    try:
        from incremento.backend.scheduler_config import load_scheduler_config
        from incremento.backend.statistics import record_reading_page

        rt = runtime.current()
        day_end_time = str(load_scheduler_config().day_end_time or "04:00")
        record_reading_page(str(rt.addon_dir), rt.profile, kind, int(card_id), int(page), day_end_time=day_end_time)
    except Exception:
        # Reading must survive a supplemental statistics failure.
        return
