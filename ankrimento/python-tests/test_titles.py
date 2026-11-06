"""Readable titles for the readers' top bar: card markup is not a title."""

from __future__ import annotations

import pytest

from ankrimento import facade


@pytest.fixture
def add(rt):
    made = []

    def make(note_type: str, **fields) -> int:
        note = rt.col.new_note(rt.col.models.by_name(note_type))
        for name, value in fields.items():
            note[name] = value
        rt.col.add_note(note, rt.col.decks.id("Default"))
        made.append(note.id)
        return note.card_ids()[0]

    yield make
    rt.col.remove_notes(made)


def test_sound_tags_are_left_out(rt, add):
    assert facade.card_title(add("Basic", Front="Listen to this [sound:clip.mp3]", Back="b")) == "Listen to this"


def test_a_cloze_hides_its_own_answer_as_the_card_does(rt, add):
    # The title is on screen before Show answer: it must not give the answer away.
    card = add("Cloze", Text="Spacing beats {{c1::cramming::what loses}} every time, {{c2::slowly}}.")
    assert facade.card_title(card) == "Spacing beats [what loses] every time, slowly."
    second = rt.col.get_note(rt.col.get_card(card).nid).card_ids()[1]
    assert facade.card_title(second) == "Spacing beats cramming every time, [...]."


def test_image_occlusion_shows_its_header(rt, add):
    from anki.stdmodels import get_stock_notetypes

    if rt.col.models.by_name("Image Occlusion") is None:
        for _name, make in get_stock_notetypes(rt.col):
            note_type = make(rt.col)
            if "Occlusion" in [field["name"] for field in note_type["flds"]]:
                rt.col.models.add(note_type)
    card = add(
        "Image Occlusion",
        Occlusion="{{c1::image-occlusion:rect:left=.1:top=.1:width=.3:height=.3}}",
        Image='<img src="diagram.png">',
        Header="The heart's <b>valves</b>",
    )
    assert facade.card_title(card) == "The heart's valves"


def test_plain_titles_are_unchanged(rt, add):
    assert facade.card_title(add("Basic", Front="Capital of France?", Back="Paris")) == "Capital of France?"
