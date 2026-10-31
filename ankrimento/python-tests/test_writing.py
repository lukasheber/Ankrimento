"""Writing topics: created, edited and saved as desktop's writing dock saves them."""

from __future__ import annotations

import json

from ankrimento import facade


def test_writing_topic_round_trip_with_word_stats(rt):
    from incremento.backend.db import get_writing_word_stats

    card_id = facade.create_writing("Essay draft")
    assert facade.card_reader(card_id) == "writing"
    opened = json.loads(facade.writing_open(card_id))
    assert "Essay draft" in opened["text"]  # upstream writes the title as a heading

    words = facade.writing_save(card_id, "# Essay draft\n\nIncremental writing on the phone.")
    assert words == 8
    assert json.loads(facade.writing_open(card_id))["text"].endswith("on the phone.")
    stats = get_writing_word_stats(str(rt.addon_dir), rt.profile, card_id)
    assert stats["current_word_count"] == 8
