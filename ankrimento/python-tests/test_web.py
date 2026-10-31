"""Web topics: adding, opening with desktop's bridge, progress and extraction."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade


@pytest.fixture(scope="module")
def web_card(rt):
    return facade.import_web("https://example.com/article", "An article", priority=40.0)


def test_web_card_opens_its_url_with_the_bridge(rt, web_card):
    assert facade.card_reader(web_card) == "web"
    opened = json.loads(facade.web_open(web_card))
    assert opened["url"] == "https://example.com/article"
    script = facade.web_script(web_card, opened["nonce"], opened["url"])
    assert f'"__incremento_webdock_pycmd__:{opened["nonce"]}:"' in script
    assert "incrementoApplyRestoreState" in script


def test_progress_is_saved_and_restored(rt, web_card):
    from incremento.backend.web_manager import get_web_progress

    url = "https://example.com/article?page=2"
    facade.web_message(web_card, "incremento_web_progress:" + json.dumps({"url": url, "scrollRatio": 0.4}))
    progress = get_web_progress(str(rt.addon_dir), rt.profile, web_card)
    assert progress["url"] == url
    assert progress["scroll_ratio"] == pytest.approx(0.4)
    assert json.loads(facade.web_open(web_card))["url"] == url  # reopens where reading stopped
    assert '"scrollRatio": 0.4' in facade.web_script(web_card, "n", url)


def test_selection_starts_an_extract(rt, web_card):
    assert facade.web_extract(web_card, "  Key sentence.  ")
    assert json.loads(facade.extract_draft(web_card))["fields"][0] == "Key sentence."
    assert not facade.web_extract(web_card, "   ")
    facade.discard_extract(web_card)
