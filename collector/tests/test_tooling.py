from decimal import Decimal
from pathlib import Path

import pytest
import simplejson

import tooling


def snapshot(root: Path) -> dict[str, bytes]:
    return {
        str(p.relative_to(root)): p.read_bytes()
        for p in root.rglob("*")
        if p.is_file() and not p.is_symlink()
    }


def test_check_is_read_only_then_format_is_idempotent(tmp_path):
    (tmp_path / "sample.py").write_text("def greet( name ):\n return 'hi '+name\n")
    (tmp_path / "README.md").write_text("# Title\n\n-   Item\n")
    (tmp_path / "data.json").write_text(
        '{"n":1.2345678901234567890123456789,"items":[2,1],"中文":"值"}'
    )
    before = snapshot(tmp_path)
    assert tooling.run_checks(check=True, root=tmp_path) == 1
    # Ruff may create its own cache, but never changes these inputs during lint.
    for name, content in before.items():
        assert (tmp_path / name).read_bytes() == content
    assert tooling.run_checks(check=False, root=tmp_path) == 0
    assert (tmp_path / "sample.py").read_bytes() != before["sample.py"]
    assert (tmp_path / "README.md").read_bytes() != before["README.md"]
    data = (tmp_path / "data.json").read_text()
    assert "1.2345678901234567890123456789" in data
    assert "中文" in data and data.endswith("\n")
    value = simplejson.loads(data, use_decimal=True)
    assert list(value) == ["n", "items", "中文"]
    assert value["n"] == Decimal("1.2345678901234567890123456789")
    assert value["items"] == [2, 1]
    formatted = {name: (tmp_path / name).read_bytes() for name in before}
    assert tooling.run_checks(check=False, root=tmp_path) == 0
    assert tooling.run_checks(check=True, root=tmp_path) == 0
    assert {name: (tmp_path / name).read_bytes() for name in before} == formatted


@pytest.mark.parametrize(
    "directory",
    [
        "examples",
        ".venv",
        ".cache",
        "build",
        "dist",
        "node_modules",
        "__pycache__",
        "venv",
        "env",
        "collector.egg-info",
    ],
)
def test_excluded_directories_are_not_read_or_changed(tmp_path, directory):
    protected = tmp_path / directory
    protected.mkdir()
    for name in ("bad.py", "bad.md", "bad.json"):
        (protected / name).write_text("not valid content")
    before = snapshot(protected)
    assert all(not paths for paths in tooling.discover_files(tmp_path).values())
    assert tooling.run_checks(check=False, root=tmp_path) == 0
    assert snapshot(protected) == before


def test_symlinks_and_files_outside_collector_are_excluded(tmp_path):
    root = tmp_path / "collector"
    root.mkdir()
    outside = tmp_path / "original.json"
    outside.write_text("original not JSON")
    (root / "linked.json").symlink_to(outside)
    (root / "linked_directory").symlink_to(tmp_path, target_is_directory=True)
    assert tooling.discover_files(root) == {".py": [], ".md": [], ".json": []}
    assert tooling.run_checks(check=False, root=root) == 0
    assert outside.read_text() == "original not JSON"


@pytest.mark.parametrize(
    "content", ["{bad", '{"a":1,"a":2}', '{"n":NaN}', '{"n":Infinity}', '{"n":-Infinity}']
)
def test_invalid_json_fails_without_overwriting(tmp_path, content):
    path = tmp_path / "bad.json"
    path.write_text(content)
    before = path.read_bytes()
    assert not tooling.format_json(path, check=True)
    assert not tooling.format_json(path, check=False)
    assert path.read_bytes() == before
    assert tooling.run_checks(check=False, root=tmp_path) == 1


def test_unfixed_code_problems_return_failure(tmp_path):
    (tmp_path / "bad.py").write_text("result = undefined_name\n")
    assert tooling.run_checks(check=False, root=tmp_path) == 1


def test_tool_execution_failure_is_reported(tmp_path, monkeypatch):
    def fail(*args, **kwargs):
        raise OSError("Cannot start tool")

    monkeypatch.setattr(tooling.subprocess, "run", fail)
    assert tooling.run_tool("ruff", ["check"], tmp_path) is False
