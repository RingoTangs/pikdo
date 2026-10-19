import asyncio
import gzip
import json
from pathlib import Path

import httpx
import pytest

import tooling
from xhs.archive_paths import JOURNAL, MANIFEST, MARKER, MARKER_BODY, STAGE
from xhs.client import XhsClient
from xhs.example_collector import Report, collect, group_sources, main, read_entries
from xhs.examples_archive import ArchiveError, sha256

ID = "6ab10b35d528ea0015dd1b7e"
OTHER = "6ab10b35d528ea0015dd1b7d"
SHORT = "https://xhslink.com/m/SameCase"


def raw_body(
    item_id: str = ID, title: str = "原始中文商品", product_id: str | None = None
) -> bytes:
    # Intentionally noncanonical JSON: CRLF, unusual spaces, key order, exact decimal text.
    return (
        '{  "success" : true,\r\n "data" : { "template_data" : [{'
        f'"descriptionMain":{{"name":{json.dumps(title, ensure_ascii=False)},"itemId":"{product_id or item_id}"}},'
        f'"descriptionH5":{{"skuId":"{item_id}"}},'
        '"sellerH5":{"name":"测试店铺"},'
        '"priceH5":{"highlightPrice":19.9900,"dealPrice":{"price":9.99000000000000000000001},"itemAnalysisDataText":"已售2.3万+"},'
        '"private":{"xsec_token":"FAKE_TOKEN","cookie":"FAKE_COOKIE"},"ordered":[3,1,2]}]}}\r\n'
    ).encode()


def write_input(path: Path, values: list[str]) -> Path:
    path.write_text(
        "\n".join(f"# {i}\n\n```text\n{value}\n```\n" for i, value in enumerate(values, 1))
    )
    return path


def handler_for(calls: list[httpx.Request], body: bytes | None = None):
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.url.host in {"xhslink.com", "www.xhslink.com"}:
            return httpx.Response(
                302,
                headers={
                    "Location": f"https://www.xiaohongshu.com/goods-detail/{ID}?xsec_token=FAKE_TOKEN"
                },
            )
        assert request.url.host == "mall.xiaohongshu.com"
        assert request.url.params["item_id"] in {ID, OTHER}
        return httpx.Response(
            200, content=body if body is not None else raw_body(request.url.params["item_id"])
        )

    return handler


def run(input_path: Path, output: Path, handler, **options) -> Report:
    return asyncio.run(
        collect(input_path, output, transport=httpx.MockTransport(handler), interval=0, **options)
    )


def detail_calls(calls: list[httpx.Request]) -> int:
    return sum(request.url.host == "mall.xiaohongshu.com" for request in calls)


def read_manifest(output: Path) -> dict:
    return json.loads((output / MANIFEST).read_text())


def test_real_input_has_six_entries_and_five_sources() -> None:
    path = Path(__file__).parents[1] / "examples/share_text.md"
    before = path.read_bytes()
    entries = read_entries(path)
    report = Report()
    groups = group_sources(entries, report)
    assert len(entries) == 6 and len(groups) == 5
    assert groups[2].entry_numbers == [3, 4]
    assert entries[1].text.startswith(" ")
    assert path.read_bytes() == before


def test_raw_bytes_and_two_level_deduplication(tmp_path) -> None:
    path = write_input(
        tmp_path / "share_text.md",
        [
            f"分享 {SHORT}?xsec_token=FAKE_TOKEN",
            SHORT + "#fragment",
            "https://xhslink.com/m/Another",
        ],
    )
    output = tmp_path / "archive"
    calls = []
    body = raw_body()
    result = run(path, output, handler_for(calls, body))
    assert result.exit_code == 0 and result.sources == 2 and result.fetched == 1
    assert len(calls) == 3 and detail_calls(calls) == 1
    assert (output / ID / "response.json").read_bytes() == body
    manifest = read_manifest(output)
    assert len(manifest["sources"]) == 2
    assert manifest["products"][ID]["response_sha256"] == sha256(body)
    assert list(manifest["sources"].values())[0]["entry_numbers"] == [1, 2]
    assert "FAKE_TOKEN" not in (output / MANIFEST).read_text()
    index = (output / ID / "index.md").read_text()
    assert "原始中文商品" in index and "测试店铺" in index
    assert r"share\_text\.md" in index and "1, 2" in index and "response.json" in index
    assert "FAKE_TOKEN" not in index and "xsec_token" not in index
    assert sha256(body) in index
    assert not (output / JOURNAL).exists()


