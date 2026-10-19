"""Collect read-only Markdown inputs into local, byte-preserving product archives."""

import argparse
import asyncio
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

import httpx

from xhs.archive_paths import COLLECTOR_ROOT, DEFAULT_OUTPUT, JOURNAL
from xhs.client import XhsClient
from xhs.errors import CollectionError, ResolveError
from xhs.examples_archive import ArchiveError, ArchiveStore, SourceRecord, sha256
from xhs.resolver import ProductResolver, check_short_redirect_response, parse_input
from xhs.schemas import Product, SourceType


@dataclass
class Entry:
    number: int | None
    text: str = field(repr=False)
    error: str | None = None


@dataclass
class SourceGroup:
    source_hash: str
    source_url: str
    raw_input: str = field(repr=False)
    source_types: list[SourceType]
    entry_numbers: list[int]
    local_item_id: str | None


@dataclass
class Report:
    entries: int = 0
    sources: int = 0
    selected: int = 0
    resolved: int = 0
    fetched: int = 0
    cached: int = 0
    pending_sources: int = 0
    unknown_short_targets: int = 0
    stopped: bool = False
    errors: list[dict] = field(default_factory=list)

    @property
    def exit_code(self) -> int:
        return 1 if self.errors else 0

    def error(self, numbers: list[int], code: str) -> None:
        self.errors.append({"entry_numbers": numbers, "code": code})


def read_entries(path: Path) -> list[Entry]:
    try:
        lines = path.read_bytes().decode("utf-8").splitlines(keepends=True)
    except (OSError, UnicodeError) as exc:
        raise ArchiveError("INPUT_READ_FAILED", "无法读取 UTF-8 分享文案文件") from exc
    entries = []
    number: int | None = None
    fence: str | None = None
    capture = False
    content: list[str] = []
    for line in lines:
        stripped = line.rstrip("\r\n")
        if fence is None:
            heading = re.fullmatch(r"#{1,6}\s+(\d+)\s*#*\s*", stripped)
            if stripped.startswith("#"):
                number = int(heading.group(1)) if heading else None
            opening = re.fullmatch(r" {0,3}(`{3,}|~{3,})([^`\r\n]*)", stripped)
            if opening:
                fence = opening.group(1)
                capture = opening.group(2).strip() == "text"
                content = []
        elif re.fullmatch(rf" {{0,3}}{re.escape(fence[0])}{{{len(fence)},}}\s*", stripped):
            if capture:
                entries.append(
                    Entry(
                        number,
                        "".join(content),
                        "MISSING_ENTRY_NUMBER" if number is None or number <= 0 else None,
                    )
                )
            fence = None
        elif capture:
            content.append(line)
    if fence is not None and capture:
        entries.append(Entry(number, "".join(content), "UNCLOSED_TEXT_BLOCK"))
    return entries


def group_sources(entries: list[Entry], report: Report) -> list[SourceGroup]:
    groups: dict[str, SourceGroup] = {}
    for entry in entries:
        numbers = [entry.number] if entry.number is not None else []
        if entry.error:
            report.error(numbers, entry.error)
            continue
        try:
            parsed = parse_input(entry.text)
        except ResolveError as exc:
            report.error(numbers, exc.code)
            continue
        url = (
            parsed.short_target.url
            if parsed.short_target
            else (f"https://www.xiaohongshu.com/goods-detail/{parsed.item_id}")
        )
        key = sha256(url.encode("utf-8"))
        if key not in groups:
            groups[key] = SourceGroup(key, url, entry.text, [], [], parsed.item_id)
        group = groups[key]
        group.entry_numbers = list(dict.fromkeys(group.entry_numbers + numbers))
        if parsed.source_type not in group.source_types:
            group.source_types.append(parsed.source_type)
    return list(groups.values())


class RequestPacer:
    def __init__(self, interval: float) -> None:
        self.interval = interval
        self.last: float | None = None

    async def __call__(self, request: httpx.Request) -> None:
        loop = asyncio.get_running_loop()
        if self.last is not None:
            delay = self.interval - (loop.time() - self.last)
            if delay > 0:
                await asyncio.sleep(delay)
        self.last = loop.time()


