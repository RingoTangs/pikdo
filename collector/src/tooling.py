"""Collector checks and formatting; reference samples and raw data stay read-only."""

import os
import subprocess
import sys
from pathlib import Path

import simplejson

from xhs.probe_paths import protected_data

COLLECTOR_ROOT = Path(__file__).resolve().parents[1]
RUFF_CONFIG = COLLECTOR_ROOT / "pyproject.toml"
EXCLUDED_DIRS = {
    "build",
    "dist",
    "node_modules",
    "__pycache__",
    "venv",
    "env",
}


def discover_files(root: Path) -> dict[str, list[Path]]:
    """Share a single scope across tools, without traversing hidden or linked directories."""
    files: dict[str, list[Path]] = {".py": [], ".md": [], ".json": []}
    for current, directories, names in os.walk(root, followlinks=False):
        directories[:] = sorted(
            name
            for name in directories
            if name not in EXCLUDED_DIRS
            and not name.startswith(".")
            and not name.endswith((".egg-info", ".dist-info"))
            and not (Path(current) / name).is_symlink()
            and not protected_data(Path(current) / name, root=root)
        )
        for name in sorted(names):
            path = Path(current) / name
            if (
                path.suffix in files
                and not name.startswith(".")
                and not path.is_symlink()
                and path.is_file()
                and not protected_data(path, root=root)
            ):
                files[path.suffix].append(path)
    return files


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def format_json(path: Path, *, check: bool, root: Path = COLLECTOR_ROOT) -> bool:
    """Preserve JSON types, decimal precision, and ordering. Never rewrite invalid input."""
    if path.name.startswith(".") or any(parent.is_symlink() for parent in (path, *path.parents)):
        return True
    if protected_data(path, root=root):
        return True
    try:
        original = path.read_bytes().decode("utf-8")
        value = simplejson.loads(
            original,
            use_decimal=True,
            allow_nan=False,
            object_pairs_hook=unique_object,
        )
        formatted = (
            simplejson.dumps(
                value,
                use_decimal=True,
                ensure_ascii=False,
                indent=4,
                sort_keys=False,
                allow_nan=False,
            )
            + "\n"
        )
        if original != formatted:
            if check:
                print(f"JSON needs formatting: {path}", flush=True)
                return False
            path.write_bytes(formatted.encode("utf-8"))
            print(f"Formatted JSON: {path}", flush=True)
        return True
    except (ValueError, OSError, RecursionError):
        # Report the path, never raw JSON content or duplicate key values.
        print(f"Invalid or unreadable JSON: {path}", file=sys.stderr, flush=True)
        return False


def run_tool(module: str, arguments: list[str], root: Path) -> bool:
    try:
        result = subprocess.run([sys.executable, "-m", module, *arguments], cwd=root, check=False)
        return result.returncode == 0
    except OSError as exc:
        print(f"Could not run {module}: {exc}", file=sys.stderr, flush=True)
        return False


def run_checks(*, check: bool, root: Path = COLLECTOR_ROOT) -> int:
    files = discover_files(root)
    results = []
    python = [str(path) for path in files[".py"]]
    if python:
        print("Checking Python" if check else "Fixing and formatting Python", flush=True)
        common = ["--config", str(RUFF_CONFIG)]
        results.append(
            run_tool("ruff", ["check", *common, *([] if check else ["--fix"]), *python], root)
        )
        results.append(
            run_tool("ruff", ["format", *common, *(["--check"] if check else []), *python], root)
        )
    else:
        print("Skipping Python: no files", flush=True)
    markdown = [str(path) for path in files[".md"]]
    if markdown:
        print("Checking Markdown" if check else "Formatting Markdown", flush=True)
        results.append(
            run_tool(
                "mdformat",
                [
                    *(["--check"] if check else []),
                    "--wrap",
                    "keep",
                    "--extensions",
                    "gfm",
                    "--no-codeformatters",
                    *markdown,
                ],
                root,
            )
        )
    else:
        print("Skipping Markdown: no files", flush=True)
    if files[".json"]:
        print("Checking JSON" if check else "Formatting JSON", flush=True)
        results.extend(format_json(path, check=check, root=root) for path in files[".json"])
    else:
        print("Skipping JSON: no files", flush=True)
    success = all(results)
    print(
        "All checks passed" if success else "Some files need attention; see results above",
        flush=True,
    )
    return 0 if success else 1


def lint() -> int:
    return run_checks(check=True)


def format() -> int:
    return run_checks(check=False)
