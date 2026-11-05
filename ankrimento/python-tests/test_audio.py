"""Audio documents, as desktop's audio import makes them."""

from __future__ import annotations

import struct

from ankrimento import facade


def _wav(path) -> None:
    samples = bytes(128 for _ in range(800))
    header = b"RIFF" + struct.pack("<I", 36 + len(samples)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 8000, 8000, 1, 8)
    path.write_bytes(header + b"data" + struct.pack("<I", len(samples)) + samples)


def test_an_audio_file_becomes_desktops_audio_topic(rt, tmp_path):
    from incremento.backend.audio_manager import AUDIO_FILENAME_FIELD, AUDIO_NOTE_TYPE
    from incremento.backend.priority_manager import get_priority

    path = tmp_path / "Lecture one.wav"
    _wav(path)
    card_id = facade.import_audio(str(path), "Lecture one", "Topics", 25.0)
    try:
        note = rt.col.get_card(card_id).note()
        assert note.note_type()["name"] == AUDIO_NOTE_TYPE
        stored = note[AUDIO_FILENAME_FIELD]
        assert stored.endswith(".wav") and rt.col.media.have(stored)
        assert get_priority(str(rt.addon_dir), rt.profile, card_id) == 25.0
        # Read as a page: desktop's template has its own player.
        assert facade.card_reader(card_id) == "text"
    finally:
        rt.col.remove_notes([rt.col.get_card(card_id).nid])
