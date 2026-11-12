"""Incremento settings shown on mobile: upstream's config, normalized by upstream on save."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade


def test_settings_round_trip_through_upstreams_config(rt):
    values = json.loads(facade.settings_values())
    assert values["use_fail_pass_on_items"] in (True, False)
    assert values["topic_done_tag"] == "topic/done"

    assert json.loads(facade.set_setting("topic_more_adjustment_percent", "25")) == 25.0
    assert json.loads(facade.set_setting("topic_more_adjustment_percent", "500")) == 100.0  # upstream's limit
    assert json.loads(facade.set_setting("extract_default_type", '"topic"')) == "topic"
    assert json.loads(facade.settings_values())["extract_default_type"] == "topic"

    facade.set_setting("topic_more_adjustment_percent", "10")
    facade.set_setting("extract_default_type", '"item"')


def test_only_known_settings_with_their_types_are_accepted(rt):
    with pytest.raises(ValueError):
        facade.set_setting("companion_browser", '"firefox"')  # not a mobile setting
    with pytest.raises(ValueError):
        facade.set_setting("use_fail_pass_on_items", '"yes"')
    with pytest.raises(ValueError):
        facade.set_setting("extract_default_type", '"cloze"')


def test_review_buttons_are_shown_or_hidden_as_desktop_stores_it(rt):
    import copy

    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    try:
        assert json.loads(facade.review_buttons()) == {"group": True, "done": True, "postpone": True, "extract": True}

        facade.set_review_button("postpone", False)
        facade.set_review_button("group", False)

        assert json.loads(facade.review_buttons()) == {"group": False, "done": True, "postpone": False, "extract": True}
        config = mw.addonManager.getConfig("incremento")
        assert config["reviewer_button_visibility"]["postpone"] is False
        assert config["reviewer_button_group_visible"] is False
    finally:
        mw.addonManager.writeConfig("incremento", saved)


def test_the_parent_context_switch_is_desktops_setting(rt):
    from aqt import mw

    saved = mw.addonManager.getConfig("incremento")
    try:
        assert facade.parent_context_enabled() is True  # desktop's default
        assert json.loads(facade.set_setting("reviewer_parent_context_enabled", "false")) is False
        assert facade.parent_context_enabled() is False
        assert mw.addonManager.getConfig("incremento")["reviewer_parent_context_enabled"] is False
        assert json.loads(facade.settings_values())["reviewer_parent_context_enabled"] is False
    finally:
        mw.addonManager.writeConfig("incremento", saved)