def test_cache_and_incremental_new_source_for_same_product(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT])
    output = tmp_path / "archive"
    calls = []
    handler = handler_for(calls)
    assert run(path, output, handler).exit_code == 0
    original = (output / ID / "response.json").read_bytes()
    calls.clear()
    assert run(path, output, handler).cached == 1
    assert calls == []
    write_input(path, [SHORT, "https://xhslink.com/m/NewSource"])
    result = run(path, output, handler)
    assert result.exit_code == 0 and len(calls) == 1 and detail_calls(calls) == 0
    assert (output / ID / "response.json").read_bytes() == original
    assert len(read_manifest(output)["sources"]) == 2
    assert "NewSource" in (output / ID / "index.md").read_text()


def test_refresh_success_and_no_repeat_with_multiple_sources(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT, "https://xhslink.com/m/Another"])
    output = tmp_path / "archive"
    calls = []
    run(path, output, handler_for(calls))
    calls.clear()
    new_body = raw_body(title="刷新后商品")
    result = run(path, output, handler_for(calls, new_body), refresh=True)
    assert result.exit_code == 0 and detail_calls(calls) == 1 and len(calls) == 1
    assert (output / ID / "response.json").read_bytes() == new_body
    assert "刷新后商品" in (output / ID / "index.md").read_text()


@pytest.mark.parametrize("kind", ["timeout", "json", "business", "identity", "restricted"])
def test_refresh_failure_preserves_old_files(tmp_path, kind) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    run(path, output, handler_for([]))
    before = {
        name: (output / name).read_bytes()
        for name in (f"{ID}/response.json", f"{ID}/index.md", MANIFEST)
    }

    def handler(request):
        if kind == "timeout":
            raise httpx.ReadTimeout("FAKE_TOKEN", request=request)
        body = {
            "json": b"{invalid",
            "business": b'{"success":false}',
            "identity": raw_body(OTHER),
        }.get(kind, b"{}")
        return httpx.Response(403 if kind == "restricted" else 200, content=body)

    report = run(path, output, handler, refresh=True)
    assert report.exit_code == 1
    assert {name: (output / name).read_bytes() for name in before} == before


@pytest.mark.parametrize(
    "body,code",
    [
        (b"{invalid", "UPSTREAM_INVALID_JSON"),
        (b'{"success":false}', "UPSTREAM_BUSINESS_ERROR"),
        (raw_body(OTHER), "UPSTREAM_IDENTITY_MISMATCH"),
        (
            b'{"success":true,"data":{"template_data":[{"descriptionMain":{"name":"x"}}]}}',
            "UPSTREAM_IDENTITY_MISSING",
        ),
    ],
)
def test_invalid_responses_not_archived(tmp_path, body, code) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    result = run(path, output, handler_for([], body))
    assert result.errors[0]["code"] == code
    assert not (output / ID).exists() and not (output / MANIFEST).exists()


def test_item_and_sku_ids_are_distinct_and_single_http_request(tmp_path) -> None:
    async def exercise():
        calls = []
        body = raw_body(product_id=OTHER)
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler_for(calls, body))
        ) as http:
            result = await XhsClient(http).fetch_product_result(ID)
        assert len(calls) == 1
        assert result.raw_body == body
        assert result.product.basic_info.product_item_id == OTHER
        assert result.product.basic_info.sku_id == ID
        assert "FAKE_TOKEN" not in repr(result)

    asyncio.run(exercise())


