"""A video clip: a moment's segment of a local video, cut on the phone, as desktop's Create Mobile Clip."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade, video_reader


@pytest.fixture
def video(rt):
    card_id = video_reader.import_video("https://www.youtube.com/watch?v=jNQXAC9IVRw", "Clipped talk")
    yield card_id
    facade.discard_extract(card_id)
    rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_a_clip_is_kept_as_media_and_starts_a_card_with_its_player_and_the_link_back(rt, video, tmp_path):
    clip = tmp_path / "cut.mp4"
    clip.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 64)  # stands in for the cut MP4

    facade.video_clip(video, 65.0, 95.0, str(clip), 600.0)

    front = json.loads(facade.extract_draft(video))["fields"][0]
    name = front.split('src="')[1].split('"')[0]
    assert name.startswith("incremento-video-clip-") and name.endswith(".mp4")
    assert rt.col.media.have(name)
    assert '<video controls playsinline preload="metadata"' in front
    assert "1:05–1:35" in front and "Clipped talk" in front
    # Desktop's link back to the moment in the video.
    assert f"incremento_open_video:{video}:65.0" in front


def test_desktops_limits_are_kept(rt, video, tmp_path):
    clip = tmp_path / "cut.mp4"
    clip.write_bytes(b"\x00" * 32)
    with pytest.raises(ValueError, match="after its start"):
        facade.video_clip(video, 30.0, 30.0, str(clip), 600.0)
    with pytest.raises(ValueError, match="limited to 10 minutes"):
        facade.video_clip(video, 0.0, 700.0, str(clip), 900.0)
    with pytest.raises(ValueError, match="exceeds the source"):
        facade.video_clip(video, 100.0, 130.0, str(clip), 110.0)


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
