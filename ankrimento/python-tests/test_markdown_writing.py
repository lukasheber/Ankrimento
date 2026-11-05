"""Markdown documents and the writing editor's formatting, as desktop's writing dock does them."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade


def test_a_markdown_file_becomes_a_study_document_read_as_an_epub(rt, tmp_path):
    path = tmp_path / "Notes on memory.md"
    path.write_text("# Memory\n\nSpacing beats cramming.\n\n## Forgetting\n\nThe curve falls fast.\n", encoding="utf8")

    card_id = facade.import_markdown(str(path), "Notes on memory", "Topics", 30.0)
    try:
        assert facade.card_reader(card_id) == "epub"
        note = rt.col.get_card(card_id).note()
        assert "Notes on memory" in note["Title"]
    finally:
        rt.col.remove_notes([rt.col.get_card(card_id).nid])


def _toolbar() -> dict[str, dict]:
    return {button["kind"]: button for button in json.loads(facade.writing_toolbar())}


def test_the_toolbar_has_desktops_buttons_in_its_order_and_names(rt):
    from incremento.backend.i18n import t

    buttons = json.loads(facade.writing_toolbar())
    assert [button["kind"] for button in buttons] == ["h1", "bold", "italic", "bullet", "number", "quote", "code", "rule"]
    toolbar = _toolbar()
    assert (toolbar["bold"]["label"], toolbar["bold"]["description"]) == ("B", t("writing_bold"))
    assert toolbar["quote"]["label"] == t("writing_quote")


@pytest.mark.parametrize(
    ("kind", "selected", "expected"),
    [
        ("bold", "word", "**word**"),
        ("italic", "word", "*word*"),
        ("h1", "Title", "# Title"),
        ("bullet", "one\ntwo", "- one\n- two"),
        ("number", "one\ntwo", "1. one\n2. two"),
        ("quote", "said", "> said"),
        ("code", "x = 1", "```\nx = 1\n```"),
        ("rule", "", "\n\n---\n\n"),
    ],
)
def test_each_button_rewrites_the_selection_as_desktop(rt, kind, selected, expected):
    assert facade.writing_transform(kind, selected) == expected


def test_without_a_selection_desktops_placeholder_is_inserted(rt):
    from incremento.backend.i18n import t

    assert facade.writing_transform("bold", "") == "**" + t("writing_placeholder_bold") + "**"
    assert facade.writing_transform("number", "") == "1. " + t("writing_placeholder_list_item")


def test_the_preview_is_desktops_rendering(rt):
    html = facade.writing_preview("# Heading\n\nSome *text* and a [link](https://example.com).")
    assert "<h1" in html and "<em>text</em>" in html
    # Desktop's preview is inert: links don't lead anywhere.
    assert "href" not in html
