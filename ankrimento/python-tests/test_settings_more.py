"""The PDF and writing settings the phone shows, saved in desktop's config."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("pdf_force_default_appearance", True),
        ("pdf_show_highlight_delete_buttons", False),
        ("pdf_scroll_to_top_on_page_change", False),
        ("writing_backups_enabled", False),
        ("writing_word_count_mode", "word_like"),
    ],
)
def test_each_setting_is_saved_in_desktops_config(rt, key, value):
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    try:
        assert json.loads(facade.set_setting(key, json.dumps(value))) == value
        assert json.loads(facade.settings_values())[key] == value
    finally:
        mw.addonManager.writeConfig("incremento", saved)


def test_the_word_count_mode_is_one_of_desktops(rt):
    with pytest.raises(ValueError):
        facade.set_setting("writing_word_count_mode", json.dumps("characters"))
