"""Batch Q/A: several cards from one extract, as desktop's batch dialog makes them."""

from __future__ import annotations

import json

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


def test_the_preview_is_desktops_parsing(rt):
    rows = json.loads(facade.extract_batch_preview("Q: What spaces reviews?\nA: The scheduler\n\nQ: Missing answer"))
    assert [(row["question"], row["valid"]) for row in rows] == [("What spaces reviews?", True), ("Missing answer", False)]
    assert rows[1]["error"]
    alternating = json.loads(facade.extract_batch_preview("one\nuno\ntwo\ndos"))
    assert [(row["question"], row["answer"]) for row in alternating] == [("one", "uno"), ("two", "dos")]


def test_each_valid_pair_becomes_a_card_under_the_source(rt, tmp_path):
    path = tmp_path / "Batch source.pdf"
    path.write_bytes(_MINIMAL_PDF)
    source = facade.import_pdf(str(path), "Batch source", priority=40.0)
    try:
        result = json.loads(facade.extract_batch_create(source, "Q: First?\nA: One\n\nQ: Second?\nA: Two\n\nQ: Broken", "Basic", "item", 30.0, ""))
        assert (result["created"], result["skipped"], result["failed"]) == (2, 1, 0)
        assert result["summary"]
        notes = rt.col.find_notes('"Front:First?" or "Front:Second?"')
        assert len(notes) == 2
        assert {rt.col.get_note(nid)["Back"] for nid in notes} == {"One", "Two"}
        # Each sits under its source in the knowledge tree, as any extract does.
        tree = json.loads(facade.knowledge_tree())
        parents = {row["card_id"]: row.get("parent_card_id") for row in tree}
        assert all(parents.get(card) == source for nid in notes for card in rt.col.card_ids_of_note(nid))
    finally:
        rt.col.remove_notes(list(rt.col.find_notes('"Front:First?" or "Front:Second?"')) + [rt.col.get_card(source).nid])


def test_labels_are_desktops(rt):
    from incremento.backend.i18n import t

    labels = json.loads(facade.extract_batch_labels())
    assert labels["button"] == t("add_card_batch_qa")
    assert labels["create"] == t("extract_batch_create_all")
