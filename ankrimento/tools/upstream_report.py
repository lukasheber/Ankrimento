"""What changed in desktop Incremento between the version the phone pins and the one in ``vendor/incremento``.

Run it after moving the submodule (UPSTREAM.md, step 2)::

    ankrimento/.venv/Scripts/python ankrimento/tools/upstream_report.py [--from REV] [--to REV]

``--from`` defaults to the commit this repository pins (``git ls-tree HEAD vendor/incremento``), ``--to`` to the
submodule's checked-out commit. It only reads; it changes nothing. The report answers, before any test runs:

* which releases and commits are in between, and what the changelog says;
* which of the files the phone ships changed, were added or removed (``SHIPPED_ENTRIES`` in ``addon.py``);
* which database migrations are new (the phone's incremento.db is migrated by upstream's code at start);
* which upstream frontend files the changed shipped files import but the phone does not ship;
* which Qt and aqt names the shipped code newly imports that the phone has no stand-in for;
* which translation keys are new.

It does not judge importance: read it with the release notes, then decide what to bring over (ROADMAP.md).
"""

from __future__ import annotations

import argparse
import ast
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "vendor" / "incremento"
PHONE_PYTHON_ROOT = ROOT / "ankrimento" / "src" / "main" / "python"
ADDON = PHONE_PYTHON_ROOT / "ankrimento" / "addon.py"


# --- Parsing (pure; tested on samples) ----------------------------------------------------------------------------

