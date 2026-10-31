"""Build ankrimento_sync.ankiaddon, the desktop side of Incremento data sync.

The add-on ships the same syncfolder.py as Ankrimento, copied in here, so
the folder format cannot drift between desktop and phone.

usage: python build.py [output directory]   (default: ../build)
"""

import json
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SYNCFOLDER = HERE.parent / "src/main/python/ankrimento/syncfolder.py"


def main() -> None:
    output_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parent / "build"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / "ankrimento_sync.ankiaddon"
    package = HERE / "ankrimento_sync"
    manifest = {"package": "ankrimento_sync", "name": "Ankrimento Sync", "min_point_version": 241100}
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(package.rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts:
                archive.write(path, path.relative_to(package).as_posix())
        archive.write(SYNCFOLDER, "syncfolder.py")
        archive.writestr("manifest.json", json.dumps(manifest))
    print(output)


if __name__ == "__main__":
    main()
