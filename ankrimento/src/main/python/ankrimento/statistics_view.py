"""The statistics screen by scope, as desktop's statistics dialog shows each.

Upstream's ``backend/statistics.py`` loads the numbers and
``frontend/stats_progress.py`` models the progress dashboard. The dialog and the
dashboard are Qt, so their pure helpers are compiled from ``frontend/stats_dialog.py``
and ``frontend/stats_dashboard.py`` alone; the phone shows desktop's numbers,
texts and rounding.

Scopes are the dialog's: "daily" (today), "7d", "30d", "365d" (a year) and
"lifetime". Desktop's session scope needs the desktop session's counters.
"""

from __future__ import annotations

import ast
import math
from datetime import date
from pathlib import Path
from typing import Any

from ankrimento import runtime

SCOPES = ("daily", "7d", "30d", "365d", "lifetime")
_PERIODS = {"7d": 7, "30d": 30, "365d": 365}
_TITLES = {
    "daily": "stats_scope_today",
    "7d": "stats_scope_7_days",
    "30d": "stats_scope_30_days",
    "365d": "stats_scope_year",
    "lifetime": "stats_scope_all_time",
}
_METRICS = ("pages", "minutes", "cards")

# The helpers used here and the ones they call, by the file they are compiled from.
_HELPERS = {
    "stats_dialog.py": (
        "_type_label",
        "_fmt_duration",
        "_format_count",
        "_format_average",
        "_topic_item_pie_items",
        "_history_day_values",
        "_reading_value",
        "_history_total_seconds",
        "_history_row_totals",
        "_history_insight",
        "_history_day_detail",
        "_activity_heatmap_model",
        "_heatmap_month_labels",
        "_ordered_type_items",
    ),
    "stats_dashboard.py": ("_metric_name", "_date_range"),
}
_CONSTANTS = ("_TYPE_ORDER",)
_helpers: dict[str, Any] | None = None


def _compiled() -> dict[str, Any]:
    """Upstream's helpers, compiled from the dialog's files with what they import."""
    global _helpers
    if _helpers is not None:
        return _helpers
    from incremento.backend import statistics
    from incremento.backend.i18n import format_date, format_number, t, tn

    namespace: dict[str, Any] = {
        "math": math,
        "date": date,
        "t": t,
        "tn": tn,
        "format_date": format_date,
        "format_number": format_number,
        "_empty": statistics._empty,
        "_empty_time": statistics._empty_time,
        "_normalize_counts_block": statistics._normalize_counts_block,
        "_normalize_time_block": statistics._normalize_time_block,
        "_normalize_tag_breakdown_block": statistics._normalize_tag_breakdown_block,
        "UNTAGGED_STATS_TAG": statistics.UNTAGGED_STATS_TAG,
    }
    frontend = Path(runtime.current().addon_dir) / "frontend"
    for file_name, names in _HELPERS.items():
        path = frontend / file_name
        tree = ast.parse(path.read_text(encoding="utf8"), filename=str(path))
        functions = []
        for node in tree.body:
            if isinstance(node, ast.FunctionDef) and node.name in names:
                functions.append(node)
            elif isinstance(node, ast.Assign) and len(node.targets) == 1:
                target = node.targets[0]
                if isinstance(target, ast.Name) and target.id in _CONSTANTS:
                    namespace[target.id] = ast.literal_eval(node.value)
        missing = set(names) - {node.name for node in functions}
        if missing:
            raise RuntimeError(f"upstream statistics helpers not found in {file_name}: {sorted(missing)}")
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(path), "exec"), namespace)  # noqa: S102 - upstream's own code
    if any(name not in namespace for name in _CONSTANTS):
        raise RuntimeError("upstream statistics constants not found")
    _helpers = namespace
    return namespace


