"""Advanced session options, read and written in desktop's dialog settings."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade


@pytest.fixture(autouse=True)
def _keep_config(rt):
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    yield
    mw.addonManager.writeConfig("incremento", saved)


def _dialog() -> dict:
    from aqt import mw
    from incremento.backend.config_service import load_addon_config

    return load_addon_config(mw.addonManager, "incremento")["dialog"]


def _options() -> dict:
    return json.loads(facade.session_options())


def _set(**changes) -> dict:
    return json.loads(facade.set_session_options(json.dumps(changes)))


def test_defaults_match_desktops(rt):
    options = _options()

    assert options["topics_filter"] == "" and options["items_filter"] == ""
    assert options["tags"] == []
    assert options["other_cards"] is True
    assert [row["type"] for row in options["content_types"]] == ["pdf", "youtube", "webpage"]
    assert not any(row["enabled"] for row in options["content_types"])
    assert options["strict"] is True
    assert options["scope"] == "session"


def test_options_reach_upstreams_scheduler(rt):
    from incremento.backend.scheduler_config import load_scheduler_config

    _set(
        topics_filter="deck:Physics",
        tags=[{"tag": "physics", "weight": 20}],
        content_types=[{"type": "pdf", "enabled": True, "weight": 30}],
        strict=False,
        scope="daily",
        media_outside_tags=True,
    )

    config = load_scheduler_config()
    assert config.topics_filter == "deck:Physics"
    assert config.tag_weights == {"physics": 0.2}
    assert config.content_type_weights == {"pdf": 0.3}
    assert config.enforce_priority is False
    assert config.scheduler_scope == "daily"
    assert config.allow_content_tag_fallback is True


def test_editing_a_tag_keeps_desktops_other_row_settings(rt):
    from aqt import mw
    from incremento.backend.config_service import load_addon_config, save_addon_config

    config = load_addon_config(mw.addonManager, "incremento")
    config["dialog"]["tag_rows"] = [{"tag": "physics", "weight": 10, "locked": True, "group": "science", "order": 2}]
    save_addon_config(mw.addonManager, "incremento", config)

    _set(tags=[{"tag": "physics", "weight": 40}, {"tag": "history", "weight": 15}])

    rows = {row["tag"]: row for row in _dialog()["tag_rows"]}
    assert rows["physics"] == {"tag": "physics", "weight": 40, "locked": True, "group": "science", "order": 2}
    assert rows["history"]["weight"] == 15 and rows["history"]["group"] == "tags"


def test_a_filter_anki_cannot_search_is_refused(rt):
    with pytest.raises(Exception):
        _set(items_filter='deck:"unclosed')

    assert _options()["items_filter"] == ""


def test_other_cards_off_leaves_only_tagged_cards(rt):
    from incremento.backend.scheduler_config import load_scheduler_config

    _set(tags=[{"tag": "physics", "weight": 50}], other_cards=False)

    assert _options()["other_cards"] is False
    assert load_scheduler_config().include_rest is False
