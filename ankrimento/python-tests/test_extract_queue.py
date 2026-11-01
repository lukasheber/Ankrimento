"""Extracts queued while reading, then added together, each linked to its own place in the source."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture
def source(rt, tmp_path):
    path = tmp_path / "Queue Source.pdf"
    path.write_bytes(_MINIMAL_PDF)
    card = facade.import_pdf(str(path), "Queue Source", priority=50.0)
    yield card
    facade.discard_extract_queue(card)


def _select(card: int, text: str) -> None:
    facade.pdf_message(card, "incremento_fill_field:" + json.dumps({"idx": 0, "text": text}))


def _queue(card: int, front: str, kind: str = "item") -> int:
    _select(card, front)
    return facade.queue_extract(card, json.dumps([front, ""]), "Basic", kind, 45.0)


def test_queued_extracts_wait_and_the_draft_is_free_for_the_next(rt, source):
    assert _queue(source, "First fact") == 1
    assert json.loads(facade.extract_draft(source))["fields"][0] == ""
    assert _queue(source, "Second idea", "topic") == 2

    queued = json.loads(facade.extract_queue(source))
    assert [(q["fields"][0], q["kind"], q["priority"]) for q in queued] == [
        ("First fact", "item", 45.0),
        ("Second idea", "topic", 45.0),
    ]


def test_one_can_be_dropped_and_the_rest_added_under_the_source(rt, source):
    _queue(source, "Dropped fact")
    _queue(source, "Kept fact")
    facade.drop_queued_extract(source, 0)

    added = json.loads(facade.add_queued_extracts(source))
    assert added["added"] == 1
    assert json.loads(facade.extract_queue(source)) == []
    rows = {row["card_id"]: row for row in json.loads(facade.knowledge_tree())}
    children = [row for row in rows.values() if row["parent_card_id"] == source]
    assert [row["title"] for row in children] == ["Kept fact"]
