"""Validated archive metadata, atomic byte storage and recoverable single-item commits."""

import hashlib
import html
import json
import os
import re
import tempfile
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Iterator, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from xhs.archive_paths import (
    DEFAULT_OUTPUT,
    ID_PATTERN,
    JOURNAL,
    LOCK,
    MANIFEST,
    MARKER,
    MARKER_BODY,
    STAGE,
)
from xhs.product_decoder import decode_product_body
from xhs.resolver import parse_input
from xhs.schemas import Product, SourceType
from xhs.values import safe_json


class ArchiveError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class MetadataModel(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")


class SourceRecord(MetadataModel):
    source_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_url: str
    item_id: str = Field(pattern=r"^[0-9a-f]{24}$")
    source_type: SourceType
    source_types: list[SourceType]
    source_file: str
    entry_numbers: list[int]
    entry_sources: dict[str, list[int]]


class ProductRecord(MetadataModel):
    fetched_at: str
    response_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class Manifest(MetadataModel):
    schema_version: Literal[1] = 1
    sources: dict[str, SourceRecord] = Field(default_factory=dict)
    products: dict[str, ProductRecord] = Field(default_factory=dict)


class PendingCommit(MetadataModel):
    item_id: str = Field(pattern=r"^[0-9a-f]{24}$")
    old_sha256: str | None
    new_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    base_manifest_sha256: str | None
    manifest: Manifest


def sha256(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def metadata_bytes(model: BaseModel) -> bytes:
    # Only control metadata uses JSON encoding. Upstream bodies never enter this function.
    return (json.dumps(model.model_dump(), ensure_ascii=False, indent=4) + "\n").encode("utf-8")


def unique_pairs(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate metadata key")
        result[key] = value
    return result


def read_metadata(path: Path, model: type[MetadataModel]) -> MetadataModel:
    try:
        raw = json.loads(path.read_bytes(), object_pairs_hook=unique_pairs)
        return model.model_validate(raw)
    except (ValueError, OSError, ValidationError) as exc:
        raise ArchiveError("INVALID_MANIFEST", "归档清单或恢复日志损坏，已停止操作") from exc


def check_manifest(manifest: Manifest) -> None:
    try:
        for item_id, record in manifest.products.items():
            if ID_PATTERN.fullmatch(item_id) is None:
                raise ValueError
            date = datetime.fromisoformat(record.fetched_at)
            if date.utcoffset() is None:
                raise ValueError
        for key, record in manifest.sources.items():
            parsed = parse_input(record.source_url)
            canonical = (
                parsed.short_target.url
                if parsed.short_target
                else (f"https://www.xiaohongshu.com/goods-detail/{parsed.item_id}")
            )
            if (
                not record.entry_sources
                or set(record.entry_numbers)
                != {n for ns in record.entry_sources.values() for n in ns}
                or any(
                    not ns
                    or any(type(n) is not int or n <= 0 for n in ns)
                    or Path(name).name != name
                    or any(c in name for c in "\\/\r\n")
                    for name, ns in record.entry_sources.items()
                )
                or canonical != record.source_url
                or sha256(canonical.encode()) != key
                or record.source_hash != key
                or record.item_id not in manifest.products
                or (parsed.item_id is not None and parsed.item_id != record.item_id)
                or not record.entry_numbers
                or any(n <= 0 for n in record.entry_numbers)
                or len(set(record.entry_numbers)) != len(record.entry_numbers)
                or not record.source_types
                or record.source_type not in record.source_types
                or Path(record.source_file).name != record.source_file
                or any(c in record.source_file for c in "\\/\r\n")
            ):
                raise ValueError
    except Exception as exc:
        raise ArchiveError("INVALID_MANIFEST", "归档清单内容不一致，已停止操作") from exc


def guard(path: Path, *, directory: bool = False) -> None:
    if path.is_symlink() or (
        path.exists() and (not path.is_dir() if directory else not path.is_file())
    ):
        raise ArchiveError("UNSAFE_OUTPUT_PATH", "归档路径包含符号链接或错误文件类型")


def atomic_write(path: Path, body: bytes) -> None:
    guard(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".collect-examples-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(body)
            stream.flush()
            os.fsync(stream.fileno())
        if sha256(Path(temporary).read_bytes()) != sha256(body):
            raise ArchiveError("ARCHIVE_WRITE_FAILED", "临时文件摘要校验失败")
        os.replace(temporary, path)
    finally:
        if Path(temporary).exists():
            Path(temporary).unlink()


def md_text(value: object) -> str:
    if value is None:
        return "未提供"
    safe = safe_json(str(value))
    if not isinstance(safe, str):
        return "未提供"
    safe = " ".join(safe.split())
    return re.sub(r"([\\`*_{}\[\]()#+.!|>-])", r"\\\1", html.escape(safe, quote=True))


def render_index(item_id: str, product: Product, manifest: Manifest) -> bytes:
    record = manifest.products[item_id]
    lines = [
        f"# {md_text(product.title)}",
        "",
        "## 商品信息",
        "",
        f"- 商品 ID：{item_id}",
        f"- 店铺名称：{md_text(product.shop.name)}",
        f"- 到手价：{md_text(product.price.current)}",
        f"- 展示价：{md_text(product.price.display)}",
        f"- 累计销量：{md_text(product.sales.display)}",
        f"- 商品 URL：https://www.xiaohongshu.com/goods-detail/{item_id}",
        "",
        "## 数据来源",
        "",
    ]
    for source in manifest.sources.values():
        if source.item_id == item_id:
            for filename, numbers in source.entry_sources.items():
                labels = ", ".join(str(n) for n in numbers)
                lines.append(f"- 来源文件：{md_text(filename)}；条目编号：{labels}")
            lines.append(f"- 来源链接：{source.source_url}")
    lines.extend(
        [
            "",
            "## 采集信息",
            "",
            f"- fetched_at：{record.fetched_at}",
            f"- response.json SHA-256：{record.response_sha256}",
            "- [response.json](response.json)",
            "",
        ]
    )
    return "\n".join(lines).encode("utf-8")


class ArchiveStore:
    def __init__(self, root: Path) -> None:
        guard(root, directory=True)
        self.root = root.resolve()
        self.manifest = Manifest()
        self.validate_root()
        self.reload()

    def validate_root(self) -> None:
        marker = self.root / MARKER
        for name in (MARKER, MANIFEST, JOURNAL, LOCK):
            guard(self.root / name)
        if marker.exists() and marker.read_bytes() != MARKER_BODY:
            raise ArchiveError("OUTPUT_CONFLICT", "输出目录归档标记无效")
        if self.root != DEFAULT_OUTPUT and self.root.exists() and not marker.exists():
            if any(self.root.iterdir()):
                raise ArchiveError("OUTPUT_CONFLICT", "自定义输出目录非空且不属于本工具")

    def reload(self) -> None:
        path = self.root / MANIFEST
        guard(path)
        self.manifest = read_metadata(path, Manifest) if path.exists() else Manifest()
        check_manifest(self.manifest)

    @contextmanager
    def locked(self) -> Iterator[None]:
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        guard(self.root / LOCK)
        descriptor = os.open(self.root / LOCK, os.O_CREAT | os.O_RDWR, 0o600)
        stream = os.fdopen(descriptor, "r+b")
        try:
            try:
                if os.name == "nt":
                    import msvcrt

                    if stream.seek(0, os.SEEK_END) == 0:
                        stream.write(b"0")
                        stream.flush()
                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise ArchiveError("ARCHIVE_BUSY", "另一个进程正在操作此输出目录") from exc
            guard(self.root / MARKER)
            if not (self.root / MARKER).exists():
                atomic_write(self.root / MARKER, MARKER_BODY)
            self.reload()
            self.recover()
            yield
        finally:
            stream.close()  # OS releases the advisory lock, including after process termination.

    def item_directory(self, item_id: str) -> Path:
        if ID_PATTERN.fullmatch(item_id) is None:
            raise ArchiveError("INVALID_ARCHIVE_ID", "归档商品 ID 无效")
        directory = self.root / item_id
        guard(directory, directory=True)
        for name in ("response.json", "index.md", STAGE):
            guard(directory / name)
        return directory

    def cached(self, item_id: str) -> Product | None:
        directory = self.item_directory(item_id)
        record = self.manifest.products.get(item_id)
        if record is None:
            if directory.exists():
                raise ArchiveError("OUTPUT_CONFLICT", "存在未登记商品目录，不会覆盖")
            return None
        path = directory / "response.json"
        if not path.is_file():
            raise ArchiveError("CACHE_MISSING", "缓存文件缺失，请使用 --refresh")
        body = path.read_bytes()
        if sha256(body) != record.response_sha256:
            raise ArchiveError("CACHE_CORRUPT", "缓存摘要不匹配，请使用 --refresh")
        try:
            product = decode_product_body(body, item_id)
        except Exception as exc:
            raise ArchiveError("CACHE_CORRUPT", "缓存商品数据无效，请使用 --refresh") from exc
        return product.model_copy(update={"fetched_at": datetime.fromisoformat(record.fetched_at)})

    def commit(
        self, item_id: str, product: Product, raw_body: bytes | None, sources: list[SourceRecord]
    ) -> None:
        directory = self.item_directory(item_id)
        if directory.exists() and item_id not in self.manifest.products:
            raise ArchiveError("OUTPUT_CONFLICT", "存在未登记商品目录，不会覆盖")
        next_manifest = self.manifest.model_copy(deep=True)
        path = directory / "response.json"
        old_hash = sha256(path.read_bytes()) if path.exists() else None
        if raw_body is not None:
            next_manifest.products[item_id] = ProductRecord(
                fetched_at=product.fetched_at.isoformat(), response_sha256=sha256(raw_body)
            )
        elif (
            item_id not in next_manifest.products
            or old_hash != next_manifest.products[item_id].response_sha256
        ):
            raise ArchiveError("CACHE_CORRUPT", "缓存已变更，不能更新来源索引")
        for source in sources:
            previous = next_manifest.sources.get(source.source_hash)
            if previous:
                entry_sources = {
                    name: list(numbers) for name, numbers in previous.entry_sources.items()
                }
                for name, numbers in source.entry_sources.items():
                    entry_sources[name] = list(dict.fromkeys(entry_sources.get(name, []) + numbers))
                source = source.model_copy(
                    update={
                        "source_file": previous.source_file,
                        "entry_sources": entry_sources,
                        "entry_numbers": list(
                            dict.fromkeys(previous.entry_numbers + source.entry_numbers)
                        ),
                        "source_types": list(
                            dict.fromkeys(previous.source_types + source.source_types)
                        ),
                        "source_type": previous.source_type,
                    }
                )
            next_manifest.sources[source.source_hash] = source
        check_manifest(next_manifest)
        manifest_path = self.root / MANIFEST
        pending = PendingCommit(
            item_id=item_id,
            old_sha256=old_hash,
            new_sha256=next_manifest.products[item_id].response_sha256,
            base_manifest_sha256=sha256(manifest_path.read_bytes())
            if manifest_path.exists()
            else None,
            manifest=next_manifest,
        )
        # A journal precedes staging; the success manifest always follows the response file.
        atomic_write(self.root / JOURNAL, metadata_bytes(pending))
        directory.mkdir(mode=0o700, exist_ok=True)
        if raw_body is not None:
            atomic_write(directory / STAGE, raw_body)
        self.recover()

    def recover(self) -> None:
        journal_path = self.root / JOURNAL
        guard(journal_path)
        if not journal_path.exists():
            return
        pending = read_metadata(journal_path, PendingCommit)
        check_manifest(pending.manifest)
        if (
            pending.item_id not in pending.manifest.products
            or pending.manifest.products[pending.item_id].response_sha256 != pending.new_sha256
        ):
            raise ArchiveError("INVALID_MANIFEST", "恢复日志摘要引用不一致")
        manifest_path = self.root / MANIFEST
        current_manifest_hash = (
            sha256(manifest_path.read_bytes()) if manifest_path.exists() else None
        )
        if current_manifest_hash not in (
            pending.base_manifest_sha256,
            sha256(metadata_bytes(pending.manifest)),
        ):
            raise ArchiveError("RECOVERY_CONFLICT", "归档清单已被修改，不能自动恢复")
        directory = self.item_directory(pending.item_id)
        response_path, stage = directory / "response.json", directory / STAGE
        current = sha256(response_path.read_bytes()) if response_path.exists() else None
        if current != pending.new_sha256:
            if current != pending.old_sha256:
                raise ArchiveError("RECOVERY_CONFLICT", "归档数据已被修改，不能自动恢复")
            if not stage.exists():
                # Interrupted before staging: no data was replaced. Retry on the next run.
                journal_path.unlink()
                if directory.exists():
                    for temporary in directory.glob(".collect-examples-*"):
                        guard(temporary)
                        temporary.unlink()
                if directory.exists() and not any(directory.iterdir()):
                    directory.rmdir()
                return
            if sha256(stage.read_bytes()) != pending.new_sha256:
                raise ArchiveError("RECOVERY_CONFLICT", "恢复临时文件摘要错误")
            decode_product_body(stage.read_bytes(), pending.item_id)
            os.replace(stage, response_path)
        raw = response_path.read_bytes()
        if sha256(raw) != pending.new_sha256:
            raise ArchiveError("ARCHIVE_WRITE_FAILED", "归档文件摘要校验失败")
        product = decode_product_body(raw, pending.item_id)
        atomic_write(
            directory / "index.md", render_index(pending.item_id, product, pending.manifest)
        )
        atomic_write(manifest_path, metadata_bytes(pending.manifest))
        self.manifest = pending.manifest
        if stage.exists():
            stage.unlink()
        journal_path.unlink()
