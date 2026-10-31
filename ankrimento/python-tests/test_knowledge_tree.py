"""The knowledge tree as the phone shows it, and branch sessions."""

from __future__ import annotations

import json
import threading

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


def test_extracts_appear_under_their_source_and_a_branch_session_starts(rt, tmp_path):
    from aqt import mw

    source = tmp_path / "Tree Source.pdf"
    source.write_bytes(_MINIMAL_PDF)
    parent = facade.import_pdf(str(source), "Tree Source", priority=50.0)
    facade.pdf_message(parent, "incremento_fill_field:" + json.dumps({"idx": 0, "text": "Child fact"}))
    child_note = json.loads(facade.create_extract(parent, json.dumps(["Child fact", "answer"]), "Basic", "item", 49.0))["note_id"]
    child = rt.col.get_note(child_note).card_ids()[0]

    rows = {row["card_id"]: row for row in json.loads(facade.knowledge_tree())}
    assert rows[child]["depth"] == rows[parent]["depth"] + 1
    assert rows[child]["parent_card_id"] == parent
    assert rows[parent]["title"] == "Tree Source"

    entered = threading.Event()
    previous = mw.host_events
    mw.host_events = lambda event, fields: entered.set() if event == "move_to_state" and fields["state"] == "review" else None
    try:
        assert facade.start_branch_session(parent)
        assert entered.wait(timeout=30)
    finally:
        mw.host_events = previous
        facade.reviewer_closed()  # end the session for later tests
