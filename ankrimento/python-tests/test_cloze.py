"""Cloze from a topic's text: SuperMemo's way from reading to items."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade


@pytest.fixture
def topic(rt, tmp_path):
    path = tmp_path / "Cloze source.md"
    path.write_text("# Memory\n\nSpacing beats cramming.\n", encoding="utf8")
    card_id = facade.import_markdown(str(path), "Cloze source")
    yield card_id
    facade.discard_extract(card_id)
    rt.col.remove_notes([rt.col.get_card(card_id).nid])


def test_a_cloze_draft_hides_the_selection_in_its_sentence_as_an_item(rt, topic):
    facade.extract_cloze(topic, "Spacing beats ", "cramming", ".")
    draft = json.loads(facade.extract_draft(topic))
    assert draft["fields"][0] == "Spacing beats {{c1::cramming}}."
    assert draft["kind"] == "item"
    assert rt.col.models.by_name(draft["note_type"])["type"] == 1
    # Chosen for the cloze, not replaced by the note type the phone remembers for the document.
    assert draft["note_type_configured"] is True


def test_the_cloze_becomes_an_item_under_its_topic(rt, topic):
    facade.extract_cloze(topic, "", "Spacing & <rest>", " helps.")
    draft = json.loads(facade.extract_draft(topic))
    assert draft["fields"][0] == "{{c1::Spacing &amp; &lt;rest&gt;}} helps."
    result = json.loads(facade.create_extract(topic, json.dumps(draft["fields"]), draft["note_type"], "item", 40.0))
    note = rt.col.get_note(result["note_id"])
    try:
        assert note.note_type()["type"] == 1 and len(note.card_ids()) == 1
        # The cloze is a new item under the topic; the topic itself stays a topic, unchanged.
        assert facade.is_topic(topic)
        assert facade.is_topic(note.card_ids()[0]) is False
        assert "{{c1::" not in rt.col.get_card(topic).note().fields[0]
        # A plain extract afterwards is no cloze: the usual note type, not a cloze one.
        facade.web_extract(topic, "Plain words")
        plain = json.loads(facade.extract_draft(topic))
        assert rt.col.models.by_name(plain["note_type"])["type"] == 0
    finally:
        rt.col.remove_notes([result["note_id"]])


def test_an_empty_selection_makes_no_cloze(rt, topic):
    with pytest.raises(ValueError):
        facade.extract_cloze(topic, "Spacing ", "  ", "")


def test_without_a_cloze_note_type_ankis_own_is_added_never_image_occlusion(rt, topic):
    from anki.models import MODEL_CLOZE
    from anki.stdmodels import get_stock_notetypes

    def occlusion(model) -> bool:
        return "Occlusion" in [field["name"] for field in model["flds"]]

    models = rt.col.models
    if not any(occlusion(model) for model in models.all()):
        for _name, make in get_stock_notetypes(rt.col):
            note_type = make(rt.col)
            if occlusion(note_type):
                models.add(note_type)
    # A collection whose only cloze-type note type is image occlusion (as the tablet's was).
    for model in models.all():
        if model["type"] == MODEL_CLOZE and not occlusion(model):
            models.remove(model["id"])

    facade.extract_cloze(topic, "Spacing beats ", "cramming", ".")
    chosen = models.by_name(json.loads(facade.extract_draft(topic))["note_type"])
    assert chosen["type"] == MODEL_CLOZE
    assert not occlusion(chosen)


def test_the_draft_names_each_note_types_fields_for_the_sheets_labels(rt, topic):
    facade.extract_cloze(topic, "Spacing beats ", "cramming", ".")
    draft = json.loads(facade.extract_draft(topic))
    assert draft["field_names"][draft["note_type"]] == ["Text", "Back Extra"]
    assert draft["field_names"]["Basic"] == ["Front", "Back"]
    assert json.loads(facade.new_card_defaults())["field_names"]["Basic"] == ["Front", "Back"]


# ── The sentence around a selection, found from the block's text ─────────────

from ankrimento import extract  # noqa: E402


def _around(text: str, word: str, stop: int | None = None):
    start = text.index(word)
    return extract.sentence_around(text, start, start + len(word), stop)


def test_the_sentence_is_cut_at_the_sentence_ends_around_the_selection():
    text = "First one. Spacing beats cramming. Last one."
    assert _around(text, "cramming") == ("Spacing beats ", "cramming", ".")


def test_abbreviations_do_not_end_a_sentence():
    text = "See Smith et al. (2013) on e.g. 90% recall. Next."
    assert _around(text, "recall") == ("See Smith et al. (2013) on e.g. 90% ", "recall", ".")


def test_the_source_link_after_the_text_is_never_part_of_the_cloze():
    text = "Spaced repetitionPage 4. of sample"
    link_at = text.index("Page 4")
    assert _around(text, "repetition", link_at) == ("Spaced ", "repetition", "")


def test_a_selection_inside_the_source_link_text_keeps_no_stop():
    # The stop only counts when it lies after the selection.
    text = "Spaced repetitionPage 4. of sample"
    assert _around(text, "sample", 5) == ("Spaced repetitionPage 4. of ", "sample", "")


def test_a_cloze_from_a_block_makes_the_draft(rt, topic):
    text = "Spaced repetitionPage 4. of sample"
    start = text.index("repetition")
    facade.extract_cloze_in(topic, text, start, start + len("repetition"), text.index("Page 4"))
    draft = json.loads(facade.extract_draft(topic))
    assert draft["fields"][0] == "Spaced {{c1::repetition}}"


# ── Undoing an extract ───────────────────────────────────────────────────────


def test_an_extract_can_be_undone_and_the_source_stays(rt, topic):
    facade.web_extract(topic, "A passing thought")
    draft = json.loads(facade.extract_draft(topic))
    made = json.loads(facade.create_extract(topic, json.dumps(draft["fields"]), draft["note_type"], "topic", 50.0))
    note_id = made["note_id"]
    assert rt.col.get_note(note_id)
    facade.undo_extract(topic, note_id)
    assert rt.col.find_notes(f"nid:{note_id}") == []
    assert facade.is_topic(topic)


def test_undoing_an_extract_that_is_already_gone_is_harmless(rt, topic):
    facade.undo_extract(topic, 123456789)
