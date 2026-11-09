"""The update report (tools/upstream_report.py): its parsing, on small samples, so a new upstream shape is noticed here."""

from __future__ import annotations

import importlib.util
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "upstream_report", Path(__file__).resolve().parents[1] / "tools" / "upstream_report.py")
report = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(report)

ENTRIES = ("backend", "locales", "config.json", "frontend/epub_dock.py", "web/web_dock_bridge.js")


def test_a_file_is_shipped_when_it_is_an_entry_or_inside_a_shipped_folder():
    assert report.is_shipped("backend/session.py", ENTRIES)
    assert report.is_shipped("backend/sub/deep.py", ENTRIES)
    assert report.is_shipped("config.json", ENTRIES)
    assert report.is_shipped("frontend/epub_dock.py", ENTRIES)
    assert not report.is_shipped("frontend/epub_dialog.py", ENTRIES)
    assert not report.is_shipped("backend_notes.txt", ENTRIES)  # a prefix of the name is not the folder
    assert not report.is_shipped("tests/test_x.py", ENTRIES)


def test_the_shipped_entries_are_read_from_the_phones_addon_without_importing_it():
    entries = report.shipped_entries()

    assert "backend" in entries and "frontend/epub_dock.py" in entries


def test_schema_migrations_are_listed_by_number_and_name():
    text = '''
_SCHEMA_MIGRATIONS = (
    (2, "operation_lifecycle", _migration_2_operation_lifecycle),
    (25, "writing_links", _migration_25_writing_links),
    (26, "passages_and_coverage", _migration_26_passages_and_coverage),
)
'''
    assert report.migrations(text) == {2: "operation_lifecycle", 25: "writing_links", 26: "passages_and_coverage"}


def test_changelog_sections_are_the_added_headings_with_their_bullets():
    diff = '''--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -1,3 +1,9 @@
+## 4 November 2026 — 2026.11.04
+
+- Link PDF passages.
+- Track viewed pages.
+
+## 3 November 2026 — 2026.11.03
+- Add Last added sorting.
 ## 23 October 2026
'''
    assert report.changelog_sections(diff) == [
        ("4 November 2026 — 2026.11.04", ["Link PDF passages.", "Track viewed pages."]),
        ("3 November 2026 — 2026.11.03", ["Add Last added sorting."]),
    ]


def test_frontend_modules_a_file_imports_are_found_however_they_are_written():
    text = '''
try:
    from .epub_passages import EPUB_PASSAGE_SCRIPT
except ImportError:
    from frontend.epub_passages import EPUB_PASSAGE_SCRIPT
from ..backend.document_passages import normalize_passage
def f():
    from .reader_coverage import add_coverage_actions
    import frontend.writing_excerpt_ui
'''
    assert report.frontend_imports(text) == {"epub_passages", "reader_coverage", "writing_excerpt_ui"}


def test_names_imported_from_qt_and_aqt_are_collected_for_the_stand_ins():
    text = '''
from PyQt6.QtPdf import QPdfDocument
from PyQt6.QtCore import QSize, Qt
from aqt.utils import showInfo
import PyQt6.QtGui
from json import dumps
'''
    assert report.qt_imports(text) == {"PyQt6.QtPdf.QPdfDocument", "PyQt6.QtCore.QSize", "PyQt6.QtCore.Qt",
                                       "aqt.utils.showInfo", "PyQt6.QtGui"}


def test_translation_keys_are_the_msgids_of_a_catalog():
    po = 'msgid ""\nmsgstr ""\n\nmsgid "writing_table"\nmsgstr "Table"\n\nmsgid "Not a key"\nmsgstr "x"\n'

    assert report.message_keys(po) == {"writing_table"}


def test_a_stand_in_gap_is_a_new_qt_name_the_phone_has_no_stand_in_for():
    new = {"PyQt6.QtPdf.QPdfDocument", "PyQt6.QtGui.QImage", "aqt.utils.showInfo"}
    old = {"PyQt6.QtPdf.QPdfDocument"}
    provided = {"PyQt6.QtPdf": {"QPdfDocument"}, "aqt.utils": {"showInfo"}}

    assert report.stand_in_gaps(new, old, provided) == ["PyQt6.QtGui.QImage"]


def test_a_relative_import_inside_backend_is_a_backend_module_not_a_frontend_one():
    text = "from .reader_coverage import coverage_summary\nfrom frontend.reader_shell import x\n"

    assert report.frontend_imports(text, relative=False) == {"reader_shell"}
    assert report.frontend_imports(text) == {"reader_coverage", "reader_shell"}
