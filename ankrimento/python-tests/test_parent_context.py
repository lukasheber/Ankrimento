"""Parent context: the source of an extract, read-only, beside the card being reviewed."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture(scope="module")
def extract(rt, tmp_path_factory):
    source = tmp_path_factory.mktemp("parent-context") / "Parent Source.pdf"
    source.write_bytes(_MINIMAL_PDF)
    parent = facade.import_pdf(str(source), "Parent Source", priority=50.0)
    fields = json.dumps(["What does spacing do?", "It strengthens memory."])
    created = json.loads(facade.create_extract(parent, fields, "Basic", "item", 49.0))
    return parent, rt.col.get_note(created["note_id"]).cards()[0].id


def test_an_extract_opens_the_card_it_came_from(rt, extract):
    parent, child = extract

    context = json.loads(facade.parent_context(child))

    assert context["card_id"] == parent
    assert context["available"] is True
    assert "Parent Source" in context["title"]


def test_the_parents_fields_come_back_as_sanitised_markup(rt, extract):
    parent, child = extract

    context = json.loads(facade.parent_context(child))

    assert context["fields"], "a PDF topic has visible fields"
    assert all(set(field) == {"name", "html"} for field in context["fields"])
    assert all("<script" not in field["html"].lower() for field in context["fields"])


def test_a_card_without_a_parent_has_no_context(rt, extract):
    parent, _child = extract

    assert json.loads(facade.parent_context(parent)) == {}


def test_a_parent_that_was_deleted_is_reported_unavailable(rt, tmp_path):
    source = tmp_path / "Doomed.pdf"
    source.write_bytes(_MINIMAL_PDF)
    parent = facade.import_pdf(str(source), "Doomed", priority=50.0)
    created = json.loads(facade.create_extract(parent, json.dumps(["Q", "A"]), "Basic", "item", 49.0))
    child = rt.col.get_note(created["note_id"]).cards()[0].id
    rt.col.remove_notes([rt.col.get_card(parent).nid])

    context = json.loads(facade.parent_context(child))

    assert context["available"] is False
    assert context["fields"] == []
