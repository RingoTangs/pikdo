"""Safe standalone CLI for the experimental browser collector."""

import argparse
import asyncio
import hashlib
import math
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path

from xhs.archive_paths import DEFAULT_OUTPUT, PROBE_MARKER, archive_root
from xhs.browser_collector import BrowserCollectionError, BrowserProductCollector, ProbeStatus
from xhs.errors import ResolveError
from xhs.resolver import parse_input


def safe_label(value: str | None) -> str:
    if value is None:
        return "未提供"
    if "://" in value or re.search(
        r"cookie|x-s\b|x-t\b|xsec|token|authorization|signature|instation_link", value, re.I
    ):
        return "[已隐藏敏感内容]"
    return re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", value)[:300]


def checked_output(value: str) -> Path:
    path = Path(value).expanduser().absolute()
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "输出路径不能包含符号链接")
    path = path.resolve()
    if path == DEFAULT_OUTPUT or path.is_relative_to(DEFAULT_OUTPUT):
        raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "不能写入原始 examples 或正式归档")
    if any(archive_root(parent) for parent in (path, *path.parents)):
        raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "不能写入正式归档目录")
    for name in ("response.json", PROBE_MARKER):
        destination = path / name
        if destination.is_symlink() or (destination.exists() and not destination.is_file()):
            raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "输出文件类型不安全")
    # Prevent an arbitrary in-repository --output from silently exposing raw data to Git.
    existing = next(parent for parent in (path, *path.parents) if parent.is_dir())
    repository = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=existing,
        capture_output=True,
        check=False,
    )
    if repository.returncode == 0:
        ignored = subprocess.run(
            ["git", "check-ignore", "--quiet", "--", str(path / "response.json")],
            cwd=existing,
            capture_output=True,
            check=False,
        )
        if ignored.returncode != 0:
            raise BrowserCollectionError(
                "BROWSER_UNSAFE_OUTPUT", "仓库内调试输出目录必须先加入 Git 忽略规则"
            )
    return path


def write_output(path: Path, body: bytes) -> None:
    # Revalidate immediately before writing; never create files for failed collection.
    path = checked_output(str(path))
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    marker = path / PROBE_MARKER
    if marker.exists() and marker.read_bytes() != b"pikdo-browser-probe-v1\n":
        raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "调试目录标记不匹配")
    marker.write_bytes(b"pikdo-browser-probe-v1\n")
    marker.chmod(0o600)
    descriptor, temporary = tempfile.mkstemp(prefix=".browser-probe-", dir=path)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(body)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path / "response.json")
    finally:
        Path(temporary).unlink(missing_ok=True)


def positive_seconds(value: str) -> float:
    try:
        result = float(value)
        if math.isfinite(result) and result > 0:
            return result
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("timeout 必须为有限正数（秒）")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="独立浏览器单商品验证，不影响生产 API 或正式归档")
    parser.add_argument("--input", required=True, help="分享文案、短链、H5 商品链接或商品 ID")
    parser.add_argument("--headed", action="store_true", help="显示浏览器窗口")
    parser.add_argument("--channel", choices=["chrome"], help="使用系统 Chrome")
    parser.add_argument("--timeout", type=positive_seconds, help="导航和响应分别使用的超时秒数")
    parser.add_argument("--output", help="可选调试目录；仓库内建议使用 .browser-probe")
    args = parser.parse_args(argv)
    started = time.monotonic()
    status = ProbeStatus()
    code = "SUCCESS"
    result = None
    try:
        parsed = parse_input(args.input)
        output = checked_output(args.output) if args.output is not None else None
        if parsed.source_type == "direct_id":
            print("输入方式：纯商品 ID，没有原始分享上下文")
        collector = BrowserProductCollector(
            headless=not args.headed,
            channel=args.channel,
            navigation_timeout=args.timeout if args.timeout is not None else 30,
            response_timeout=args.timeout if args.timeout is not None else 15,
            status=status,
        )
        result = asyncio.run(collector.collect(args.input))
        if output is not None:
            write_output(output, result.raw_body)
    except (BrowserCollectionError, ResolveError) as exc:
        code = exc.code
    except OSError:
        code = "BROWSER_OUTPUT_IO_ERROR"
    except KeyboardInterrupt:
        code = "BROWSER_INTERRUPTED"
    print(f"浏览器启动：{'成功' if status.browser_started else '未启动'}")
    print(f"导航状态：{status.navigation}")
    print(f"导航 HTTP 状态：{status.navigation_http_status or '未捕获'}")
    print(f"商品 ID：{status.item_id or '未确认'}")
    print(f"商品响应 HTTP 状态：{status.http_status or '未捕获'}")
    if result is not None:
        print(f"商品名称：{safe_label(result.product.title)}")
        print(f"店铺名称：{safe_label(result.product.shop.name)}")
        print(f"原始 JSON 字节数：{len(result.raw_body)}")
        print(f"SHA-256：{hashlib.sha256(result.raw_body).hexdigest()}")
    print(f"总耗时：{time.monotonic() - started:.3f} 秒")
    print(f"结果：{code}")
    return 0 if code == "SUCCESS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
