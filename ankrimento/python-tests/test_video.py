"""Video topics: shared links, what to play, position and watch time."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade


@pytest.fixture(scope="module")
def video_card(rt):
    added = json.loads(facade.import_link("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "A talk"))
    assert added["kind"] == "video"
    return added["card_id"]


def test_shared_links_become_video_or_web_topics(rt, video_card):
    assert facade.card_reader(video_card) == "video"
    web = json.loads(facade.import_link("https://example.org/essay", "An essay"))
    assert web["kind"] == "web"
    assert facade.card_reader(web["card_id"]) == "web"


def test_youtube_topic_plays_from_the_saved_position(rt, video_card):
    opened = json.loads(facade.video_open(video_card))
    assert opened["kind"] == "youtube"
    assert opened["video_id"] == "dQw4w9WgXcQ"
    assert opened["position"] == 0.0

    facade.video_progress(video_card, 42.5, 10.0)
    assert json.loads(facade.video_open(video_card))["position"] == pytest.approx(42.5)


def test_watch_time_is_recorded(rt, video_card):
    from incremento.backend.db import get_connection

    facade.video_progress(video_card, 60.0, 15.0)
    total = get_connection(str(rt.addon_dir), rt.profile).execute(
        "SELECT SUM(watched_seconds) FROM video_watch_history WHERE card_id = ?", (video_card,)
    ).fetchone()[0]
    assert total >= 25.0
