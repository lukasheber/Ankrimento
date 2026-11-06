"""The PDF viewer's colour button: the phone chooses, and answers the viewer as desktop does."""

from __future__ import annotations

import copy
import json

from aqt import mw

from ankrimento import facade


def test_a_chosen_colour_goes_back_to_the_viewer(rt):
    script = facade.pdf_choose_color(1, json.dumps({"cardId": 1, "currentColor": "yellow"}), "green")
    assert script == 'window.incrementoPickPdfHighlightColor && window.incrementoPickPdfHighlightColor("green");'


def test_closing_the_chooser_tells_the_viewer_nothing_was_chosen(rt):
    script = facade.pdf_choose_color(1, json.dumps({"cardId": 1, "currentColor": "yellow"}), "")
    assert script.endswith("window.incrementoPickPdfHighlightColor(null);")


def test_the_default_colour_is_saved_for_the_profile(rt):
    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    try:
        script = facade.pdf_choose_color(1, json.dumps({"cardId": 1, "currentColor": "yellow", "defaultColor": True}), "blue")
        assert 'window.incrementoSetDefaultHighlightColor?.("blue");' in script
        assert script.endswith('window.incrementoPickPdfHighlightColor("blue");')
        from incremento.backend.config_service import configured_reader_default_highlight_color

        assert configured_reader_default_highlight_color(mw.addonManager.getConfig("incremento"), rt.profile) == "blue"
    finally:
        mw.addonManager.writeConfig("incremento", saved)


def test_an_unknown_colour_is_not_passed_on(rt):
    script = facade.pdf_choose_color(1, json.dumps({"cardId": 1}), "not-a-colour")
    assert script.endswith("window.incrementoPickPdfHighlightColor(null);")
