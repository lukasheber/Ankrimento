"""An extract just added names its card, so the phone can open it ("Show topic") to see or edit it."""

from __future__ import annotations

import json

from ankrimento import facade
from test_pdf import _MINIMAL_PDF


def test_a_new_extract_names_its_card(rt, tmp_path):
    source = tmp_path / "Source.pdf"
    source.write_bytes(_MINIMAL_PDF)
    parent = facade.import_pdf(str(source), "Source", priority=50.0)
    facade.pdf_message(parent, "incremento_fill_field:" + json.dumps({"idx": 0, "text": "Selected text"}))

    made = json.loads(facade.create_extract(parent, json.dumps(["Selected text", ""]), "Basic", "topic", 40.0))

    assert made["card_id"] == rt.col.get_note(made["note_id"]).card_ids()[0]
    assert facade.card_reader(made["card_id"]) == "text"
    rt.col.remove_notes([made["note_id"], rt.col.get_card(parent).nid])
