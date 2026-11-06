"""Parity check: where the phone depends on desktop Incremento, and whether it still holds.

Run it after updating ``vendor/incremento`` (see ``UPSTREAM.md``)::

    ankrimento/.venv/Scripts/python ankrimento/tools/parity_check.py

It reports, and fails (exit 1) on, what a new desktop version can break without
any test noticing:

* messages desktop's reader pages send (``pycmd``) that the phone neither
  answers nor lists below with a reason (a new desktop feature to bring over);
* desktop functions and constants the phone compiles from desktop's Qt files
  (``ast``) that are gone or renamed;
* translation keys the phone looks up with ``t()``/``tn()`` that are gone;
* entries the phone ships that are no longer in the add-on.

It warns about the desktop functions the phone mirrors ("Mirrors ``name``")
that it can no longer find, so their copies can be checked against desktop.
"""

from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "vendor" / "incremento"
PHONE_PYTHON = ROOT / "ankrimento" / "src" / "main" / "python" / "ankrimento"
PHONE_SOURCES = ROOT / "ankrimento" / "src" / "main"

# The pages whose messages the phone receives.
PAGES = ("web/dist/pdf_viewer.js", "frontend/epub_dock.py", "web/web_dock_bridge.js", "web/video_player.html")

# Messages (or names that look like them) the phone does not answer, each with why. A message
# missing here and on the phone is new on desktop: bring it over, or add it here with the reason.
NOT_ANSWERED = {
    # Page element ids and toolbar groups, not messages.
    "incremento_epub_dock": "an element id",
    "incremento_epub_toolbar_group": "an element class",
    "incremento_epub_progress_meter": "an element id",
    # Desktop's own panels and window layout; the phone has its own bars and sheets.
    "incremento_epub_controls": "desktop's control panel state",
    "incremento_pdf_controls": "desktop's control panel state",
    "incremento_selection_state": "desktop's toolbar follows the selection; the phone uses Android's selection menu",
    "incremento_pdf_focus": "desktop's focus mode",
    "incremento_pdf_annotations": "desktop's annotation sync panel (sync is deferred)",
    "incremento_pdf_range": "desktop streams large PDFs in ranges; the phone opens the file",
    "incremento_pdf_page_labels": "desktop keeps printed page labels for its own labels",
    "incremento_pdf_regenerate_cover": "desktop's bookshelf cover",
    # The phone's own screens do these (see ROADMAP.md).
    "incremento_pdf_find": "the phone's Find sheet (the viewer's find box shows only with its controls)",
    "incremento_pdf_find_nav": "the phone's Find bar",
    "incremento_pdf_open_find_dialog": "the phone's Find sheet",
    "incremento_pdf_review_all": "PDF reader menu -> Review cards from this document",
    "incremento_pdf_due_review": "PDF reader menu -> Review cards from this document",
    # Known gaps: desktop dialogs the viewer opens that the phone does not have yet (UPSTREAM.md).
    "incremento_pdf_hl_color": "GAP: desktop's highlight colour chooser",
    "incremento_pdf_hl_card": "GAP: a card from one highlight",
    "incremento_pdf_hl_bulk_cards": "GAP: cards from many highlights",
    "incremento_open_card": "GAP: opening a card listed on the page",
    "incremento_open_page_cards": "GAP: the page's cards panel",
    "incremento_open_all_pdf_cards": "GAP: all of the document's cards",
    "incremento_open_add_card": "GAP: desktop's Add Card dock",
    "incremento_pdf_limit_settings": "GAP: the daily page limit's settings",
    "incremento_epub_snapshot": "GAP: EPUB snapshots",
}

# Desktop names the phone compiles from desktop's Qt files: (phone module, variable, desktop file).
# A dict variable maps desktop files to names itself.
COMPILED = (
    ("epub_reader.py", "_SCRIPT_CONSTANTS", "frontend/epub_dock.py"),
    ("epub_reader.py", "_LINK_FUNCTIONS", "frontend/epub_dock.py"),
    ("statistics_view.py", "_HELPERS", None),
    ("statistics_view.py", "_CONSTANTS", "frontend/stats_dialog.py"),
    ("writing.py", "_TEXT_HELPERS", "frontend/writing_dock.py"),
)

_MESSAGE = re.compile(r"""["'`](incremento_[a-z_]+)(?::|["'`])""")
_KEY = re.compile(r"""\btn?\(\s*["']([a-z][a-z0-9_]+)["']""")
_MIRROR = re.compile(r"[Mm]irrors\s+``(?:[\w/.]+\.py:)?([A-Za-z_][\w.]*)``")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf8", errors="ignore")


def _phone_text() -> str:
    files = [*PHONE_SOURCES.rglob("*.py"), *PHONE_SOURCES.rglob("*.kt")]
    return "\n".join(_read(path) for path in files)


