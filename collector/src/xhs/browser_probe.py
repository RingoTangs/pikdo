"""Safe standalone CLI for the experimental browser collector."""

import argparse
import asyncio
import hashlib
import json
import math
import os
import re
import stat
import subprocess
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path

from xhs.browser_collector import BrowserCollectionError, BrowserProductCollector, ProbeStatus
from xhs.errors import BROWSER_HTTP_ERRORS, InputError
from xhs.input_parser import parse_input
from xhs.probe_paths import EXAMPLES_DIR, PROBE_MARKER, legacy_directory
from xhs.upstream_diagnostics import UpstreamDiagnostic


def safe_label(value: str | None) -> str:
    if value is None:
        return "未提供"
    if "://" in value or re.search(
        r"cookie|x-s\b|x-t\b|xsec|token|authorization|signature|instation_link", value, re.I
    ):
        return "[已隐藏敏感内容]"
    return re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", value)[:300]


def checked_output(value: str, *, files: tuple[str, ...] = ("response.json",)) -> Path:
    path = Path(value).expanduser().absolute()
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "输出路径不能包含符号链接")
    path = path.resolve()
    if path == EXAMPLES_DIR or path.is_relative_to(EXAMPLES_DIR):
        raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "不能写入原始 examples 或历史归档")
    if any(legacy_directory(parent) for parent in (path, *path.parents)):
        raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "不能写入历史归档目录")
    for name in (*files, PROBE_MARKER):
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
        for name in files:
            ignored = subprocess.run(
                ["git", "check-ignore", "--quiet", "--", str(path / name)],
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


@contextmanager
def _output_directory(path: Path):
    """Open every component without following links, including the final directory."""
    descriptor = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in path.parts[1:]:
            try:
                child = os.open(
                    component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor
                )
            except FileNotFoundError:
                os.mkdir(component, mode=0o700, dir_fd=descriptor)
                child = os.open(
                    component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor
                )
            os.close(descriptor)
            descriptor = child
        yield descriptor
    finally:
        os.close(descriptor)


def _mark_directory(descriptor: int) -> None:
    marker = b"pikdo-browser-probe-v1\n"
    try:
        fd = os.open(
            PROBE_MARKER,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=descriptor,
        )
    except FileExistsError:
        fd = os.open(PROBE_MARKER, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        with os.fdopen(fd, "rb") as stream:
            if (
                not stat.S_ISREG(os.fstat(stream.fileno()).st_mode)
                or stream.read(len(marker) + 1) != marker
            ):
                raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "调试目录标记不匹配")
    else:
        with os.fdopen(fd, "wb") as stream:
            stream.write(marker)
            stream.flush()
            os.fsync(stream.fileno())


def write_error_output(path: Path, diagnostic: UpstreamDiagnostic, code: str) -> None:
    metadata = diagnostic.safe_metadata()
    collection_id = metadata["collection_id"]
    if collection_id is None or code not in BROWSER_HTTP_ERRORS:
        raise BrowserCollectionError("BROWSER_UNSAFE_OUTPUT", "诊断元数据不受支持")
    metadata["collector_error"] = code
    if diagnostic.raw_body is not None and diagnostic.capture_status in {"captured", "empty"}:
        metadata["body_size"] = len(diagnostic.raw_body)
        metadata["body_sha256"] = hashlib.sha256(diagnostic.raw_body).hexdigest()
    names = ("upstream-error.bin", "diagnostic.json", PROBE_MARKER)
    path = checked_output(str(path), files=names)
    destination = path / f"error-{collection_id}"
    checked_output(str(destination), files=names)
    # A single directory rename publishes the pair. No existing diagnostic is replaced.
    with _output_directory(path) as root_fd:
        _mark_directory(root_fd)
        temporary = f".browser-probe-error-{os.urandom(16).hex()}"
        os.mkdir(temporary, mode=0o700, dir_fd=root_fd)
        stage_fd = os.open(temporary, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
        published = False
        try:
            artifacts = {
                "diagnostic.json": json.dumps(metadata, ensure_ascii=False, indent=2).encode()
                + b"\n"
            }
            if diagnostic.raw_body is not None and diagnostic.capture_status in {
                "captured",
                "empty",
            }:
                artifacts["upstream-error.bin"] = diagnostic.raw_body
            for name, body in artifacts.items():
                fd = os.open(
                    name,
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                    0o600,
                    dir_fd=stage_fd,
                )
                with os.fdopen(fd, "wb") as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(body)
                    stream.flush()
                    os.fsync(stream.fileno())
            os.fsync(stage_fd)
            checked_output(str(destination), files=names)
            try:
                os.stat(destination.name, dir_fd=root_fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise BrowserCollectionError(
                    "BROWSER_UNSAFE_OUTPUT", "诊断目录已存在，不覆盖历史数据"
                )
            os.rename(temporary, destination.name, src_dir_fd=root_fd, dst_dir_fd=root_fd)
            published = True
            os.fsync(root_fd)
        finally:
            # Remove only our unpublished temporary files, using the held descriptors.
            if not published:
                for name in ("upstream-error.bin", "diagnostic.json"):
                    try:
                        os.unlink(name, dir_fd=stage_fd)
                    except FileNotFoundError:
                        pass
            os.close(stage_fd)
            try:
                os.rmdir(temporary, dir_fd=root_fd)
            except FileNotFoundError:
                pass


def positive_seconds(value: str) -> float:
    try:
        result = float(value)
        if math.isfinite(result) and result > 0:
            return result
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("timeout 必须为有限正数（秒）")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="独立浏览器单商品验证，不影响生产 API 或历史归档")
    parser.add_argument("--input", required=True, help="分享文案、短链、H5 商品链接或商品 ID")
    parser.add_argument("--headed", action="store_true", help="显示浏览器窗口")
    parser.add_argument("--channel", choices=["chrome"], help="使用系统 Chrome")
    parser.add_argument("--timeout", type=positive_seconds, help="导航和响应分别使用的超时秒数")
    parser.add_argument("--error-output", help="可选上游错误诊断目录；默认不导出")
    parser.add_argument("--output", help="可选调试目录；仓库内建议使用 .browser-probe")
    args = parser.parse_args(argv)
    started = time.monotonic()
    status = ProbeStatus()
    code = "SUCCESS"
    result = None
    error_output = None
    try:
        parsed = parse_input(args.input)
        output = checked_output(args.output) if args.output is not None else None
        if args.error_output is not None:
            error_output = checked_output(
                args.error_output, files=("upstream-error.bin", "diagnostic.json", PROBE_MARKER)
            )
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
    except (BrowserCollectionError, InputError) as exc:
        code = exc.code
        diagnostic = getattr(exc, "upstream_diagnostic", None)
        if error_output is not None and diagnostic is not None:
            try:
                write_error_output(error_output, diagnostic, code)
            except (OSError, BrowserCollectionError):
                print("诊断导出：BROWSER_OUTPUT_IO_ERROR")
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
