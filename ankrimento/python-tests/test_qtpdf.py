"""The ``QPdfDocument`` stand-in: what desktop's PDF checks ask of Qt, answered without Qt.

Desktop checks a PDF before importing it (header, page tree, first page) and, without PyMuPDF, asks Qt for the page
count and for a password error. The phone has neither, so the stand-in answers from the file itself. Text and page
images stay unavailable: the reader's pdf.js and the phone's own page text do that work.
"""

from __future__ import annotations

import io

import pytest
from pypdf import PdfWriter

from test_pdf import _MINIMAL_PDF


def _pdf(tmp_path, name="a.pdf", *, pages=1, user_password=None, owner_password=None):
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(width=200, height=200)
    if owner_password is not None or user_password is not None:
        writer.encrypt(user_password=user_password or "", owner_password=owner_password or "owner")
    buffer = io.BytesIO()
    writer.write(buffer)
    path = tmp_path / name
    path.write_bytes(buffer.getvalue())
    return str(path)


def _load(path):
    from PyQt6.QtPdf import QPdfDocument

    document = QPdfDocument(None)
    document.load(path)
    return document


def test_a_pdf_reports_its_pages_and_no_error(tmp_path):
    from PyQt6.QtPdf import QPdfDocument

    document = _load(_pdf(tmp_path, pages=7))

    assert document.pageCount() == 7
    assert document.error() == QPdfDocument.Error.None_


def test_the_minimal_test_pdf_has_one_page(tmp_path):
    path = tmp_path / "min.pdf"
    path.write_bytes(_MINIMAL_PDF)

    assert _load(str(path)).pageCount() == 1


def test_a_file_that_is_not_a_pdf_has_no_pages_and_says_why(tmp_path):
    from PyQt6.QtPdf import QPdfDocument

    path = tmp_path / "bad.pdf"
    path.write_bytes(b"%PDF-1.7\nthis is not really a pdf")

    document = _load(str(path))

    assert document.pageCount() == 0
    assert document.error() == QPdfDocument.Error.InvalidFileFormat


def test_a_password_protected_pdf_asks_for_the_password(tmp_path):
    from PyQt6.QtPdf import QPdfDocument

    document = _load(_pdf(tmp_path, user_password="secret"))

    assert document.error() == QPdfDocument.Error.IncorrectPassword
    assert document.pageCount() == 0


def test_a_pdf_with_only_an_owner_password_opens_like_it_does_in_qt(tmp_path):
    from PyQt6.QtPdf import QPdfDocument

    document = _load(_pdf(tmp_path, owner_password="owner", pages=3))

    assert document.error() == QPdfDocument.Error.None_
    assert document.pageCount() == 3


def test_a_missing_file_is_reported_as_missing(tmp_path):
    from PyQt6.QtPdf import QPdfDocument

    document = _load(str(tmp_path / "nothing.pdf"))

    assert document.error() == QPdfDocument.Error.FileNotFound
    assert document.pageCount() == 0


def test_text_and_images_stay_unavailable_and_close_forgets_the_file(tmp_path):
    document = _load(_pdf(tmp_path, pages=2))

    assert document.getAllText(0).isValid() is False and document.getAllText(0).text() == ""
    document.close()
    assert document.pageCount() == 0


def test_desktops_import_check_accepts_a_good_pdf_and_refuses_bad_ones(rt, tmp_path):
    from incremento.backend import pdf_manager

    good = _pdf(tmp_path, pages=4)
    corrupt = tmp_path / "corrupt.pdf"
    corrupt.write_bytes(b"%PDF-1.4\n1 0 obj <<>> endobj\n")
    locked = _pdf(tmp_path, "locked.pdf", user_password="secret")

    assert pdf_manager.validate_pdf_file(good) == 4
    with pytest.raises(pdf_manager.PdfValidationError):
        pdf_manager.validate_pdf_file(str(corrupt))
    with pytest.raises(pdf_manager.PdfValidationError) as raised:
        pdf_manager.validate_pdf_file(locked)
    assert "password" in str(raised.value).lower()
