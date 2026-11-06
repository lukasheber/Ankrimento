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


def test_the_writing_backup_schedule_is_desktops_set_of_intervals(rt):
    import copy

    from aqt import mw

    from ankrimento import settings

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    try:
        # Desktop's choices, from its writing backups, and its default when none is set.
        assert settings.choices("writing_backup_tiers") == ["1m", "5m", "15m", "30m", "1h", "6h", "1d", "7d"]
        assert settings.values()["writing_backup_tiers"] == ["1m", "30m", "1d"]
        assert settings.set_value("writing_backup_tiers", ["7d", "1h", "1h"]) == ["1h", "7d"]
        assert mw.addonManager.getConfig("incremento")["writing_backup_tiers"] == ["1h", "7d"]
        with pytest.raises(ValueError):
            settings.set_value("writing_backup_tiers", ["2w"])
        with pytest.raises(ValueError):
            settings.set_value("writing_backup_tiers", "1h")
    finally:
        mw.addonManager.writeConfig("incremento", saved)


def test_the_postpone_mode_is_desktops_and_drives_the_phones_postpone(rt):
    # What the phone's Postpone does: back after the set minutes, or at the end of the session.
    from aqt import mw
    from incremento.backend.topic_postpone import configured_topic_postpone_mode

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    try:
        assert json.loads(facade.set_setting("topic_postpone_mode", json.dumps("session"))) == "session"
        assert configured_topic_postpone_mode(mw.addonManager.getConfig("incremento")) == "session"
        with pytest.raises(ValueError):
            facade.set_setting("topic_postpone_mode", json.dumps("forever"))
    finally:
        mw.addonManager.writeConfig("incremento", saved)
