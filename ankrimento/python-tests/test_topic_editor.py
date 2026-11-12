"""Editing a topic where it is read: the text as formatted text, its source link kept whole."""

from __future__ import annotations

import pytest

from ankrimento import facade

LINK = (
    '<a href="#" onclick="pycmd(\'incremento_open_video:77:75.0\')" '
    'style="color:#4a90d9">▶ 1:15</a>'
)


@pytest.fixture
def topic(rt):
    from anki.notes import Note

    model = rt.col.models.by_name("Basic")
    note = rt.col.new_note(model)
    note.fields[0] = f"A <b>bold</b> idea<br>{LINK}"
    note.fields[1] = ""
    rt.col.add_note(note, rt.col.decks.id("Default"))
    card_id = note.card_ids()[0]
    yield card_id
    rt.col.remove_notes([note.id])


def test_the_editor_shows_the_text_and_a_chip_for_the_source_link(topic):
    import json

    editable = json.loads(facade.topic_editable(topic))
    assert "<b>bold</b>" in editable["html"]
    # No script, no JSON blob to break: the link is a chip that says where it goes.
    assert "pycmd" not in editable["html"] and "onclick" not in editable["html"]
    assert 'data-source-link="0"' in editable["html"] and "▶ 1:15" in editable["html"]


def test_saving_keeps_the_link_exactly_as_it_was(rt, topic):
    import json

    editable = json.loads(facade.topic_editable(topic))
    edited = editable["html"].replace("A <b>bold</b> idea", "A <i>better</i> idea")
    facade.topic_save(topic, edited)
    front = rt.col.get_card(topic).note().fields[0]
    assert front == f"A <i>better</i> idea<br>{LINK}"


def test_a_deleted_chip_comes_back_at_the_end(rt, topic):
    facade.topic_save(topic, "Only words")
    front = rt.col.get_card(topic).note().fields[0]
    assert front == f"Only words<br>{LINK}"


def test_the_editing_markup_never_reaches_the_note(rt, topic):
    facade.topic_save(topic, '<span contenteditable="true">Words</span>')
    front = rt.col.get_card(topic).note().fields[0]
    assert "contenteditable" not in front
    assert "Words" in front and LINK in front


def test_a_topic_without_a_source_link_edits_plainly(rt):
    import json

    model = rt.col.models.by_name("Basic")
    note = rt.col.new_note(model)
    note.fields[0] = "Plain <u>text</u>"
    rt.col.add_note(note, rt.col.decks.id("Default"))
    card_id = note.card_ids()[0]
    try:
        assert json.loads(facade.topic_editable(card_id))["html"] == "Plain <u>text</u>"
        facade.topic_save(card_id, "Plain <u>text</u> more")
        assert rt.col.get_card(card_id).note().fields[0] == "Plain <u>text</u> more"
    finally:
        rt.col.remove_notes([note.id])
