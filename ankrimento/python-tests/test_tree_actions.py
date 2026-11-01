"""Knowledge tree tools: search, and postponing a branch as desktop's postpone dialog does."""

from __future__ import annotations

import json

import pytest

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


@pytest.fixture
def branch(rt, tmp_path):
    """A PDF with a topic extracted from it, due today after 900 days (upstream's default postpones
    topics beyond 800 days, as SuperMemo's does)."""
    source = tmp_path / "Branch Source.pdf"
    source.write_bytes(_MINIMAL_PDF)
    parent = facade.import_pdf(str(source), "Branch Source", priority=50.0)
    facade.pdf_message(parent, "incremento_fill_field:" + json.dumps({"idx": 0, "text": "Branch topic"}))
    note = json.loads(facade.create_extract(parent, json.dumps(["Branch topic", "hidden detail"]), "Basic", "topic", 40.0))["note_id"]
    child = rt.col.get_note(note).card_ids()[0]
    rt.col.sched.set_due_date([child], "0")
    card = rt.col.get_card(child)
    card.ivl = 900
    rt.col.update_card(card)
    yield parent, child
    rt.col.remove_notes([note, rt.col.get_card(parent).nid])


def test_search_finds_titles_and_note_text_in_tree_order(rt, branch):
    parent, child = branch

    assert [hit["card_id"] for hit in json.loads(facade.tree_search("Branch"))][:2] == [parent, child]
    by_text = json.loads(facade.tree_search("hidden detail"))
    assert [hit["card_id"] for hit in by_text] == [child]
    assert by_text[0]["match_reason"]


def test_postponing_a_branch_shows_upstreams_simulation_then_moves_its_due_cards(rt, branch):
    parent, child = branch

    plan = json.loads(facade.tree_postpone_plan(parent))
    assert plan["count"] >= 1
    assert plan["lines"] and all(isinstance(line, str) and line for line in plan["lines"])
    due_before = rt.col.get_card(child).due

    done = json.loads(facade.tree_postpone_branch(parent))
    assert done["count"] == plan["count"]
    assert done["message"]
    assert rt.col.get_card(child).due > due_before
