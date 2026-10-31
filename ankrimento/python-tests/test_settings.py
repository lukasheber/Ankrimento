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