def test_content_is_decompressed_bytes_not_gzip_transfer(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    body = raw_body()
    output = tmp_path / "archive"
    result = run(
        path,
        output,
        lambda request: httpx.Response(
            200, content=gzip.compress(body), headers={"Content-Encoding": "gzip"}
        ),
    )
    assert result.exit_code == 0
    assert (output / ID / "response.json").read_bytes() == body


def test_timeout_continues_but_restrictions_stop_later_requests(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [ID, OTHER])
    calls = []

    def handler(request):
        calls.append(request)
        if request.url.params["item_id"] == ID:
            raise httpx.ReadTimeout("timeout", request=request)
        return httpx.Response(200, content=raw_body(OTHER))

    report = run(path, tmp_path / "normal", handler)
    assert report.exit_code == 1 and report.fetched == 1 and len(calls) == 2
    calls.clear()

    def restricted(request):
        calls.append(request)
        return httpx.Response(461)

    report = run(path, tmp_path / "restricted", restricted)
    assert report.stopped and len(calls) == 1


def test_short_link_access_restriction_stops_batch(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT, ID])
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(429)

    assert run(path, tmp_path / "archive", handler).stopped
    assert len(calls) == 1


def test_dry_run_zero_network_zero_writes(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT, SHORT, ID])
    before = {p: p.read_bytes() for p in tmp_path.rglob("*") if p.is_file()}

    def forbidden(request):
        pytest.fail("dry-run must never send a request")

    report = run(path, tmp_path / "archive", forbidden, dry_run=True)
    assert report.entries == 3 and report.sources == 2 and report.pending_sources == 2
    assert report.unknown_short_targets == 1 and not (tmp_path / "archive").exists()
    assert {p: p.read_bytes() for p in tmp_path.rglob("*") if p.is_file()} == before


def test_limit_counts_distinct_sources(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT, SHORT, ID, OTHER])
    output = tmp_path / "archive"
    calls = []
    result = run(path, output, handler_for(calls), limit=2)
    assert result.selected == 2 and result.sources == 3
    assert detail_calls(calls) == 1
    assert len(read_manifest(output)["sources"]) == 2
    assert not (output / OTHER).exists()


def test_empty_blocks_and_multiline_input(tmp_path) -> None:
    path = tmp_path / "share_text.md"
    path.write_text(
        f"Intro\n# 1\n```text\n  \n```\nDescription\n# 2\n```text\n分享 😆\n {SHORT}\n 点击查看\n```\n"
    )
    report = run(path, tmp_path / "archive", handler_for([]))
    assert report.entries == 2 and report.fetched == 1 and report.errors[0]["entry_numbers"] == [1]


@pytest.mark.parametrize(
    "content", ["{invalid", '{"schema_version":2}', '{"schema_version":1,"schema_version":1}']
)
def test_corrupt_manifest_not_overwritten(tmp_path, content) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    run(path, output, handler_for([]))
    manifest = output / MANIFEST
    manifest.write_text(content)
    before = manifest.read_bytes()
    with pytest.raises(ArchiveError):
        run(path, output, lambda request: pytest.fail("bad manifest must fail before network"))
    assert manifest.read_bytes() == before


@pytest.mark.parametrize("missing", [False, True])
def test_bad_cache_requires_refresh(tmp_path, missing) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    run(path, output, handler_for([]))
    raw = output / ID / "response.json"
    if missing:
        raw.unlink()
    else:
        raw.write_bytes(b"corrupt")
    calls = []
    result = run(path, output, handler_for(calls))
    assert result.errors[0]["code"] == ("CACHE_MISSING" if missing else "CACHE_CORRUPT")
    assert calls == []
    assert run(path, output, handler_for(calls), refresh=True).exit_code == 0
    assert raw.read_bytes() == raw_body()


