"""Reading coverage: what was viewed, and what was marked read, in PDFs and EPUBs.

Desktop's readers report every page or passage that comes into view and keep two figures apart: *Viewed* (seen, in
any order) and *Marked read* (said to be read). The recording is upstream's: ``frontend/reader_coverage.py``'s
``CoverageTracker`` validates and merges each report and calls ``backend/reader_coverage.py``, so the figures are the
ones desktop shows and the rows sync. This module only gives the tracker what desktop's docks give it, a document's
filename and chapter lengths, and runs its background work in place (the phone's calls already run off the main thread).
"""

from __future__ import annotations

from typing import Any

from ankrimento import runtime

_trackers: dict[tuple, Any] = {}


class _Finished:
    """A job run in place, shaped like the future the tracker's ``done`` callback reads."""

    def __init__(self, work) -> None:
        try:
            self._value, self._error = work(), None
        except Exception as error:  # the tracker reports it to the reader as an error
            self._value, self._error = None, error

    def result(self):
        if self._error is not None:
            raise self._error
        return self._value


def _run(work, done, uses_collection=False) -> None:
    done(_Finished(work))


def _pdf_pages(card_id: int) -> int:
    """Pages of the card's PDF (0 when unreadable)."""
    from ankrimento import pdf_reader

    try:
        from pypdf import PdfReader

        return len(PdfReader(pdf_reader.pdf_path(int(card_id)), strict=False).pages)
    except Exception:
        return 0


_page_counts: dict[tuple, int | None] = {}


def _count_pages(path) -> int | None:
    """Pages of a PDF, remembered per file so the Library does not open every PDF each time it is shown."""
    try:
        stat = path.stat()
        key = (str(path), stat.st_size, stat.st_mtime_ns)
    except OSError:
        return None
    if key not in _page_counts:
        try:
            from pypdf import PdfReader

            _page_counts[key] = len(PdfReader(str(path), strict=False).pages) or None
        except Exception:
            _page_counts[key] = None
    return _page_counts[key]


def library_figures(card_ids: list[int]) -> dict[int, dict[str, Any]]:
    """Viewed and marked read percent of each PDF or EPUB among ``card_ids``, as desktop's bookshelf computes them."""
    from incremento.backend.document_statistics import document_progress_from_collection

    if not card_ids:
        return {}
    rt = runtime.current()
    rows = document_progress_from_collection(
        rt.col, str(rt.addon_dir), rt.profile, card_ids=list(card_ids), pdf_page_counter=_count_pages
    )
    return {int(row["card_id"]): row for row in rows or ()}


def label(figures: dict | None) -> str:
    """Desktop's "Viewed 40% · Marked read 10%" for a figures row, or "" when it has none."""
    from incremento.frontend.reader_coverage import coverage_label

    return coverage_label(figures) if figures and figures.get("viewed_percent") is not None else ""


def _document(card_id: int) -> tuple[str, str, tuple[int, ...]] | None:
    """(kind, filename, chapter lengths) of a PDF or EPUB card, otherwise None."""
    note = runtime.current().col.get_card(int(card_id)).note()
    name = note.note_type()["name"]
    if name == "Incremento PDF":
        return "pdf", str(note["PDF_Filename"]), ()
    if name == "Incremento EPUB":
        from incremento.backend.document_statistics import _epub_lengths

        from ankrimento import epub_reader

        _, metadata = epub_reader._metadata(int(card_id))
        # Desktop's dock gives its tracker the length of each section's text, at least 1.
        lengths = _epub_lengths(metadata) or tuple(max(1, len(str(s.get("text") or ""))) for s in metadata.get("sections") or [])
        return "epub", str(note["EPUB_Filename"]), tuple(max(1, n) for n in lengths)
    return None


class _Box:
    """Holds what the tracker last replied."""

    latest: dict | None = None


def _tracker(card_id: int):
    from incremento.frontend.reader_coverage import CoverageTracker

    document = _document(card_id)
    if document is None:
        return None, None
    kind, filename, lengths = document
    rt = runtime.current()
    key = (str(rt.addon_dir), rt.profile, kind, int(card_id), filename, lengths)
    cached = _trackers.get(key)
    if cached is None:
        box = _Box()

        def reply(value: dict) -> None:
            box.latest = value

        tracker = CoverageTracker(
            str(rt.addon_dir), rt.profile, kind, int(card_id), filename,
            run=_run, current=lambda: True, visible=lambda: True, reply=reply, lengths=lengths,
        )
        if kind == "pdf":
            tracker.total = _pdf_pages(card_id)
        cached = _trackers[key] = (tracker, box)
    return cached


def _figures(value: dict | None) -> dict[str, Any]:
    """The summary with desktop's wording for it."""
    from incremento.frontend.reader_coverage import coverage_label

    if not value:
        return {}
    return {**value, "label": coverage_label(value)}


def summary(card_id: int) -> dict[str, Any]:
    """Viewed and marked read for a PDF or EPUB, with desktop's label; empty for any other card."""
    from incremento.backend.reader_coverage import coverage_summary

    document = _document(card_id)
    if document is None:
        return {}
    kind, filename, lengths = document
    rt = runtime.current()
    total = _pdf_pages(card_id) if kind == "pdf" else None
    return _figures(coverage_summary(str(rt.addon_dir), rt.profile, kind, int(card_id), filename, total=total, lengths=lengths))


def receive(card_id: int, data: Any, *, section_index: int | None = None, mode: str = "view") -> dict[str, Any] | None:
    """Record one report from a reader; the new figures, or None when the report is refused.

    ``section_index`` is the open chapter of an EPUB: a report for another chapter is not recorded, as on desktop.
    """
    if not isinstance(data, dict) or int(data.get("cardId", 0) or 0) != int(card_id):
        return None
    tracker, box = _tracker(card_id)
    if tracker is None or (section_index is not None and tracker.args[2] == "epub" and data.get("position") != section_index):
        return None
    box.latest = None
    if not tracker.receive(data, mode=mode) or not box.latest or box.latest.get("error"):
        return None
    return _figures(box.latest)


def mark(card_id: int, mode: str, position: int, start: int, end: int) -> dict[str, Any]:
    """Mark a page range or a passage read or unread (``mode`` "mark" or "unmark"); an EPUB range ending at 0 is the chapter."""
    tracker, box = _tracker(card_id)
    if tracker is None:
        return {}
    if tracker.args[2] == "epub" and end <= 0 and 0 <= position < len(tracker.lengths):
        end = tracker.lengths[position]
    sample = {"cardId": int(card_id), "position": int(position), "start": int(start), "end": int(end)}
    if tracker.args[2] == "pdf":
        sample["totalPages"] = tracker.total
    box.latest = None
    tracker.receive(sample, mode=mode)
    return _figures(box.latest) if box.latest and not box.latest.get("error") else summary(card_id)
