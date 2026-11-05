"""Video bookmarks and Vimeo, as desktop's video dock has them."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade, video_reader


@pytest.fixture
def video(rt):
    card_id = video_reader.import_video("https://www.youtube.com/watch?v=jNQXAC9IVRw", "Bookmarked video")
    yield card_id
    rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_bookmarks_keep_the_moment_with_desktops_label_and_a_comment(rt, video):
    added = json.loads(facade.video_add_bookmark(video, 75.5))
    (bookmark,) = added
    assert (bookmark["seconds"], bookmark["label"]) == (75.5, "1:15")

    commented = json.loads(facade.video_bookmark_comment(video, bookmark["id"], "the key point"))
    assert commented[0]["comment"] == "the key point"

    assert json.loads(facade.video_delete_bookmark(video, bookmark["id"])) == []


def test_bookmarks_are_listed_by_time(rt, video):
    facade.video_add_bookmark(video, 120)
    facade.video_add_bookmark(video, 30)
    assert [b["seconds"] for b in json.loads(facade.video_bookmarks(video))] == [30, 120]


def test_a_vimeo_video_opens_in_vimeos_player_at_its_place(rt):
    from incremento.backend.video_manager import set_video_position

    card_id = video_reader.import_video("https://vimeo.com/76979871", "A Vimeo talk")
    try:
        set_video_position(str(rt.addon_dir), rt.profile, card_id, 42.0)
        opened = json.loads(facade.video_open(card_id))
        assert opened["kind"] == "vimeo"
        assert opened["url"].startswith("https://player.vimeo.com/video/76979871")
        assert opened["position"] == 42.0
    finally:
        rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_a_shared_vimeo_link_becomes_a_video_topic_as_on_desktop(rt):
    added = json.loads(facade.import_link("https://vimeo.com/76979871", "A Vimeo talk"))
    try:
        assert added["kind"] == "video"
        assert facade.card_reader(added["card_id"]) == "video"
    finally:
        rt.col.remove_notes([rt.col.get_card(added["card_id"]).nid])
