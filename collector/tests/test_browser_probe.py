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
        option in text
        for option in (
            "--input",
            "--output",
            "--headed",
            "--channel",
            "--timeout",
            "--error-output",
        )
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


def test_real_probe_core_preserves_461_over_detach_timeout(monkeypatch, capsys, caplog, tmp_path):
    import asyncio
    import logging

    from test_browser_collector import FakePlaywright

    from xhs import browser_collector

    pw = FakePlaywright()
    pw.page.responses[0].status = 461

    async def blocked():
        await asyncio.Event().wait()

    pw.cdp.detach = blocked
    monkeypatch.setattr(browser_collector, "async_playwright", lambda: pw)
    real_collector = browser_collector.BrowserProductCollector

    def collector(**options):
        return real_collector(cleanup_timeout=0.01, **options)

    monkeypatch.setattr(module, "BrowserProductCollector", collector)
    caplog.set_level(logging.INFO, logger="xhs.browser")
    output = tmp_path / "probe"
    code = module.main(
        ["--input", "https://xhslink.com/m/test?token=SECRET", "--output", str(output)]
    )
    captured = capsys.readouterr()
    assert code == 1
    assert "商品响应 HTTP 状态：461" in captured.out
    assert "结果：BROWSER_ACCESS_RESTRICTED" in captured.out
    assert "结果：SUCCESS" not in captured.out and not output.exists()
    assert "stage=cdp_detach outcome=timeout" in caplog.text
    assert "SECRET" not in captured.out + captured.err + caplog.text
    assert pw.browser.closed and pw.context.closed and pw.stopped


def error_evidence(status="captured"):
    from xhs.upstream_diagnostics import extract_upstream_error

    body = b' { "success" : false, "code":123, "msg":"Cookie=SECRET xsec_token=SECRET Authorization=SECRET x-s=SECRET x-t=SECRET" }\n'
    return extract_upstream_error(
        body if status == "captured" else None,
        461,
        source="product_response",
        collection_id="a" * 32,
        capture_status=status,
    )


def test_error_output_bytes_metadata_permissions_and_success_output_coexist(
    collector, tmp_path, capsys
):
    import json
    import stat

    diagnostic = error_evidence()
    collector.error = BrowserCollectionError(
        "BROWSER_ACCESS_RESTRICTED", "safe", upstream_diagnostic=diagnostic
    )
    output = tmp_path / "probe"
    module.write_output(output, b"existing success response")
    assert module.main(["--input", ID, "--output", str(output), "--error-output", str(output)]) == 1
    directory = output / ("error-" + "a" * 32)
    assert (directory / "upstream-error.bin").read_bytes() == diagnostic.raw_body
    metadata = json.loads((directory / "diagnostic.json").read_bytes())
    assert metadata["body_sha256"] == hashlib.sha256(diagnostic.raw_body).hexdigest()
    assert metadata["body_size"] == len(diagnostic.raw_body)
    assert metadata["collector_error"] == "BROWSER_ACCESS_RESTRICTED"
    assert "SECRET" not in json.dumps(metadata)
    assert (output / "response.json").read_bytes() == b"existing success response"
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700
    for filename in ("upstream-error.bin", "diagnostic.json"):
        assert stat.S_IMODE((directory / filename).stat().st_mode) == 0o600
    captured = capsys.readouterr()
    assert "SECRET" not in captured.out + captured.err
    assert "结果：BROWSER_ACCESS_RESTRICTED" in captured.out
    assert not list(output.glob(".browser-probe-error-*"))


@pytest.mark.parametrize(
    "status", ["timeout", "read_error", "too_large", "skipped_unknown_length", "unavailable"]
)
def test_uncaptured_error_exports_metadata_only(tmp_path, status):
    import json

    output = tmp_path / "probe"
    module.write_error_output(output, error_evidence(status), "BROWSER_ACCESS_RESTRICTED")
    directory = output / ("error-" + "a" * 32)
    assert not (directory / "upstream-error.bin").exists()
    metadata = json.loads((directory / "diagnostic.json").read_bytes())
    assert metadata["capture_status"] == status
    assert metadata["body_sha256"] is metadata["body_size"] is None


def test_default_failure_does_not_export_and_success_does_not_export_errors(
    collector, tmp_path, capsys
):
    collector.error = BrowserCollectionError(
        "BROWSER_ACCESS_RESTRICTED", "safe", upstream_diagnostic=error_evidence()
    )
    output = tmp_path / "probe"
    assert module.main(["--input", ID, "--output", str(output)]) == 1
    assert not output.exists()
    collector.error = None
    assert module.main(["--input", ID, "--error-output", str(output)]) == 0
    assert not output.exists()


