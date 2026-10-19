"""Archive naming shared by the CLI and formatting tools; no I/O at import time."""

import re
from pathlib import Path

COLLECTOR_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = COLLECTOR_ROOT / "examples"
MARKER = ".pikdo-example-archive"
PROBE_MARKER = ".pikdo-browser-probe"
MARKER_BODY = b"pikdo-example-archive-v1\n"
MANIFEST = "manifest.json"
JOURNAL = ".collect-examples.pending.json"
LOCK = ".collect-examples.lock"
STAGE = ".response.pending"
ID_PATTERN = re.compile(r"[0-9a-f]{24}")


def archive_root(path: Path) -> bool:
    marker = path / MARKER
    return path.resolve() == DEFAULT_OUTPUT or (marker.is_file() and not marker.is_symlink())


def generated_directory(path: Path) -> bool:
    return ID_PATTERN.fullmatch(path.name) is not None and archive_root(path.parent)


def generated_file(path: Path) -> bool:
    return generated_directory(path.parent) or (
        archive_root(path.parent) and path.name in {MANIFEST, JOURNAL, LOCK, MARKER}
    )


def probe_directory(path: Path) -> bool:
    marker = path / PROBE_MARKER
    return marker.is_file() and not marker.is_symlink()


def probe_file(path: Path) -> bool:
    return probe_directory(path.parent)