def shipped_entries() -> tuple[str, ...]:
    """``SHIPPED_ENTRIES`` from the phone's ``addon.py``, read without importing it."""
    tree = ast.parse(ADDON.read_text(encoding="utf8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "SHIPPED_ENTRIES" for t in node.targets):
            return tuple(ast.literal_eval(node.value))
    raise LookupError("addon.py has no SHIPPED_ENTRIES")


def is_shipped(path: str, entries: tuple[str, ...]) -> bool:
    return any(path == entry or path.startswith(entry + "/") for entry in entries)


def migrations(text: str) -> dict[int, str]:
    """Schema migrations by number and name, from ``_SCHEMA_MIGRATIONS`` in upstream's ``backend/db.py``."""
    block = re.search(r"_SCHEMA_MIGRATIONS\s*=\s*\((.*?)\n\)", text, re.S)
    return {int(number): name for number, name in re.findall(r'\(\s*(\d+),\s*"([A-Za-z0-9_]+)"', block.group(1))} if block else {}


def changelog_sections(diff: str) -> list[tuple[str, list[str]]]:
    """The headings a changelog diff adds, each with its added bullets."""
    sections: list[tuple[str, list[str]]] = []
    for line in diff.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        text = line[1:]
        if text.startswith("## "):
            sections.append((text[3:].strip(), []))
        elif text.startswith("- ") and sections:
            sections[-1][1].append(text[2:].strip())
    return sections


_FRONTEND_IMPORT = re.compile(r"^\s*(?:from\s+(?:\.(\w+)|frontend\.(\w+))\s+import|import\s+frontend\.(\w+))", re.M)


def frontend_imports(text: str, relative: bool = True) -> set[str]:
    """Names of ``frontend/<name>.py`` modules a file imports, written relatively (only inside ``frontend/``) or as ``frontend.<name>``."""
    return {match[1] or match[2] or (match[0] if relative else "") for match in _FRONTEND_IMPORT.findall(text)} - {""}


def qt_imports(text: str) -> set[str]:
    """Dotted names imported from ``PyQt6`` and ``aqt``, e.g. ``PyQt6.QtPdf.QPdfDocument``."""
    names: set[str] = set()
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return names
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and node.level == 0 and node.module.split(".")[0] in ("PyQt6", "aqt"):
            names |= {f"{node.module}.{alias.name}" for alias in node.names}
        elif isinstance(node, ast.Import):
            names |= {alias.name for alias in node.names if alias.name.split(".")[0] in ("PyQt6", "aqt")}
    return names


def message_keys(po_text: str) -> set[str]:
    return set(re.findall(r'^msgid "([a-z][a-z0-9_]+)"', po_text, re.M))


def stand_in_gaps(new: set[str], old: set[str], provided: dict[str, set[str]]) -> list[str]:
    """Qt and aqt names newly imported (not in ``old``) that no stand-in module provides."""
    gaps = []
    for name in sorted(new - old):
        module, _, member = name.rpartition(".")
        if module in provided and member in provided[module]:
            continue
        if name in provided:  # a whole module, e.g. "import PyQt6.QtGui", with a stand-in module
            continue
        gaps.append(name)
    return gaps


def provided_names() -> dict[str, set[str]]:
    """Names each stand-in module (``PyQt6/*.py``, ``aqt/*.py``) defines, by dotted module name."""
    provided: dict[str, set[str]] = {}
    for package in ("PyQt6", "aqt"):
        for path in (PHONE_PYTHON_ROOT / package).glob("*.py"):
            module = package if path.stem == "__init__" else f"{package}.{path.stem}"
            tree = ast.parse(path.read_text(encoding="utf8"))
            names: set[str] = set()
            for node in tree.body:
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    names.add(node.name)
                elif isinstance(node, ast.Assign):
                    names |= {t.id for t in node.targets if isinstance(t, ast.Name)}
                elif isinstance(node, ast.ImportFrom):
                    names |= {alias.asname or alias.name for alias in node.names}
            provided[module] = names
    return provided


# --- Reading upstream (git) ---------------------------------------------------------------------------------------

def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return result.stdout if result.returncode == 0 else ""


def pinned_commit() -> str:
    line = _git(ROOT, "ls-tree", "HEAD", "vendor/incremento").split()
    return line[2] if len(line) >= 3 else ""


def _show(rev: str, path: str) -> str:
    return _git(UPSTREAM, "show", f"{rev}:{path}")


def _files(rev: str) -> list[str]:
    return _git(UPSTREAM, "ls-tree", "-r", "--name-only", rev).splitlines()


def _describe(rev: str) -> str:
    return _git(UPSTREAM, "describe", "--tags", "--always", rev).strip()


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # changelog headings have typographic dashes
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="old", default=None, help="the version in use (default: the pinned commit)")
    parser.add_argument("--to", dest="new", default=None, help="the new version (default: the submodule's checkout)")
    options = parser.parse_args(argv)
    old = options.old or pinned_commit()
    new = options.new or _git(UPSTREAM, "rev-parse", "HEAD").strip()
    if not old or not new:
        print("Cannot tell the two versions: run from a checkout with vendor/incremento initialised.", file=sys.stderr)
        return 1
    entries = shipped_entries()

    print(f"From {_describe(old)} ({old[:10]}) to {_describe(new)} ({new[:10]})")
    commits = _git(UPSTREAM, "log", "--first-parent", "--format=%h %ad %s", "--date=short", f"{old}..{new}").splitlines()
    tags = [t for t in _git(UPSTREAM, "tag", "--sort=creatordate", "--merged", new).split() if t not in
            _git(UPSTREAM, "tag", "--merged", old).split()]
    print(f"\n== Releases and commits in between: {len(commits)} merged changes; releases {', '.join(tags) or 'none (not tagged)'}")
    for line in commits[:25]:
        print(f"  {line}")
    beyond = _git(UPSTREAM, "describe", "--tags", "--long", new).strip()
    if beyond and not beyond.split("-g")[0].endswith("-0"):
        print(f"  note: {new[:10]} is past the last release ({beyond}); a tag is a release, anything after it is not")

    print("\n== Changelog")
    for heading, bullets in changelog_sections(_git(UPSTREAM, "diff", old, new, "--", "CHANGELOG.md")):
        print(f"  {heading}")
        for bullet in bullets:
            print(f"    - {bullet[:240]}")

    print("\n== Shipped files (SHIPPED_ENTRIES)")
    changed: dict[str, str] = {}
    for line in _git(UPSTREAM, "diff", "--numstat", old, new).splitlines():
        added, removed, path = line.split("\t", 2)
        if is_shipped(path, entries) and not path.startswith("tests/"):
            changed[path] = f"+{added} -{removed}"
    status = {line.split("\t")[1]: line.split("\t")[0] for line in _git(UPSTREAM, "diff", "--name-status", old, new).splitlines() if "\t" in line}
    groups = {"locales/": "translations", "backend/mcp_": "the MCP server for AI tools (desktop only)"}
    for prefix, what in groups.items():
        members = [path for path in changed if path.startswith(prefix)]
        if members:
            print(f"  {len(members)} files under {prefix}* ({what})")
    for path in sorted(changed):
        if not any(path.startswith(prefix) for prefix in groups):
            print(f"  {status.get(path, '?')} {path} ({changed[path]})")
    print(f"  {len(changed)} shipped files differ")

    print("\n== Database migrations")
    before, after = migrations(_show(old, "backend/db.py")), migrations(_show(new, "backend/db.py"))
    added = {n: name for n, name in after.items() if n not in before}
    print("  new: " + (", ".join(f"{n} {name}" for n, name in sorted(added.items())) or "none"))
    print(f"  schema {max(before, default=1)} -> {max(after, default=1)}"
          "; the phone's incremento.db is migrated by this code at start, so check a restore on desktop (step 6)")

    print("\n== Upstream frontend files the changed shipped files newly import")
    new_files = set(_files(new))
    shipped_frontend = {p[len("frontend/"):-3] for p in new_files if p.startswith("frontend/") and p.endswith(".py") and is_shipped(p, entries)}
    wanted: dict[str, set[str]] = {}
    for path in changed:
        if path.endswith(".py"):
            relative = path.startswith("frontend/")  # inside backend/, "from .x" is a backend module
            for module in frontend_imports(_show(new, path), relative) - frontend_imports(_show(old, path), relative):
                if module not in shipped_frontend and f"frontend/{module}.py" in new_files:
                    wanted.setdefault(module, set()).add(path)
    for module, users in sorted(wanted.items()):
        print(f"  frontend/{module}.py (new import in {', '.join(sorted(users))}): plain Python the phone runs -> ship it; Qt only -> ignore")
    if not wanted:
        print("  none")

    print("\n== Qt and aqt names the shipped backend newly imports, with no stand-in (the phone runs backend/)")
    old_qt = {name for path in _files(old) if path.startswith("backend/") and path.endswith(".py") for name in qt_imports(_show(old, path))}
    new_qt = {name for path in new_files if path.startswith("backend/") and path.endswith(".py") for name in qt_imports(_show(new, path))}
    gaps = stand_in_gaps(new_qt, old_qt, provided_names())
    for name in gaps:
        print(f"  {name}")
    if not gaps:
        print("  none (new calls on an existing stand-in class are not seen here: the tests find those)")

    print("\n== Translation keys")
    def keys(rev: str) -> set[str]:
        return {k for p in _files(rev) if p.startswith("locales/en/") and p.endswith(".po") for k in message_keys(_show(rev, p))}
    fresh = sorted(keys(new) - keys(old))
    print(f"  {len(fresh)} new, {len(keys(old) - keys(new))} removed" + (f"; first new: {', '.join(fresh[:12])}" if fresh else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