async def collect(
    input_path: Path,
    output_path: Path,
    *,
    dry_run: bool = False,
    limit: int | None = None,
    refresh: bool = False,
    interval: float = 2.0,
    transport: httpx.AsyncBaseTransport | None = None,
) -> Report:
    if not math.isfinite(interval) or interval < 0 or (limit is not None and limit <= 0):
        raise ArchiveError("INVALID_OPTIONS", "interval 必须为有限非负数，limit 必须为正整数")
    # The source file itself may never be an archive output destination.
    input_absolute = input_path.resolve()
    output_absolute = output_path.resolve()
    if input_absolute == output_absolute / "manifest.json" or (
        input_absolute.is_relative_to(output_absolute)
        and any(
            re.fullmatch(r"[0-9a-f]{24}", part)
            for part in input_absolute.relative_to(output_absolute).parts[:-1]
        )
    ):
        raise ArchiveError("UNSAFE_OUTPUT_PATH", "输入文件不能位于生成的归档文件路径")
    entries = read_entries(input_path)
    report = Report(entries=len(entries))
    if not entries:
        report.error([], "NO_TEXT_BLOCKS")
    groups = group_sources(entries, report)
    report.sources = len(groups)
    selected = groups if limit is None else groups[:limit]
    report.selected = len(selected)
    store = ArchiveStore(output_path)
    if dry_run:
        if (store.root / JOURNAL).exists():
            report.error([], "RECOVERY_PENDING")
        known_products: set[str] = set()
        for group in selected:
            saved = store.manifest.sources.get(group.source_hash)
            item_id = saved.item_id if saved else group.local_item_id
            if not saved:
                report.pending_sources += 1
            if item_id is None:
                report.unknown_short_targets += 1
            elif item_id not in known_products:
                known_products.add(item_id)
                try:
                    if store.cached(item_id) is not None:
                        report.cached += 1
                except ArchiveError as exc:
                    report.error(group.entry_numbers, exc.code)
        return report
    with store.locked():
        timeout = httpx.Timeout(connect=5, read=10, write=10, pool=5)
        async with httpx.AsyncClient(
            timeout=timeout,
            follow_redirects=False,
            transport=transport,
            event_hooks={
                "response": [check_short_redirect_response],
                "request": [RequestPacer(interval)],
            },
        ) as http:
            client = XhsClient(http)
            resolver = ProductResolver(client)
            attempted: dict[str, Product | Exception] = {}
            for group in selected:
                try:
                    saved = store.manifest.sources.get(group.source_hash)
                    if saved:
                        item_id = saved.item_id
                    else:
                        item_id = (await resolver.resolve(group.raw_input)).item_id
                        report.resolved += 1
                    raw_body = None
                    if item_id in attempted:
                        previous = attempted[item_id]
                        if isinstance(previous, Exception):
                            raise previous
                        product = previous
                    else:
                        try:
                            if refresh:
                                directory = store.item_directory(item_id)
                                if directory.exists() and item_id not in store.manifest.products:
                                    raise ArchiveError("OUTPUT_CONFLICT", "存在未登记商品目录")
                                product = None
                            else:
                                product = store.cached(item_id)
                            if product is None:
                                result = await client.fetch_product_result(item_id)
                                raw_body, product = result.raw_body, result.product
                                report.fetched += 1
                            else:
                                report.cached += 1
                            attempted[item_id] = product
                        except (ResolveError, CollectionError, ArchiveError) as exc:
                            attempted[item_id] = exc
                            raise
                except (ResolveError, CollectionError, ArchiveError) as exc:
                    report.error(group.entry_numbers, exc.code)
                    if exc.code in {"SHORT_URL_ACCESS_RESTRICTED", "UPSTREAM_ACCESS_RESTRICTED"}:
                        report.stopped = True
                        break
                    continue
                record = SourceRecord(
                    source_hash=group.source_hash,
                    source_url=group.source_url,
                    item_id=item_id,
                    source_type=group.source_types[0],
                    source_types=group.source_types,
                    source_file=input_path.name,
                    entry_numbers=group.entry_numbers,
                    entry_sources={input_path.name: group.entry_numbers},
                )
                # Commit failures are fatal: do not continue with an unfinished disk transaction.
                store.commit(item_id, product, raw_body, [record])
    return report


def project_path(value: str) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else COLLECTOR_ROOT / path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="从 text 代码块增量采集商品，逐字节归档原始响应")
    parser.add_argument("--input", type=project_path, default=DEFAULT_OUTPUT / "share_text.md")
    parser.add_argument("--output", type=project_path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--dry-run", action="store_true", help="不联网、不写文件，仅检查本地输入和缓存"
    )
    parser.add_argument("--limit", type=int, help="最多处理多少个不同来源")
    parser.add_argument("--refresh", action="store_true", help="成功获取新数据后才替换已登记快照")
    parser.add_argument(
        "--interval", type=float, default=2.0, help="请求开始之间的最小间隔秒数，默认 2"
    )
    args = parser.parse_args(argv)
    if (
        not math.isfinite(args.interval)
        or args.interval < 0
        or (args.limit is not None and args.limit <= 0)
    ):
        parser.error("interval 必须为有限非负数，limit 必须为正整数")
    try:
        report = asyncio.run(
            collect(
                args.input,
                args.output,
                dry_run=args.dry_run,
                limit=args.limit,
                refresh=args.refresh,
                interval=args.interval,
            )
        )
        print(
            f"条目 {report.entries}；不同来源 {report.sources}；选中 {report.selected}；"
            f"新解析 {report.resolved}；新采集 {report.fetched}；缓存 {report.cached}；失败 {len(report.errors)}"
        )
        if args.dry_run:
            print(
                f"待处理新来源 {report.pending_sources}；未知短链目标 {report.unknown_short_targets}（不推断商品 ID）"
            )
        for error in report.errors:
            print(f"条目 {error['entry_numbers']}：{error['code']}")
        if report.stopped:
            print("上游访问受限，已停止后续网络请求")
        return report.exit_code
    except (ArchiveError, CollectionError, ResolveError) as exc:
        print(f"{exc.code}：{exc.message}")
        return 1
    except OSError:
        print("ARCHIVE_IO_ERROR：归档文件操作失败，已有快照不会通过失败请求重建")
        return 1
    except KeyboardInterrupt:
        print("任务已中断，下次运行将检查恢复日志")
        return 1
