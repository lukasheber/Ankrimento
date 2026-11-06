"""A video's paired subtitles (target and reference language), as desktop's subtitle dialog keeps them."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade, video_reader

_SRT = "1\n00:00:01,000 --> 00:00:03,500\nHola, mundo\n\n2\n00:00:04,000 --> 00:00:06,000\nAdiós\n"
_VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:03.500\nHello, world\n"


@pytest.fixture
def video(rt, tmp_path):
    source = tmp_path / "Lesson.mp4"
    source.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 64)
    card_id = facade.import_local_video(str(source), "Lesson", "Topics", 50.0)
    yield card_id
    rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_a_file_in_each_slot_gives_its_cues_and_its_label(rt, video, tmp_path):
    target = tmp_path / "es.srt"
    target.write_text(_SRT, encoding="utf8")
    reference = tmp_path / "en.vtt"
    reference.write_text(_VTT, encoding="utf8")

    facade.video_attach_subtitle(video, "target", str(target), "es.srt")
    facade.video_attach_subtitle(video, "reference", str(reference), "en.vtt")

    subtitles = json.loads(facade.video_subtitles(video))
    assert subtitles["target"]["label"] == "es.srt"
    assert subtitles["target"]["cues"] == [
        {"start": 1.0, "end": 3.5, "text": "Hola, mundo"},
        {"start": 4.0, "end": 6.0, "text": "Adiós"},
    ]
    assert subtitles["reference"]["cues"][0]["text"] == "Hello, world"
    # Kept as desktop keeps them: in the profile's videos, named after the title and the slot.
    from incremento.backend.video_manager import get_video_note_media

    media = get_video_note_media(rt.col.get_card(video).note())
    assert media["target_subtitle_file"].startswith("videos/Lesson-target")
    assert media["reference_subtitle_label"] == "en.vtt"


def test_a_slot_is_cleared_and_other_formats_are_refused(rt, video, tmp_path):
    target = tmp_path / "es.srt"
    target.write_text(_SRT, encoding="utf8")
    facade.video_attach_subtitle(video, "target", str(target), "es.srt")
    facade.video_clear_subtitle(video, "target")
    assert json.loads(facade.video_subtitles(video))["target"] == {"label": "", "cues": []}

    notes = tmp_path / "notes.txt"
    notes.write_text("no", encoding="utf8")
    with pytest.raises(ValueError, match="Unsupported subtitle format"):
        facade.video_attach_subtitle(video, "reference", str(notes), "notes.txt")
    with pytest.raises(ValueError):
        facade.video_attach_subtitle(video, "other", str(target), "es.srt")


def test_a_video_without_subtitles_has_empty_slots(rt, video):
    assert video_reader.subtitles(video) == {"target": {"label": "", "cues": []}, "reference": {"label": "", "cues": []}}
