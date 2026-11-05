"""The statistics screen by scope, as desktop's statistics dialog shows each."""

from __future__ import annotations

import copy
import json
from datetime import date, timedelta

import pytest

from ankrimento import facade


def _view(scope: str) -> dict:
    return json.loads(facade.statistics_view(scope))


@pytest.fixture
def stats_restored(rt):
    """Upstream's statistics as they were before the test."""
    from incremento.backend.statistics import load_stats, save_stats

    before = copy.deepcopy(load_stats(str(rt.addon_dir), rt.profile))
    yield
    save_stats(str(rt.addon_dir), rt.profile, before)


@pytest.mark.parametrize(("scope", "bars"), [("daily", 7), ("7d", 7), ("30d", 30), ("365d", 53), ("lifetime", 30)])
def test_every_scope_has_desktops_progress_dashboard(rt, scope, bars):
    from incremento.backend.i18n import t

    dashboard = _view(scope)["dashboard"]

    # Pages, study time and cards, in desktop's order and names.
    assert [tile["name"] for tile in dashboard["tiles"]] == [t("stats_pages_read"), t("stats_study_time"), t("stats_cards_studied")]
    assert [key for key, _label in dashboard["metrics"]] == ["pages", "minutes", "cards"]
    assert dashboard["heading"] and dashboard["streak"] and dashboard["consistency"]
    # Daily bars up to a month, weekly ones for a year.
    assert len(dashboard["bars"]) == bars
    assert dashboard["chart_title"] == t("stats_progress_weekly" if bars == 53 else "stats_progress_daily")
    bar = dashboard["bars"][-1]
    assert set(bar) == {"label", "pages", "minutes", "cards", "detail"}
    assert set(bar["detail"]) == {"pages", "minutes", "cards"}


@pytest.mark.parametrize(("scope", "days"), [("7d", 7), ("30d", 30), ("365d", 365)])
def test_a_period_has_desktops_streak_line_and_one_heatmap_day_per_calendar_day(rt, scope, days):
    from incremento.backend.i18n import t

    view = _view(scope)

    assert view["title"] == t({"7d": "stats_scope_7_days", "30d": "stats_scope_30_days", "365d": "stats_scope_year"}[scope])
    assert view["insight"]
    # Each tile compares with the period before.
    assert all(tile["comparison"] for tile in view["dashboard"]["tiles"])
    rows = view["days"]
    assert len(rows) == days
    # Calendar days without gaps, ending today.
    dates = [date.fromisoformat(row["date"]) for row in rows]
    assert all(later - earlier == timedelta(days=1) for earlier, later in zip(dates, dates[1:]))
    assert set(rows[-1]) == {"date", "intensity", "detail"}
    assert rows[-1]["detail"]
    # Laid out as desktop's heatmap: weekday rows from Monday, a month label per week column.
    assert view["first_weekday"] == dates[0].weekday()
    assert len(view["months"]) == -(-(view["first_weekday"] + days) // 7)
    assert view["weekdays"] == [t(f"stats_weekday_{day}") for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")]
    assert [key for key, _label in view["scopes"]] == ["daily", "7d", "30d", "365d", "lifetime"]


def test_today_and_all_time_show_cards_by_kind_and_by_tag(rt, stats_restored):
    from incremento.backend.statistics import record_tag_breakdown

    addon_dir, profile = str(rt.addon_dir), rt.profile
    record_tag_breakdown(addon_dir, profile, study_kind="topics", tag="Physics", include_count=True, seconds=30)
    record_tag_breakdown(addon_dir, profile, study_kind="items", tag=None, include_count=True, seconds=12)

    lifetime = _view("lifetime")
    topics = {label: (count, duration, cards) for label, count, duration, cards in lifetime["tags"]["topics"]}
    count, duration, cards = topics["Physics"]
    assert cards >= 1 and count == str(int(cards))
    assert duration  # desktop's duration text
    # Desktop's own name for cards without a tag.
    from incremento.backend.i18n import t

    assert t("stats_untagged") in {row[0] for row in lifetime["tags"]["items"]}
    assert lifetime["tag_titles"] == {"topics": t("stats_topic_tag_time"), "items": t("stats_item_tag_time")}
    assert "kinds" in lifetime and "media" in lifetime

    today = _view("daily")
    assert "days" not in today
    assert set(today["tags"]) == {"topics", "items"}


def test_an_unknown_scope_is_refused(rt):
    with pytest.raises(ValueError):
        _view("decade")
