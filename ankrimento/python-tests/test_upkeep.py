"""Desktop's upkeep: the support bundle and card format updates."""

from __future__ import annotations

import json
import zipfile

from ankrimento import facade


def test_the_support_bundle_is_desktops_privacy_safe_zip(rt, tmp_path):
    path = tmp_path / "support.zip"
    result = json.loads(facade.support_bundle(str(path)))
    assert result["event_count"] >= 0
    with zipfile.ZipFile(path) as bundle:
        names = set(bundle.namelist())
    assert {"README.txt", "config/sanitized.json"} <= names


def test_card_formats_are_current_after_an_update(rt, tmp_path):
    from incremento.backend.note_type_updates import incremento_note_type_specs

    # A document makes its note type; then spoil that note type's template, as an older version left it.
    source = tmp_path / "Formats.md"
    source.write_text("# Formats\n\nText.\n", encoding="utf8")
    card_id = facade.import_markdown(str(source), "Formats")
    note_id = rt.col.get_card(card_id).nid
    spec = next(spec for spec in incremento_note_type_specs() if rt.col.models.by_name(spec.name) is not None)
    model = rt.col.models.by_name(spec.name)
    original = model["tmpls"][0]["qfmt"]
    model["tmpls"][0]["qfmt"] = original + "<!-- older -->"
    rt.col.models.update_dict(model)
    try:
        pending = json.loads(facade.card_format_updates())
        assert spec.name in [row["note_type"] for row in pending]
        assert all(row["changes"] for row in pending)

        assert spec.name in json.loads(facade.apply_card_format_updates())
        assert spec.name not in [row["note_type"] for row in json.loads(facade.card_format_updates())]
    finally:
        rt.col.remove_notes([note_id])
        model = rt.col.models.by_name(spec.name)
        if model["tmpls"][0]["qfmt"] != original:
            model["tmpls"][0]["qfmt"] = original
            rt.col.models.update_dict(model)
