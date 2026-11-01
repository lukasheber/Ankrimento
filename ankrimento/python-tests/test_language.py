"""The backend's message language, chosen at startup as desktop's add-on chooses it."""

from __future__ import annotations

import copy

import pytest
from aqt import mw

from ankrimento import runtime


@pytest.fixture
def language_choice(rt):
    """Sets Incremento's language setting; later tests expect the settings and English as they were."""
    from incremento.backend.config_service import load_addon_config, save_addon_config
    from incremento.backend.i18n import initialize_language

    saved = copy.deepcopy(mw.addonManager.getConfig("incremento"))

    def choose(value: str) -> None:
        config = load_addon_config(mw.addonManager, "incremento")
        config["ui_language"] = value
        save_addon_config(mw.addonManager, "incremento", config)

    yield choose
    mw.addonManager.writeConfig("incremento", saved)
    initialize_language("en")


def test_auto_follows_the_app_language_when_upstream_has_it(rt, language_choice):
    from incremento.backend.i18n import get_locale, t

    language_choice("auto")
    runtime.activate_language("hr-HR")
    assert get_locale() == "hr"
    assert t("reader_epub_link_blocked") != "Blocked an unsafe or unsupported EPUB link."

    runtime.activate_language("zh-CN")
    assert get_locale() == "zh-Hans"


def test_a_language_upstream_lacks_falls_back_to_english(rt, language_choice):
    from incremento.backend.i18n import get_locale

    language_choice("auto")
    runtime.activate_language("pt-BR")
    assert get_locale() == "en"


def test_the_language_chosen_in_incremento_wins(rt, language_choice):
    from incremento.backend.i18n import get_locale

    language_choice("hr")
    runtime.activate_language("en-US")
    assert get_locale() == "hr"