@pytest.mark.parametrize(
    "target", ["root", "destination", "upstream-error.bin", "diagnostic.json", PROBE_MARKER]
)
def test_error_export_rejects_symlink_targets(tmp_path, target):
    root = tmp_path / "probe"
    real = tmp_path / "real"
    real.mkdir()
    if target == "root":
        root.symlink_to(real, target_is_directory=True)
    else:
        root.mkdir()
        destination = root / ("error-" + "a" * 32)
        if target == "destination":
            destination.symlink_to(real, target_is_directory=True)
        else:
            destination.mkdir()
            (destination / target).symlink_to(tmp_path / "victim")
    with pytest.raises(BrowserCollectionError):
        module.write_error_output(root, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    assert list(real.iterdir()) == []
    assert not (tmp_path / "victim").exists()


def test_error_export_checks_actual_git_ignored_filenames(tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / ".gitignore").write_text("/probe/response.json\n/probe/.pikdo-browser-probe\n")
    output = tmp_path / "probe"
    module.checked_output(str(output))
    with pytest.raises(BrowserCollectionError):
        module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    assert not output.exists()
    (tmp_path / ".gitignore").write_text("/probe/\n")
    module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    for path in output.rglob("*"):
        assert (
            subprocess.run(["git", "check-ignore", "-q", str(path)], cwd=tmp_path).returncode == 0
        )


def test_error_export_atomic_publish_failure_has_no_partial_bundle(tmp_path, monkeypatch):
    output = tmp_path / "probe"
    module.write_output(output, b"old response")

    def fail(*args, **kwargs):
        raise OSError("SECRET filesystem failure")

    monkeypatch.setattr(module.os, "rename", fail)
    with pytest.raises(OSError):
        module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    assert (output / "response.json").read_bytes() == b"old response"
    assert not list(output.glob("error-*")) and not list(output.glob(".browser-probe-error-*"))


def test_error_export_does_not_overwrite_previous_bundle(tmp_path):
    output = tmp_path / "probe"
    module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    body = output / ("error-" + "a" * 32) / "upstream-error.bin"
    expected = body.read_bytes()
    with pytest.raises(BrowserCollectionError):
        module.write_error_output(output, error_evidence("timeout"), "BROWSER_ACCESS_RESTRICTED")
    assert body.read_bytes() == expected
    assert not list(output.glob(".browser-probe-error-*"))


def test_error_export_failure_keeps_primary_cli_error(collector, tmp_path, monkeypatch, capsys):
    collector.error = BrowserCollectionError(
        "BROWSER_ACCESS_RESTRICTED", "safe", upstream_diagnostic=error_evidence()
    )

    def fail(*args):
        raise OSError("SECRET")

    monkeypatch.setattr(module, "write_error_output", fail)
    assert module.main(["--input", ID, "--error-output", str(tmp_path / "probe")]) == 1
    captured = capsys.readouterr()
    assert "结果：BROWSER_ACCESS_RESTRICTED" in captured.out
    assert "诊断导出：BROWSER_OUTPUT_IO_ERROR" in captured.out
    assert "SECRET" not in captured.out + captured.err


def test_error_raw_files_are_protected_from_tooling(tmp_path):
    output = tmp_path / "probe"
    module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    directory = output / ("error-" + "a" * 32)
    before = {path: path.read_bytes() for path in directory.iterdir()}
    for path in directory.iterdir():
        assert tooling.format_json(path, check=False)
    assert all(path.read_bytes() == body for path, body in before.items())


def test_error_export_partial_write_failure_does_not_publish_pair(tmp_path, monkeypatch):
    output = tmp_path / "probe"
    original_fsync = module.os.fsync
    calls = 0

    def fail_during_second_file(descriptor):
        nonlocal calls
        calls += 1
        if calls == 3:  # marker, metadata, then raw body
            raise OSError("SECRET partial write")
        return original_fsync(descriptor)

    monkeypatch.setattr(module.os, "fsync", fail_during_second_file)
    with pytest.raises(OSError):
        module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    assert sorted(p.name for p in output.iterdir()) == [PROBE_MARKER]


def test_error_export_rejects_original_and_historical_data(tmp_path):
    for output in (EXAMPLES_DIR, EXAMPLES_DIR / "nested"):
        with pytest.raises(BrowserCollectionError):
            module.write_error_output(output, error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    root = tmp_path / "archive"
    root.mkdir()
    (root / ".pikdo-example-archive").write_bytes(b"historical")
    with pytest.raises(BrowserCollectionError):
        module.write_error_output(root / "nested", error_evidence(), "BROWSER_ACCESS_RESTRICTED")
    assert sorted(p.name for p in root.iterdir()) == [".pikdo-example-archive"]
