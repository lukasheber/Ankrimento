"""The PDF viewer's page limit button: desktop's dialog, as the phone shows it."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture
def pdf(rt, tmp_path):
    source = tmp_path / "Limited.pdf"
    source.write_bytes(_MINIMAL_PDF)
    return facade.import_pdf(str(source), "Limited", priority=50.0)


def test_the_limit_dialog_has_desktops_values_and_words(rt, pdf):
    dialog = json.loads(facade.pdf_limit_settings(pdf))
    assert dialog["enabled"] is False
    assert dialog["daily_page_limit"] == 10  # desktop's spin box starts at 10 when there is no limit
    assert dialog["enforcement_mode"] == "warning"
    assert dialog["labels"]["title"] == "PDF Reading Limit"
    assert [mode["key"] for mode in dialog["modes"]] == ["warning", "soft_lock", "hard_stop"]
    assert dialog["modes"][1]["label"] == "Soft lock + override"


def test_a_saved_limit_is_kept_and_the_viewer_told(rt, pdf):
    saved = json.loads(facade.pdf_save_limit(pdf, True, 12, "soft_lock"))
    assert saved["message"] == "PDF limit saved: 12 pages/day (Soft lock)."
    assert "incrementoReceivePdfLimitStatus" in saved["script"]
    dialog = json.loads(facade.pdf_limit_settings(pdf))
    assert (dialog["enabled"], dialog["daily_page_limit"], dialog["enforcement_mode"]) == (True, 12, "soft_lock")

    off = json.loads(facade.pdf_save_limit(pdf, False, 12, "soft_lock"))
    assert off["message"] == "PDF daily reading limit disabled."
    assert json.loads(facade.pdf_limit_settings(pdf))["enabled"] is False


def test_a_limit_out_of_range_is_kept_within_desktops(rt, pdf):
    json.loads(facade.pdf_save_limit(pdf, True, 99999, "nonsense"))
    dialog = json.loads(facade.pdf_limit_settings(pdf))
    assert dialog["daily_page_limit"] == 5000 and dialog["enforcement_mode"] == "warning"
