"""On the phone a new extract is a topic unless the user chose otherwise (upstream's own default is an item)."""

from __future__ import annotations

import copy
import json

from ankrimento import facade


def _forget_choice(mw):
    """The add-on's saved settings as a fresh install has them: nothing chosen yet."""
    saved = copy.deepcopy(mw.addonManager.addonMeta("incremento"))
    meta = copy.deepcopy(saved)
    meta.pop("config", None)
    mw.addonManager.writeAddonMeta("incremento", meta)
    return saved


def test_without_a_choice_new_extracts_are_topics(rt):
    from aqt import mw

    saved = _forget_choice(mw)
    try:
        assert json.loads(facade.settings_values())["extract_default_type"] == "topic"
        assert json.loads(facade.new_card_defaults())["kind"] == "topic"
    finally:
        mw.addonManager.writeAddonMeta("incremento", saved)


def test_a_chosen_item_is_kept(rt):
    from aqt import mw

    saved = _forget_choice(mw)
    try:
        facade.set_setting("extract_default_type", json.dumps("item"))
        assert json.loads(facade.settings_values())["extract_default_type"] == "item"
        assert json.loads(facade.new_card_defaults())["kind"] == "item"
    finally:
        mw.addonManager.writeAddonMeta("incremento", saved)