def unanswered_messages() -> tuple[list[str], list[str]]:
    """(new on desktop, known and listed) messages the phone does not answer."""
    sent: set[str] = set()
    for page in PAGES:
        path = UPSTREAM / page
        if path.exists():
            sent |= set(_MESSAGE.findall(_read(path)))
    phone = _phone_text()
    quiet = sorted(message for message in sent if message not in phone)
    return [m for m in quiet if m not in NOT_ANSWERED], [m for m in quiet if m in NOT_ANSWERED]


def _top_level_names(path: Path) -> set[str]:
    tree = ast.parse(_read(path), filename=str(path))
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(node.name)
        elif isinstance(node, ast.Assign):
            names |= {target.id for target in node.targets if isinstance(target, ast.Name)}
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            names.add(node.target.id)
    return names


def _phone_constant(module: str, variable: str):
    tree = ast.parse(_read(PHONE_PYTHON / module))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == variable for t in node.targets):
            return ast.literal_eval(node.value)
    raise LookupError(f"{module} has no {variable}")


def missing_compiled() -> list[str]:
    """Desktop names the phone compiles that desktop's files no longer have."""
    missing = []
    for module, variable, desktop_file in COMPILED:
        value = _phone_constant(module, variable)
        wanted = value.items() if isinstance(value, dict) else [(desktop_file, value)]
        for file_name, names in wanted:
            path = UPSTREAM / (file_name if "/" in file_name else f"frontend/{file_name}")
            present = _top_level_names(path) if path.exists() else set()
            missing += [f"{path.relative_to(UPSTREAM)}: {name} (for {module} {variable})" for name in names if name not in present]
    return missing


def _desktop_keys() -> set[str]:
    keys: set[str] = set()
    for catalog in (UPSTREAM / "locales" / "en").rglob("*.po"):
        keys |= set(re.findall(r'^msgid "([a-z][a-z0-9_]+)"', _read(catalog), re.M))
    return keys


def missing_keys() -> list[str]:
    """Translation keys the phone's Python asks desktop for that are gone."""
    known = _desktop_keys()
    used: dict[str, str] = {}
    for path in PHONE_PYTHON.rglob("*.py"):
        for key in _KEY.findall(_read(path)):
            used.setdefault(key, path.name)
    return [f"{key} (in {module})" for key, module in sorted(used.items()) if key not in known]


# Shipped when present: AnkiWeb writes manifest.json into an installed add-on; the source has none.
OPTIONAL_SHIPPED = {"manifest.json"}


def missing_shipped() -> list[str]:
    entries = _phone_constant("addon.py", "SHIPPED_ENTRIES")
    return [entry for entry in entries if entry not in OPTIONAL_SHIPPED and not (UPSTREAM / entry).exists()]


def mirrored_names() -> list[tuple[str, str]]:
    """(desktop name, phone module) for each "Mirrors ``name``" note in the phone's Python."""
    return sorted({(name, path.name) for path in PHONE_PYTHON.rglob("*.py") for name in _MIRROR.findall(_read(path))})


def lost_mirrors() -> list[str]:
    """Desktop names the phone says it mirrors that no desktop file defines any more."""
    desktop = "\n".join(_read(path) for path in UPSTREAM.rglob("*.py") if "tests" not in path.parts)
    lost = []
    for name, module in mirrored_names():
        last = name.split(".")[-1]
        if not re.search(rf"\b(def|class)\s+{re.escape(last)}\b|^{re.escape(last)}\s*=", desktop, re.M):
            lost.append(f"{name} (mirrored in {module})")
    return lost


def main() -> int:
    new, known = unanswered_messages()
    failures = {
        "Desktop messages the phone does not answer (new: bring over, or list in NOT_ANSWERED)": new,
        "Desktop names compiled by the phone that are gone": missing_compiled(),
        "Translation keys the phone uses that desktop no longer has": missing_keys(),
        "Shipped entries missing from the add-on": missing_shipped(),
    }
    for title, items in failures.items():
        print(f"{title}: {'none' if not items else ''}")
        for item in items:
            print(f"  - {item}")
    gaps = [m for m in known if NOT_ANSWERED[m].startswith("GAP")]
    print(f"Known gaps still open: {len(gaps)}")
    for message in gaps:
        print(f"  - {message}: {NOT_ANSWERED[message][5:]}")
    lost = lost_mirrors()
    print(f"Mirrored desktop names checked: {len(mirrored_names())}")
    print(f"Warnings, mirrored desktop names not found: {'none' if not lost else ''}")
    for item in lost:
        print(f"  - {item}")
    return 1 if any(failures.values()) else 0


if __name__ == "__main__":
    sys.exit(main())
