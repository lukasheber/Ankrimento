"""Copy the sync engine from an Incremento checkout into ``ankrimento/engine``.

The engine (``backend/sync_*.py``) is written for desktop Incremento and is not yet in the vendored release, so it is
shipped as an overlay next to it: the build merges ``engine/backend`` into the add-on's ``backend`` folder. Nothing in
``vendor/incremento`` is edited. Once an upstream release contains the engine, delete the overlay.

    python tools/update_engine.py <incremento checkout> [--commit <sha>]

``engine/ENGINE.json`` records the commit and a hash of every copied file, and ``python-tests/test_engine_sync.py``
fails when a file differs from its hash, so an overlay file cannot be edited by hand.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ENGINE_DIR = Path(__file__).resolve().parents[1] / "engine"

# What the phone needs; sync_topic_anchor.py is a prototype nothing calls yet.
ENGINE_FILES = (
    "sync_anki.py",
    "sync_apply.py",
    "sync_blobs.py",
    "sync_capture.py",
    "sync_documents.py",
    "sync_exchange.py",
    "sync_paths.py",
    "sync_records.py",
    "sync_schema.py",
    "sync_service.py",
    "sync_snapshot.py",
    "sync_store.py",
)


def digest(path: Path) -> str:
    # Line endings differ between checkouts; the hash is of the text a Python import sees.
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--commit", help="commit to record (default: the checkout's HEAD)")
    args = parser.parse_args(argv)
    backend = args.checkout / "backend"
    missing = [name for name in ENGINE_FILES if not (backend / name).is_file()]
    if missing:
        print(f"not in {backend}: {', '.join(missing)}", file=sys.stderr)
        return 1
    commit = args.commit or subprocess.run(
        ["git", "-C", str(args.checkout), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    target = ENGINE_DIR / "backend"
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    files = {}
    for name in ENGINE_FILES:
        text = (backend / name).read_bytes().replace(b"\r\n", b"\n")
        (target / name).write_bytes(text)
        files[name] = digest(target / name)
    manifest = {"source": "BaskovicP/Incremento (sync stack, issue #16)", "commit": commit, "files": files}
    (ENGINE_DIR / "ENGINE.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"copied {len(files)} files from {commit[:10]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