@pytest.mark.parametrize("failure", ["index.md", MANIFEST, STAGE])
def test_interrupted_commit_can_recover(tmp_path, monkeypatch, failure) -> None:
    import xhs.examples_archive as archive

    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    original = archive.atomic_write

    def fail(target, body):
        if target.name == failure:
            raise OSError("simulated interruption")
        original(target, body)

    monkeypatch.setattr(archive, "atomic_write", fail)
    with pytest.raises(OSError):
        run(path, output, handler_for([]))
    assert (output / JOURNAL).exists()
    monkeypatch.setattr(archive, "atomic_write", original)
    calls = []
    result = run(path, output, handler_for(calls))
    assert result.exit_code == 0
    assert detail_calls(calls) == (1 if failure == STAGE else 0)
    assert (output / ID / "response.json").read_bytes() == raw_body()
    assert not (output / JOURNAL).exists()


def test_index_regeneration_and_sensitive_content_filtering(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT + "?xsec_token=FAKE_TOKEN"])
    output = tmp_path / "archive"
    body = raw_body(
        title="商品\n<script>injection</script> [x](https://evil.test/?xsec_token=FAKE_TOKEN)"
    )
    assert run(path, output, handler_for([], body)).exit_code == 0
    index = output / ID / "index.md"
    text = index.read_text()
    assert "FAKE_TOKEN" not in text and "<script>" not in text
    assert (output / ID / "response.json").read_bytes() == body
    index.unlink()
    assert run(path, output, lambda request: pytest.fail("cache must not request")).exit_code == 0
    assert index.read_text() == text


def test_formatting_never_changes_generated_raw_bytes(tmp_path) -> None:
    root = tmp_path / "collector"
    root.mkdir()
    output = root / "examples"
    output.mkdir()
    (output / MARKER).write_bytes(MARKER_BODY)
    path = write_input(tmp_path / "share_text.md", [ID])
    run(path, output, handler_for([]))
    response = output / ID / "response.json"
    before = response.read_bytes()
    assert response not in tooling.discover_files(root)[".json"]
    assert tooling.format_json(response, check=False)
    assert tooling.run_checks(check=False, root=root) == 0
    assert response.read_bytes() == before


def test_unknown_directory_and_symlink_protection(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    output.mkdir()
    (output / MARKER).write_bytes(MARKER_BODY)
    directory = output / ID
    directory.mkdir()
    unrelated = directory / "response.json"
    unrelated.write_bytes(b"unrelated")
    assert run(path, output, handler_for([]), refresh=True).errors[0]["code"] == "OUTPUT_CONFLICT"
    assert unrelated.read_bytes() == b"unrelated"
    unrelated.unlink()
    unrelated.symlink_to(path)
    assert run(path, output, handler_for([])).errors[0]["code"] == "UNSAFE_OUTPUT_PATH"
    assert path.is_file()


def test_nonempty_custom_output_is_rejected(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    output.mkdir()
    (output / "notes.md").write_text("unrelated")
    with pytest.raises(ArchiveError):
        run(path, output, lambda request: pytest.fail("must not request"))
    assert (output / "notes.md").read_text() == "unrelated"


def test_cli_dry_run_and_help(tmp_path, capsys) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT, SHORT])
    output = tmp_path / "archive"
    assert main(["--input", str(path), "--output", str(output), "--dry-run"]) == 0
    assert "不同来源 1" in capsys.readouterr().out
    assert not output.exists()
    with pytest.raises(SystemExit) as exc:
        main(["--help"])
    assert exc.value.code == 0


def test_request_interval_applies_to_short_and_detail_requests(tmp_path, monkeypatch) -> None:
    delays = []

    async def fake_sleep(delay):
        delays.append(delay)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    path = write_input(tmp_path / "share_text.md", [SHORT, "https://xhslink.com/m/Another"])
    calls = []
    result = asyncio.run(
        collect(
            path,
            tmp_path / "archive",
            interval=2,
            transport=httpx.MockTransport(handler_for(calls)),
        )
    )
    assert result.exit_code == 0
    assert len(calls) == 3 and len(delays) == 2
    assert all(0 < delay <= 2 for delay in delays)


