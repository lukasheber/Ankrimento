"""PDF page links, as desktop's page-links bridge keeps them, and the link preview's size."""

from __future__ import annotations

import copy
import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture
def two_pdfs(rt, tmp_path):
    cards = []
    for title in ("Linked source", "Linked target"):
        path = tmp_path / f"{title}.pdf"
        path.write_bytes(_MINIMAL_PDF)
        cards.append(facade.import_pdf(str(path), title, priority=50.0))
    yield cards
    rt.col.remove_notes([rt.col.get_card(card).nid for card in cards])


def _links(card: int, page: int) -> list[dict]:
    return json.loads(facade.pdf_page_links(card, page))


def test_a_link_shows_at_both_ends_and_opens_its_other_end(rt, two_pdfs):
    source, target = two_pdfs
    link_id = facade.pdf_page_link_add(source, 1, 0.25, target, 1, 0.5, 0.4)

    (outgoing,) = _links(source, 1)
    assert (outgoing["id"], outgoing["direction"], outgoing["title"], outgoing["available"]) == (link_id, "outgoing", "Linked target", True)
    (incoming,) = _links(target, 1)
    assert (incoming["direction"], incoming["title"]) == ("incoming", "Linked source")

    # Its other end, where desktop's jump opens it.
    there = json.loads(facade.pdf_page_link_target(source, 1, link_id, "outgoing"))
    assert there == {"card_id": target, "page": 1, "scroll": 0.5, "page_y": 0.4}
    back = json.loads(facade.pdf_page_link_target(target, 1, link_id, "incoming"))
    assert (back["card_id"], back["page"]) == (source, 1)

    assert facade.pdf_page_link_remove(source, 1, link_id)
    assert _links(source, 1) == [] and _links(target, 1) == []


def test_a_link_cannot_point_past_the_daily_page_limit(rt, two_pdfs):
    from incremento.backend.pdf_manager import save_pdf_daily_limit_settings

    source, target = two_pdfs
    save_pdf_daily_limit_settings(str(rt.addon_dir), rt.profile, target, enabled=True, daily_page_limit=1, enforcement_mode="hard_stop")
    assert facade.pdf_page_link_limit(target) >= 1
    with pytest.raises(ValueError):
        facade.pdf_page_link_add(source, 1, None, target, facade.pdf_page_link_limit(target) + 1, 0.0, None)


def test_the_viewers_own_panel_is_answered(rt, two_pdfs):
    from aqt import mw

    source, target = two_pdfs
    link_id = facade.pdf_page_link_add(source, 1, None, target, 1, 0.0, None)
    request = {"cardId": source, "page": 1, "request": 3, "generation": 1}

    script = facade.pdf_message(source, "incremento_pdf_page_links:" + json.dumps({**request, "operation": "list"}))
    assert "incrementoReceivePdfPageLinks" in script
    reply = json.loads(script[script.index("(", script.index("incrementoReceivePdfPageLinks(")) + 1 : script.rindex(")")])
    assert {key: reply[key] for key in request} == request
    assert [row["id"] for row in reply["links"]] == [link_id]

    # Opening and linking are the phone's own screens, asked for through the host.
    events = []
    previous = mw.host_events
    mw.host_events = lambda event, fields: events.append((event, fields))
    try:
        facade.pdf_message(source, "incremento_pdf_page_links:" + json.dumps(
            {**request, "operation": "jump", "id": link_id, "direction": "outgoing", "mode": "jump"}))
        facade.pdf_message(source, "incremento_pdf_page_links:" + json.dumps({**request, "operation": "create", "page_y": 0.3}))
    finally:
        mw.host_events = previous
    assert ("pdf_open", {"source_card_id": source, "card_id": target, "page": 1, "scroll": 0.0, "page_y": None}) in events
    assert ("pdf_page_link_create", {"card_id": source, "page": 1, "page_y": 0.3}) in events

    removed = facade.pdf_message(source, "incremento_pdf_page_links:" + json.dumps(
        {**request, "operation": "remove", "id": link_id, "direction": "outgoing"}))
    assert "incrementoRefreshPdfPageLinks" in removed
    assert _links(source, 1) == []


def test_the_link_previews_size_and_zoom_are_kept_per_profile(rt, two_pdfs):
    from aqt import mw
    from incremento.backend.config_service import configured_pdf_link_preview_size, configured_pdf_link_preview_zoom

    source, _target = two_pdfs
    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))
    try:
        facade.pdf_message(source, "incremento_pdf_link_preview_size:" + json.dumps({"cardId": source, "size": {"width": 400, "height": 300}}))
        facade.pdf_message(source, "incremento_pdf_link_preview_zoom:" + json.dumps({"cardId": source, "zoom": 2}))
        config = mw.addonManager.getConfig("incremento")
        assert configured_pdf_link_preview_size(config, rt.profile) == {"width": 400, "height": 300}
        assert configured_pdf_link_preview_zoom(config, rt.profile) == 2
    finally:
        mw.addonManager.writeConfig("incremento", saved)
