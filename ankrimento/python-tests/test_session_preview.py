"""The session preview: upstream's picks shown first; the session then uses exactly those cards."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade, session
from test_review_flow import _add_notes
from test_session_lifecycle import host_events  # noqa: F401 - fixture


@pytest.fixture(autouse=True)
def _keep_config(rt):
    from aqt import mw

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    yield
    mw.addonManager.writeConfig("incremento", saved)
    session.forget_preview()


def test_the_preview_lists_upstreams_picks_and_the_session_uses_them(rt, host_events):  # noqa: F811
    _events, entered_review = host_events
    _add_notes(rt.col, "Topics", 3, "preview topic")

    preview = json.loads(facade.session_preview())
    assert preview["count"] == len(preview["entries"]) >= 1
    assert preview["target"] >= preview["count"]
    first = preview["entries"][0]
    assert first["title"] and first["type"] and first["mode"]
    # Cards added after the preview are not in it, so a fresh selection would be told apart.
    later = set(_add_notes(rt.col, "Topics", 3, "after the preview"))

    facade.start_session()
    assert entered_review.wait(timeout=30)
    from incremento.backend.session_selection import incremento_session_deck_name

    deck_id = rt.col.decks.id_for_name(incremento_session_deck_name(None))
    in_session = set(rt.col.find_cards(f"did:{deck_id}"))
    assert in_session == {entry["card_id"] for entry in preview["entries"]}
    assert not in_session & later
    facade.reviewer_closed()


def test_a_preview_made_before_the_settings_changed_is_not_used(rt):
    facade.session_preview()
    assert session.HeadlessSchedulerDialog(None).get_preview_override() is not None

    settings = json.loads(facade.session_settings())
    settings["card_count"] = settings["card_count"] + 1
    facade.save_session_settings(json.dumps(settings))
    assert session.HeadlessSchedulerDialog(None).get_preview_override() is None