def view(scope: str) -> dict[str, Any]:
    """What desktop's dialog shows for ``scope``, as text and numbers for the phone's screen."""
    if scope not in SCOPES:
        raise ValueError(f"unknown statistics scope: {scope!r}")
    from incremento.backend.i18n import t
    from incremento.backend.scheduler_config import load_scheduler_config
    from incremento.backend.statistics import _effective_date, load_daily_history, load_media_activity, load_stats

    rt = runtime.current()
    addon_dir, profile = str(rt.addon_dir), rt.profile
    day_end = load_scheduler_config().day_end_time
    helpers = _compiled()
    today = _effective_date(day_end)
    # As the dialog: two years of history, its media activity per scope.
    history = list(load_daily_history(addon_dir, profile, days=730, day_end_time=day_end))
    media_days = {"daily": 1, "lifetime": None}.get(scope, _PERIODS.get(scope))
    media = load_media_activity(addon_dir, profile, days=media_days, day_end_time=day_end)
    stats = load_stats(addon_dir, profile)
    counts, seconds = _scope_counts(stats, scope, today)

    result: dict[str, Any] = {
        "title": t(_TITLES[scope]),
        "scopes": [[key, t(_TITLES[key])] for key in SCOPES],
        "dashboard": _dashboard(helpers, scope, history, today, media, counts, seconds),
        "media": [
            [t(label), value]
            for label, value in (
                ("stats_pages_read", helpers["_format_count"](media.get("pages", 0))),
                ("stats_pdf_pages_read", helpers["_format_count"](media.get("pdf_pages", 0))),
                ("stats_epub_pages_read", helpers["_format_count"](media.get("epub_pages", 0))),
                ("stats_videos_watched", helpers["_format_count"](media.get("videos_watched", 0))),
                ("stats_video_watch_time", helpers["_fmt_duration"](float(media.get("video_watch_seconds", 0) or 0))),
            )
        ],
        "media_title": t("stats_reading_video_activity"),
    }
    if scope in _PERIODS:
        shown = history[-_PERIODS[scope]:]
        cells = helpers["_activity_heatmap_model"](shown)
        # As the dialog's ``_ActivityHeatmap``: a row per weekday from Monday, a column per week.
        first_weekday = date.fromisoformat(cells[0]["date"]).weekday() if cells else 0
        weeks = max(1, math.ceil((first_weekday + len(cells)) / 7))
        result.update(
            insight=helpers["_history_insight"](shown),
            heatmap_title=t("stats_activity_heatmap"),
            heatmap_hint=t("stats_heatmap_select_day"),
            first_weekday=first_weekday,
            months=helpers["_heatmap_month_labels"](cells, first_weekday=first_weekday, weeks=weeks),
            weekdays=[t(f"stats_weekday_{day}") for day in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")],
            days=[
                {"date": cell["date"], "intensity": cell["intensity"], "detail": _day_detail(helpers, cell["detail"])}
                for cell in cells
            ],
        )
        return result

    breakdown = _scope_tags(stats, scope, today, rt.col, day_end)
    result.update(
        kinds_title=t("stats_card_type_distribution"),
        kinds=[[label, helpers["_format_count"](value)] for label, value in helpers["_ordered_type_items"](counts["type"])],
        # The dialog's tag pies, as rows: a tag, its cards and time, and the cards to size its bar.
        tags={
            kind: [
                [label, helpers["_format_count"](cards), helpers["_fmt_duration"](seconds), cards]
                for label, seconds, cards in helpers["_topic_item_pie_items"](breakdown, kind)
            ]
            for kind in ("topics", "items")
        },
        tag_titles={"topics": t("stats_topic_tag_time"), "items": t("stats_item_tag_time")},
        empty=t("stats_no_scope_statistics"),
    )
    return result


def _scope_counts(stats: dict, scope: str, today: str) -> tuple[dict, dict]:
    """The dialog's ``_get_counts`` and ``_get_time``: today's when they are today's, otherwise all time."""
    from incremento.backend.statistics import (
        _is_valid_counts_block,
        _is_valid_time_block,
        _normalize_counts_block,
        _normalize_time_block,
    )

    time_raw = stats.get("time") if isinstance(stats.get("time"), dict) else {}
    if scope != "daily":
        return _normalize_counts_block(stats.get("lifetime")), _normalize_time_block(time_raw.get("lifetime"))
    daily = stats.get("daily") if isinstance(stats.get("daily"), dict) else {}
    counts = (
        _normalize_counts_block(daily["counts"])
        if daily.get("date") == today and _is_valid_counts_block(daily.get("counts"))
        else _normalize_counts_block({})
    )
    daily_time = time_raw.get("daily") if isinstance(time_raw.get("daily"), dict) else {}
    seconds = (
        _normalize_time_block(daily_time["seconds"])
        if daily_time.get("date") == today and _is_valid_time_block(daily_time.get("seconds"))
        else _normalize_time_block({})
    )
    return counts, seconds


def _scope_tags(stats: dict, scope: str, today: str, col, day_end: str) -> dict:
    """The dialog's ``_get_tag_breakdown``: today's from Anki's review log when it can be read."""
    from incremento.backend.statistics import _normalize_tag_breakdown_block, daily_tag_breakdown_from_collection

    raw = stats.get("tag_breakdown") if isinstance(stats.get("tag_breakdown"), dict) else {}
    if scope == "lifetime":
        return _normalize_tag_breakdown_block(raw.get("lifetime"))
    live = daily_tag_breakdown_from_collection(col, day_end)
    if live is not None:
        return _normalize_tag_breakdown_block(live)
    daily = raw.get("daily")
    return _normalize_tag_breakdown_block(daily if isinstance(daily, dict) and daily.get("date") == today else {})


def _dashboard(helpers, scope, history, today, media, counts, seconds) -> dict[str, Any]:
    """The dialog's ``_add_progress_dashboard`` and its ``ProgressDashboard``, as text and bars."""
    from incremento.backend.i18n import format_number, t, tn
    from incremento.frontend.stats_progress import progress_model

    days = {"daily": 7, "7d": 7, "30d": 30, "365d": 365, "lifetime": 30}[scope]
    model = progress_model(history, end_date=today, days=days)
    totals = dict(model["totals"])
    comparison = model["comparison"]
    comparison_days = days
    if scope in {"daily", "lifetime"}:
        totals = {
            "cards": float(sum(counts["type"].values())),
            "minutes": helpers["_history_total_seconds"](seconds) / 60,
            **{metric: float(media.get(metric, 0) or 0) for metric in ("pages", "pdf_pages", "epub_pages")},
        }
        comparison_days = 1
        comparison = None
        if scope == "daily":
            one_day = progress_model(history, end_date=today, days=1)
            # Keep the overview on the same normalized row.
            if any(row.get("date") == today for row in history):
                totals["cards"] = one_day["totals"]["cards"]
                totals["minutes"] = one_day["totals"]["minutes"]
            comparison = one_day["comparison"]

    def value(metric: str, amount: float) -> str:
        return helpers["_fmt_duration"](amount * 60) if metric == "minutes" else helpers["_format_count"](amount)

    def change(metric: str) -> str | None:
        if comparison is None:
            return None
        before = comparison[metric]
        if before["percent"] is None:
            return t("stats_progress_no_baseline")
        percent = round(before["percent"])
        return t("stats_progress_comparison", change=("+" if percent > 0 else "") + format_number(percent) + "%", days=comparison_days)

    def hint(metric: str) -> str:
        if metric == "pages":
            return t("stats_progress_formats", pdf=value("pages", totals["pdf_pages"]), epub=value("pages", totals["epub_pages"]))
        return t("stats_progress_time_hint" if metric == "minutes" else "stats_progress_cards_hint")

    def detail(index: int, metric: str) -> str:
        row = model["buckets"][index]
        return t(
            "stats_progress_day_detail",
            date=helpers["_date_range"](row),
            pages=value("pages", row["pages"]),
            pdf=value("pages", row["pdf_pages"]),
            epub=value("pages", row["epub_pages"]),
            duration=value("minutes", row["minutes"]),
            cards=value("cards", row["cards"]),
            total=value(metric, model["cumulative"][metric][index]),
            metric=helpers["_metric_name"](metric),
        )

    scope_label = t(_TITLES[scope])
    return {
        "heading": t("stats_progress_heading", scope=scope_label),
        "streak": tn("stats_progress_streak", model["reading_streak"]),
        "tiles": [
            {"key": metric, "name": helpers["_metric_name"](metric), "value": value(metric, totals[metric]),
             "hint": hint(metric), "comparison": change(metric)}
            for metric in _METRICS
        ],
        "metrics": [[metric, t(label)] for metric, label in (("pages", "stats_pages"), ("minutes", "stats_study_time"), ("cards", "stats_cards"))],
        "chart_window": t("stats_progress_graph_window", period=tn("stats_progress_last_days", days)),
        "chart_title": t("stats_progress_weekly" if len(model["daily"]) > 31 else "stats_progress_daily"),
        "consistency": t(
            "stats_progress_consistency",
            reading=model["reading_days"],
            active=model["active_days"],
            days=len(model["daily"]),
            average=format_number(round(model["totals"]["pages"] / len(model["daily"]), 1)),
        ),
        "empty": t("stats_progress_empty"),
        "bars": [
            {
                "label": helpers["_date_range"](bucket),
                **{metric: float(bucket[metric]) for metric in _METRICS},
                "detail": {metric: detail(index, metric) for metric in _METRICS},
            }
            for index, bucket in enumerate(model["buckets"])
        ],
    }


def _day_detail(helpers: dict[str, Any], detail: dict) -> str:
    """The dialog's line for a day picked on its heatmap (``_show_day_drilldown``)."""
    from incremento.backend.i18n import t

    count = helpers["_format_count"]
    return t(
        "stats_day_detail",
        date=detail.get("date") or t("stats_selected_day"),
        cards=count(float(detail.get("cards", 0))),
        topics=count(float(detail.get("topics", 0))),
        items=count(float(detail.get("items", 0))),
        other=count(float(detail.get("other_cards", 0))),
        pdf_pages=count(float(detail.get("pdf_pages", 0))),
        epub_pages=count(float(detail.get("epub_pages", 0))),
        duration=helpers["_fmt_duration"](float(detail.get("seconds", 0))),
    )
