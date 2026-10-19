"""Probe CLI privacy, atomic writes and formatter protection."""

import hashlib
import subprocess
from pathlib import Path

import pytest

import tooling
from xhs import browser_probe as module
from xhs.browser_collector import BrowserCollectionError, BrowserProductResult, parse_browser_body
from xhs.probe_paths import EXAMPLES_DIR, PROBE_MARKER

ID = "6ab10b35d528ea0015dd1b7e"
RAW = (Path(__file__).parents[1] / "examples/response.json").read_bytes()


@pytest.fixture
def collector(monkeypatch):
    class FakeCollector:
        options = None
        raw = None
        error = None

        def __init__(self, **options):
            type(self).options = options
            self.status = options["status"]

        async def collect(self, raw):
            type(self).raw = raw
            self.status.browser_started = True
            self.status.navigation = "product_page"
            self.status.item_id = ID
            self.status.http_status = 200
            if self.error:
                raise self.error
            return BrowserProductResult(ID, RAW, parse_browser_body(RAW, ID), 200)

    monkeypatch.setattr(module, "BrowserProductCollector", FakeCollector)
    return FakeCollector


def test_help(capsys):
    with pytest.raises(SystemExit) as result:
        module.main(["--help"])
    assert result.value.code == 0
    text = capsys.readouterr().out
    assert all(
        option in text for option in ("--input", "--output", "--headed", "--channel", "--timeout")
    )


def test_cli_success_byte_output_and_sensitive_input_not_logged(collector, tmp_path, capsys):
    raw = "【小红书】title https://xhslink.com/m/example?xsec_token=SENTINEL 点击查看"
    output = tmp_path / "probe"
    result = module.main(
        [
            "--input",
            raw,
            "--headed",
            "--channel",
            "chrome",
            "--timeout",
            "5",
            "--output",
            str(output),
        ]
    )
    assert result == 0
    assert (output / "response.json").read_bytes() == RAW
    assert (output / PROBE_MARKER).is_file()
    assert collector.raw == raw
    assert collector.options["headless"] is False
    assert collector.options["channel"] == "chrome"
    assert collector.options["navigation_timeout"] == collector.options["response_timeout"] == 5
    text = capsys.readouterr().out
    assert "浏览器启动：成功" in text and "product_page" in text and "SUCCESS" in text
    assert hashlib.sha256(RAW).hexdigest() in text
    assert str(len(RAW)) in text
    assert all(secret not in text for secret in ("SENTINEL", "xsec_token", "https://", "Cookie"))
    assert not list(output.glob(".browser-probe-*"))


def test_default_no_output_and_direct_id_notice(collector, capsys, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    assert module.main(["--input", ID]) == 0
    assert not list(tmp_path.iterdir())
    assert "没有原始分享上下文" in capsys.readouterr().out
    assert collector.options["navigation_timeout"] == 30
    assert collector.options["response_timeout"] == 15


def test_failed_collection_no_files_and_safe_diagnostic(collector, tmp_path, capsys):
    collector.error = BrowserCollectionError(
        "BROWSER_ACCESS_RESTRICTED", "SENTINEL xsec_token=secret"
    )
    output = tmp_path / "output"
    assert module.main(["--input", ID, "--output", str(output)]) == 1
    assert not output.exists()
    text = capsys.readouterr().out
    assert "BROWSER_ACCESS_RESTRICTED" in text and "SENTINEL" not in text


def test_invalid_input_does_not_launch(collector, capsys):
    assert module.main(["--input", "https://127.0.0.1/?xsec_token=SENTINEL"]) == 1
    assert collector.options is None
    assert "SENTINEL" not in capsys.readouterr().out


@pytest.mark.parametrize("value", ["0", "-1", "nan", "inf", "bad"])
def test_invalid_timeout(value):
    with pytest.raises(SystemExit) as error:
        module.main(["--input", ID, "--timeout", value])
    assert error.value.code == 2


@pytest.mark.parametrize(
    "value", ["xsec_token=secret", "x-s: secret", "Cookie=secret", "https://x.test/?t=secret"]
)
def test_business_labels_cannot_leak_credentials(value):
    assert module.safe_label(value) == "[已隐藏敏感内容]"
    assert module.safe_label("title\n\x1b[31m") == "title  [31m"


def test_examples_and_archive_output_rejected(tmp_path):
    for output in (EXAMPLES_DIR, EXAMPLES_DIR / ID, EXAMPLES_DIR / "subdir"):
        with pytest.raises(BrowserCollectionError):
            module.checked_output(str(output))
    archive = tmp_path / "archive"
    archive.mkdir()
    (archive / ".pikdo-example-archive").write_text("marker")
    with pytest.raises(BrowserCollectionError):
        module.checked_output(str(archive / ID))


def test_symlink_output_rejected(tmp_path):
    real = tmp_path / "real"
    real.mkdir()
    link = tmp_path / "link"
    link.symlink_to(real, target_is_directory=True)
    with pytest.raises(BrowserCollectionError):
        module.checked_output(str(link / "nested"))
    (real / "response.json").symlink_to(tmp_path / "other")
    with pytest.raises(BrowserCollectionError):
        module.write_output(real, RAW)


def test_repository_output_requires_ignore(tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    output = tmp_path / "raw-probe"
    with pytest.raises(BrowserCollectionError):
        module.checked_output(str(output))
    (tmp_path / ".gitignore").write_text("/raw-probe/\n")
    assert module.checked_output(str(output)) == output


def test_failed_atomic_replace_preserves_previous_bytes(tmp_path, monkeypatch):
    output = tmp_path / "probe"
    module.write_output(output, b"old")

    def broken(*args):
        raise OSError("SENTINEL")

    monkeypatch.setattr(module.os, "replace", broken)
    with pytest.raises(OSError):
        module.write_output(output, RAW)
    assert (output / "response.json").read_bytes() == b"old"
    assert not list(output.glob(".browser-probe-*"))


def test_output_io_error_safe_and_nonzero(collector, tmp_path, monkeypatch, capsys):
    def broken(*args):
        raise OSError("SENTINEL")

    monkeypatch.setattr(module, "write_output", broken)
    assert module.main(["--input", ID, "--output", str(tmp_path / "output")]) == 1
    text = capsys.readouterr().out
    assert "BROWSER_OUTPUT_IO_ERROR" in text and "SENTINEL" not in text


def test_formatter_never_reencodes_probe_bytes(tmp_path):
    output = tmp_path / "custom-probe"
    body = b'{ "precision": 1.000000000000000000000001, "order": [3,1,2] }\r\n'
    module.write_output(output, body)
    assert tooling.discover_files(tmp_path)[".json"] == []
    assert tooling.format_json(output / "response.json", check=False)
    assert (output / "response.json").read_bytes() == body
