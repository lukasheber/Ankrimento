"""Video extracts that mark a segment of the parent video, and local video files as topics."""

from __future__ import annotations

import json
import os

import pytest

from ankrimento import facade, video_reader


@pytest.fixture
def video(rt):
    card_id = video_reader.import_video("https://www.youtube.com/watch?v=jNQXAC9IVRw", "Clipped talk")
    yield card_id
    facade.discard_extract(card_id)
    rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_a_segment_extract_marks_start_and_end_in_the_parent_without_copying_any_media(rt, video):
    before = set(os.listdir(rt.col.media.dir()))

    facade.video_extract(video, 65.0, 95.0)

    front = json.loads(facade.extract_draft(video))["fields"][0]
    # The phone plays from the parent and stops at the end.
    assert f"incremento_open_video:{video}:65.0:95.0" in front
    assert "1:05–1:35" in front or "01:05–01:35" in front
    assert "Clipped talk" in front
    # Desktop understands only card:start, so its own link to the start stays beside it.
    assert f"pycmd('incremento_open_video:{video}:65.0')" in front
    assert "<video" not in front
    assert set(os.listdir(rt.col.media.dir())) == before


def test_a_segment_must_end_after_it_starts_and_stay_in_range(rt, video):
    with pytest.raises(ValueError):
        facade.video_extract(video, 30.0, 30.0)
    with pytest.raises(ValueError):
        facade.video_extract(video, 40.0, 20.0)
    with pytest.raises(ValueError):
        facade.video_extract(video, -1.0, 20.0)


def test_an_extract_without_an_end_is_still_a_moment(rt, video):
    facade.video_extract(video, 65.0)

    front = json.loads(facade.extract_draft(video))["fields"][0]
    assert f"incremento_open_video:{video}:65.0" in front
    assert front.count("incremento_open_video") == 1


def test_a_local_video_file_becomes_a_video_topic_kept_as_it_is(rt, tmp_path):
    # Desktop's Add Video from a file, "Original quality (no re-encoding)": the phone has no FFmpeg.
    source = tmp_path / "Lecture one.mp4"
    source.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x01" * 128)

    card_id = facade.import_local_video(str(source), "Lecture one", "Topics", 45.0)
    try:
        assert facade.card_reader(card_id) == "video"
        opened = video_reader.open_video(card_id)
        assert opened["kind"] == "local"
        with open(opened["path"], "rb") as copied:
            assert copied.read() == source.read_bytes()
    finally:
        rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_an_unsupported_file_is_refused_with_desktops_message(rt, tmp_path):
    source = tmp_path / "notes.txt"
    source.write_text("not a video", encoding="utf8")
    with pytest.raises(ValueError, match="Unsupported local video format"):
        facade.import_local_video(str(source), "Notes", "Topics", 50.0)


def test_desktops_own_readers_find_the_start_of_a_segment_extract(rt, video):
    # Desktop's Study resources and Review All read the start from the front; its click handler wants exactly
    # card:start, which the second link gives.
    from incremento.backend.media_review import _VIDEO_POSITION_RE
    from incremento.backend.study_resources import _VIDEO

    facade.video_extract(video, 65.0, 95.0)
    front = json.loads(facade.extract_draft(video))["fields"][0]

    assert _VIDEO.search(front).groups() == (str(video), "65.0")
    found = _VIDEO_POSITION_RE.search(front)
    assert (found["card_id"], found["position"]) == (str(video), "65.0")
    assert f"pycmd('incremento_open_video:{video}:65.0')" in front  # the handler's exact card:start


def test_a_vimeo_extract_opens_at_its_start_not_where_playback_stopped(rt):
    # The Vimeo player page is loaded from a URL that carries the start second, so the link's start has to be
    # known when the URL is built.
    card = video_reader.import_video("https://vimeo.com/76979871", "Vimeo talk")
    try:
        facade.video_progress(card, 10.0, 0.0)
        assert "t=10" in json.loads(facade.video_open(card))["url"]  # the saved position by default

        opened = json.loads(facade.video_open(card, 75.0))

        assert opened["position"] == 75.0
        assert "t=75" in opened["url"]
    finally:
        rt.col.remove_notes([rt.col.get_card(card).nid])
