"""Read-only data boundaries shared by browser-probe and development tools."""

from pathlib import Path

COLLECTOR_ROOT = Path(__file__).resolve().parents[2]
EXAMPLES_DIR = COLLECTOR_ROOT / "examples"
PROBE_MARKER = ".pikdo-browser-probe"
# Recognition only: historical files are never read, recovered, or deleted here.
LEGACY_MARKER = ".pikdo-example-archive"


def legacy_directory(path: Path) -> bool:
    marker = path / LEGACY_MARKER
    return marker.exists() or marker.is_symlink()


def probe_directory(path: Path) -> bool:
    marker = path / PROBE_MARKER
    return marker.exists() or marker.is_symlink()


def protected_data(path: Path, *, root: Path = COLLECTOR_ROOT) -> bool:
    """Skip all samples and any descendant of a marked raw-data directory."""
    resolved = path.resolve()
    if resolved.is_relative_to(EXAMPLES_DIR.resolve()) or resolved.is_relative_to(
        (root / "examples").resolve()
    ):
        return True
    return any(
        legacy_directory(parent) or probe_directory(parent) for parent in (path, *path.parents)
    )
