"""The bridge, the adopted collection and the installed add-on."""

from __future__ import annotations

import json

import pytest
from anki.errors import NotFoundError

from conftest import INCREMENTO_SOURCE


def test_collection_calls_go_through_the_host(rt):
    # card_count() is a backend RPC; the scalar is a db_command.
    assert rt.col.card_count() == rt.col.db.scalar("select count() from cards")


def test_backend_errors_keep_their_type(rt):
    with pytest.raises(NotFoundError):
        rt.col.get_card(123456789)


def test_addon_has_the_desktop_layout(rt):
    assert (rt.addon_dir / "backend" / "session.py").is_file()
    assert (rt.addon_dir / "user_files" / rt.profile / "incremento.db").is_file()
    # Only the EPUB dock ships from frontend/, as data for its page script.
    assert [p.name for p in (rt.addon_dir / "frontend").iterdir()] == ["epub_dock.py"]
    assert not (rt.addon_dir / "__init__.py").exists()


def test_config_reads_upstream_defaults_and_writes_meta_json(rt):
    from aqt import mw

    defaults = json.loads((INCREMENTO_SOURCE / "config.json").read_text(encoding="utf8"))
    config = mw.addonManager.getConfig("incremento")
    assert config == defaults

    config["ankrimento_test_key"] = 1
    mw.addonManager.writeConfig("incremento", config)
    meta = json.loads((rt.addon_dir / "meta.json").read_text(encoding="utf8"))
    assert meta["config"]["ankrimento_test_key"] == 1


def test_a_second_collection_cannot_take_over_the_host_backend(rt, tmp_path):
    """Upstream opens throwaway collections (e.g. to validate a backup's APKG);
    through the bridge that would open/close the host's own collection."""
    from anki.collection import Collection

    with pytest.raises(Exception):
        Collection(str(tmp_path / "other.anki2"))
    assert rt.col.db.scalar("select count() from col") == 1  # host collection still open


def test_upstream_modules_import_under_their_desktop_names(rt):
    from incremento.backend import session, topic_scheduler

    assert session._ADDON_PKG == "incremento"
    assert topic_scheduler._ADDON_DIR == str(rt.addon_dir)