def test_duplicate_product_failure_is_not_requested_twice(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [SHORT, "https://xhslink.com/m/Another"])
    calls = []
    base = handler_for(calls)

    def handler(request):
        if request.url.host == "mall.xiaohongshu.com":
            calls.append(request)
            raise httpx.ReadTimeout("failed", request=request)
        return base(request)

    result = run(path, tmp_path / "archive", handler)
    assert len(result.errors) == 2 and detail_calls(calls) == 1


def test_pending_dry_run_does_not_recover_or_write(tmp_path, monkeypatch) -> None:
    import xhs.examples_archive as archive

    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    original = archive.atomic_write

    def fail(target, body):
        if target.name == MANIFEST:
            raise OSError("interruption")
        original(target, body)

    monkeypatch.setattr(archive, "atomic_write", fail)
    with pytest.raises(OSError):
        run(path, output, handler_for([]))
    monkeypatch.setattr(archive, "atomic_write", original)
    before = {p: p.read_bytes() for p in output.rglob("*") if p.is_file()}
    report = run(path, output, lambda request: pytest.fail("dry-run network"), dry_run=True)
    assert any(error["code"] == "RECOVERY_PENDING" for error in report.errors)
    assert {p: p.read_bytes() for p in output.rglob("*") if p.is_file()} == before


def test_refresh_commit_interruption_recovers_new_snapshot(tmp_path, monkeypatch) -> None:
    import xhs.examples_archive as archive

    path = write_input(tmp_path / "share_text.md", [ID])
    output = tmp_path / "archive"
    run(path, output, handler_for([]))
    original = archive.atomic_write

    def fail(target, body):
        if target.name == MANIFEST:
            raise OSError("interruption")
        original(target, body)

    monkeypatch.setattr(archive, "atomic_write", fail)
    body = raw_body(title="New snapshot")
    with pytest.raises(OSError):
        run(path, output, handler_for([], body), refresh=True)
    monkeypatch.setattr(archive, "atomic_write", original)
    assert (
        run(path, output, lambda request: pytest.fail("must recover without network")).exit_code
        == 0
    )
    assert (output / ID / "response.json").read_bytes() == body
    assert read_manifest(output)["products"][ID]["response_sha256"] == sha256(body)


def test_sources_keep_numbers_from_multiple_input_files(tmp_path) -> None:
    first = write_input(tmp_path / "first.md", [ID])
    second = write_input(tmp_path / "second.md", [ID, ID])
    output = tmp_path / "archive"
    run(first, output, handler_for([]))
    assert run(second, output, lambda request: pytest.fail("cached")).exit_code == 0
    source = next(iter(read_manifest(output)["sources"].values()))
    assert source["entry_sources"] == {"first.md": [1], "second.md": [1, 2]}
    index = (output / ID / "index.md").read_text()
    assert r"first\.md" in index and r"second\.md" in index


def test_nonfinite_json_is_not_archived(tmp_path) -> None:
    path = write_input(tmp_path / "share_text.md", [ID])
    body = raw_body().replace(b'"ordered":[3,1,2]', b'"ordered":[NaN]')
    result = run(path, tmp_path / "archive", handler_for([], body))
    assert result.errors[0]["code"] == "UPSTREAM_INVALID_JSON"


def test_missing_heading_and_unclosed_fence_are_reported(tmp_path) -> None:
    path = tmp_path / "share_text.md"
    path.write_text(f"```text\n{ID}\n```\n# 2\n```text\n{ID}\n")
    entries = read_entries(path)
    assert [entry.error for entry in entries] == ["MISSING_ENTRY_NUMBER", "UNCLOSED_TEXT_BLOCK"]


def test_archive_lock_rejects_concurrent_writer(tmp_path) -> None:
    from xhs.examples_archive import ArchiveStore

    first = ArchiveStore(tmp_path / "archive")
    with first.locked():
        second = ArchiveStore(tmp_path / "archive")
        with pytest.raises(ArchiveError, match="另一个进程"):
            with second.locked():
                pytest.fail("concurrent writer acquired lock")
