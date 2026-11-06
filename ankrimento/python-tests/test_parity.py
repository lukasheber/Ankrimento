"""The parity check (tools/parity_check.py) as part of the test suite: an update of desktop's
add-on that breaks what the phone relies on fails here, not on a device."""

from __future__ import annotations

import importlib.util
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("parity_check", Path(__file__).resolve().parents[1] / "tools" / "parity_check.py")
parity = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(parity)


def test_every_desktop_message_is_answered_or_explained():
    new, _known = parity.unanswered_messages()
    assert new == []


def test_desktop_names_the_phone_compiles_still_exist():
    assert parity.missing_compiled() == []


def test_translation_keys_the_phone_uses_still_exist():
    assert parity.missing_keys() == []


def test_shipped_entries_exist():
    assert parity.missing_shipped() == []


def test_mirrored_desktop_functions_still_exist():
    assert parity.mirrored_names()
    assert parity.lost_mirrors() == []


def test_every_mirrored_desktop_function_is_recorded_as_reviewed():
    # A function mirrored without its fingerprint recorded: run the check with --record after comparing.
    assert parity.unrecorded_mirrors() == []


def test_no_mirrored_desktop_function_changed_since_it_was_copied():
    # Desktop changed a function the phone copies: compare, bring the copy up to date, then --record.
    assert parity.changed_mirrors() == []


def test_a_changed_desktop_function_is_reported(monkeypatch):
    recorded = dict(parity._recorded())
    name = sorted(recorded)[0]
    recorded[name] = "0" * 16  # as if desktop's source had been different when the copy was compared
    monkeypatch.setattr(parity, "_recorded", lambda: recorded)
    assert parity.changed_mirrors() == [name]
