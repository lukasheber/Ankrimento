"""A card made from scratch with a priority, as desktop's Add Card dock makes one outside an extract."""

from __future__ import annotations

import json

from ankrimento import facade


def test_defaults_are_upstreams(rt):
    from incremento.backend.knowledge_tree import default_deck_name, default_note_type_name

    defaults = json.loads(facade.new_card_defaults())
    assert defaults["note_type"] == default_note_type_name(None)
    assert defaults["deck"] == default_deck_name(None)
    assert defaults["deck"] in defaults["decks"] and defaults["note_type"] in defaults["note_types"]
    assert defaults["priority"] == 50.0  # desktop's scratch priority
    assert defaults["fields"] == ["", ""]


def test_a_card_from_scratch_gets_its_priority_kind_and_deck_but_no_tree_place(rt):
    from incremento.backend.knowledge_tree import configured_item_tags
    from incremento.backend.priority_manager import get_priority

    note_id = facade.create_card(json.dumps(["Scratch question", "Scratch answer"]), "Basic", "item", 30.0, "Default")

    note = rt.col.get_note(note_id)
    card_id = note.card_ids()[0]
    assert rt.col.decks.name(rt.col.get_card(card_id).did) == "Default"
    assert get_priority(str(rt.addon_dir), rt.profile, card_id) == 30.0
    assert set(configured_item_tags()) <= {tag.casefold() for tag in note.tags}
    assert card_id not in {row["card_id"] for row in json.loads(facade.knowledge_tree())}
    rt.col.remove_notes([note_id])


def test_incrementos_document_note_types_are_not_offered_for_cards(rt, tmp_path):
    # A card of one would open as a broken document (desktop's readers open these note types).
    import json as _json

    path = tmp_path / "Offered.md"
    path.write_text("Words.", encoding="utf8")
    topic = facade.import_markdown(str(path), "Offered")
    try:
        documents = {"Incremento PDF", "Incremento EPUB", "Incremento Web", "Incremento Video", "Incremento Writing"}
        present = {m["name"] for m in rt.col.models.all()} & documents
        assert present, "importing made at least one of Incremento's document note types"
        for offered in (
            _json.loads(facade.extract_draft(topic))["note_types"],
            _json.loads(facade.new_card_defaults())["note_types"],
        ):
            assert not set(offered) & documents
    finally:
        rt.col.remove_notes([rt.col.get_card(topic).nid])


def test_image_occlusion_is_not_offered_for_text_cards(rt):
    # Its Occlusion field holds shapes drawn in AnkiDroid's occlusion editor, not text.
    import json as _json

    from anki.stdmodels import get_stock_notetypes

    def occlusion(model) -> bool:
        return "Occlusion" in [field["name"] for field in model["flds"]]

    if not any(occlusion(model) for model in rt.col.models.all()):
        for _name, make in get_stock_notetypes(rt.col):
            note_type = make(rt.col)
            if occlusion(note_type):
                rt.col.models.add(note_type)
    names = {model["name"] for model in rt.col.models.all() if occlusion(model)}
    assert names
    assert not set(_json.loads(facade.new_card_defaults())["note_types"]) & names
