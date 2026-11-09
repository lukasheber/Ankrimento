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

It also fails when a mirrored desktop function changed since its copy was last
compared: ``tools/mirrors.json`` keeps a fingerprint of each one's source. After
bringing the copies up to date, record the new fingerprints::

    ankrimento/.venv/Scripts/python ankrimento/tools/parity_check.py --record
"""

from __future__ import annotations

import ast
import hashlib
import json
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
    # Desktop's repair of a PDF whose file went missing; on the phone the file lives in the app's own storage.
    "incremento_pdf_repair_missing": "desktop's dialog to replace a missing PDF; the phone re-imports the document",
    # Known gaps: features the readers' pages report that the phone does not have yet (UPSTREAM.md, ROADMAP.md).
    "incremento_epub_passage_status": "GAP: precise passage links between documents (EPUB side)",
    "incremento_pdf_passage_back": "GAP: Jump Back after following a precise passage link (PDF side)",
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
    # Line endings as stored, whatever the checkout made of them: fingerprints match on every machine.
    return path.read_text(encoding="utf8", errors="ignore").replace("\r\n", "\n")


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


MIRRORS_FILE = Path(__file__).resolve().parent / "mirrors.json"


def _desktop_sources() -> dict[str, list[str]]:
    """Each top-level function or class name in desktop's Python, with its source (all definitions of it)."""
    sources: dict[str, list[str]] = {}
    for path in sorted(UPSTREAM.rglob("*.py")):
        if "tests" in path.parts:
            continue
        text = _read(path)
        try:
            tree = ast.parse(text, filename=str(path))
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                segment = ast.get_source_segment(text, node) or ""
                sources.setdefault(node.name, []).append(f"{path.relative_to(UPSTREAM).as_posix()}\n{segment}")
    return sources


def mirror_fingerprints() -> dict[str, str]:
    """A fingerprint of the desktop source behind each mirrored name (the name's last part)."""
    sources = _desktop_sources()
    prints = {}
    for name, _module in mirrored_names():
        found = sources.get(name.split(".")[-1])
        if found:
            prints[name] = hashlib.sha256("\n".join(sorted(found)).encode("utf8")).hexdigest()[:16]
    return prints


def _recorded() -> dict[str, str]:
    return json.loads(MIRRORS_FILE.read_text(encoding="utf8")) if MIRRORS_FILE.exists() else {}


def unrecorded_mirrors() -> list[str]:
    """Mirrored names without a recorded fingerprint (a new copy: record it once compared)."""
    recorded = _recorded()
    return sorted(name for name in mirror_fingerprints() if name not in recorded)


def changed_mirrors() -> list[str]:
    """Mirrored desktop functions whose source changed since the copies were last compared."""
    recorded = _recorded()
    return sorted(name for name, value in mirror_fingerprints().items() if name in recorded and recorded[name] != value)


def record_mirrors() -> None:
    MIRRORS_FILE.write_text(json.dumps(mirror_fingerprints(), indent=4, sort_keys=True) + "\n", encoding="utf8")


def main() -> int:
    if "--record" in sys.argv[1:]:
        record_mirrors()
        print(f"Recorded {len(_recorded())} mirrored desktop functions in {MIRRORS_FILE.name}.")
        return 0
    new, known = unanswered_messages()
    failures = {
        "Desktop messages the phone does not answer (new: bring over, or list in NOT_ANSWERED)": new,
        "Desktop names compiled by the phone that are gone": missing_compiled(),
        "Translation keys the phone uses that desktop no longer has": missing_keys(),
        "Shipped entries missing from the add-on": missing_shipped(),
        "Mirrored desktop functions changed since copied (compare, update the copy, then --record)": changed_mirrors(),
        "Mirrored desktop functions never recorded (compare, then --record)": unrecorded_mirrors(),
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
