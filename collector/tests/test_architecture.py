"""Guard the Playwright-only boundary and immutable reference data."""

import ast
import hashlib
import importlib.metadata
import importlib.util
import re
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).parents[1]


def test_removed_modules_and_cli_are_not_available():
    for module in ("client", "resolver", "example_collector", "examples_archive", "archive_paths"):
        assert importlib.util.find_spec(f"xhs.{module}") is None
    scripts = tomllib.loads((ROOT / "pyproject.toml").read_text())["project"]["scripts"]
    assert set(scripts) == {"dev", "test", "format", "lint", "browser-probe"}
    entrypoints = importlib.metadata.distribution("collector").entry_points
    assert not any(entry.name == "collect-examples" for entry in entrypoints)


def test_production_code_has_no_httpx_or_retired_module_imports():
    forbidden = {
        "httpx",
        "requests",
        "xhs.client",
        "xhs.resolver",
        "xhs.example_collector",
        "xhs.examples_archive",
        "xhs.archive_paths",
    }
    for path in (ROOT / "src").rglob("*.py"):
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
                assert all(alias.name != "APIRequestContext" for alias in node.names)
            else:
                continue
            assert not any(
                name == banned or name.startswith(banned + ".")
                for name in names
                for banned in forbidden
            ), path


def test_httpx_is_only_a_development_dependency():
    config = tomllib.loads((ROOT / "pyproject.toml").read_text())

    def names(deps):
        return {re.split(r"[<>=!~\[]", dep)[0] for dep in deps}

    assert "httpx" not in names(config["project"]["dependencies"])
    assert "httpx" in names(config["dependency-groups"]["dev"])
    assert "selectolax" not in names(config["project"]["dependencies"])


def test_original_reference_bytes_are_unchanged():
    expected = {
        "response.json": "133588b3e1038fdf1ffa001f614240a5b539d7463c798046bc82660ec083cbb3",
        "share_text.md": "b1af9de06412fcb33aaf250a0902518047095efd95b734496e262919e81c81ce",
        "README.md": "074c47318ff3c562d413d8dc2ac5e8f9a8d03d211fc26bfeaa137e7543b4ce41",
    }
    for filename, digest in expected.items():
        assert hashlib.sha256((ROOT / "examples" / filename).read_bytes()).hexdigest() == digest


def test_historical_data_and_probe_outputs_stay_git_ignored():
    for path in (
        "examples/" + "a" * 24 + "/response.json",
        "examples/manifest.json",
        "examples/.collect-examples.pending.json",
        "examples/.collect-examples.lock",
        "examples/.pikdo-example-archive",
        ".browser-probe/response.json",
    ):
        result = subprocess.run(
            ["git", "check-ignore", "--quiet", "--", path],
            cwd=ROOT,
            check=False,
            capture_output=True,
        )
        assert result.returncode == 0, path
