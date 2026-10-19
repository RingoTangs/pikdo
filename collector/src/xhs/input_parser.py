"""Pure product input parsing and trusted URL validation; never accesses the network."""

import html
import re
from dataclasses import dataclass
from typing import Literal
from urllib.parse import urlsplit

from xhs.errors import InputError

MAX_INPUT_LENGTH = 8192
H5_HOSTS = frozenset({"xiaohongshu.com", "www.xiaohongshu.com"})
ITEM_ID = re.compile(r"[0-9a-fA-F]{24}")
PRODUCT_PATH = re.compile(r"/goods-detail/([0-9a-fA-F]{24})/?")
URL_PATTERN = re.compile(r"(?<![A-Za-z0-9_])https?://[^\s<>\"'，。；：！？、（）【】《》]+", re.I)
URL_TAIL = ".,;:!?)]}）】》>"
SHORT_HOSTS = frozenset({"xhslink.com", "www.xhslink.com"})
SHORT_PATH = re.compile(r"/(?:[A-Za-z0-9_-]+/)?[A-Za-z0-9_-]+/?")
SourceType = Literal["direct_id", "h5_url", "short_url", "share_text"]


def preprocess_input(raw: str) -> str:
    """One HTML decode and finite URL escapes only; never decode URL parameters."""
    if not isinstance(raw, str) or not 1 <= len(raw) <= MAX_INPUT_LENGTH:
        raise InputError("INVALID_PRODUCT_INPUT", "输入必须为 1–8192 字符的字符串")
    value = html.unescape(raw).strip()
    value = re.sub(r"\b(https?)\\?:\\?/\\?/", r"\1://", value, flags=re.I)
    value = URL_PATTERN.sub(lambda match: match.group().replace(r"\/", "/"), value)
    if not value:
        raise InputError("INVALID_PRODUCT_INPUT", "输入不能为空")
    return value


@dataclass(frozen=True)
class Target:
    hostname: str
    path: str
    item_id: str | None = None

    @property
    def url(self) -> str:
        # Only validated ASCII hostnames and paths are ever retained here.
        return f"https://{self.hostname}{self.path}"


def validate_url(value: str, *, redirect: bool = False) -> Target:
    code = "UNTRUSTED_REDIRECT_TARGET" if redirect else "INVALID_PRODUCT_INPUT"
    try:
        if "\\" in value or any(ord(char) <= 32 or ord(char) == 127 for char in value):
            raise ValueError
        parts = urlsplit(value)
        host = parts.hostname
        if (
            parts.scheme != "https"
            or host not in H5_HOSTS | SHORT_HOSTS
            or parts.username is not None
            or parts.password is not None
            or parts.port not in (None, 443)
            or parts.netloc.lower() not in (host, f"{host}:443")
        ):
            raise ValueError
    except ValueError as exc:
        raise InputError(code, "链接协议、域名或访问地址不受信任") from exc
    if host in H5_HOSTS:
        match = PRODUCT_PATH.fullmatch(parts.path)
        if match is None:
            raise InputError("NOT_PRODUCT_LINK", "链接不是受支持的商品详情链接")
        item_id = match.group(1).lower()
        return Target("www.xiaohongshu.com", f"/goods-detail/{item_id}", item_id)
    if SHORT_PATH.fullmatch(parts.path) is None:
        raise InputError("NOT_PRODUCT_LINK", "短链接路径不受支持")
    return Target(host, parts.path.rstrip("/"))


@dataclass(frozen=True)
class ParsedInput:
    source_type: SourceType
    item_id: str | None = None
    short_target: Target | None = None


def parse_input(raw: str) -> ParsedInput:
    """Pure parsing and validation; never searches prose for bare 24-character IDs."""
    value = preprocess_input(raw)
    if ITEM_ID.fullmatch(value):
        return ParsedInput("direct_id", value.lower())
    matches = list(URL_PATTERN.finditer(value))
    if not matches:
        code = (
            "INVALID_PRODUCT_INPUT"
            if re.fullmatch(r"[A-Za-z0-9]+", value)
            else "PRODUCT_LINK_NOT_FOUND"
        )
        raise InputError(code, "无法从输入内容中识别商品链接")
    targets: dict[str, Target] = {}
    for match in matches:
        # A complete URL consumes its query/fragment; nested URLs are not rescanned.
        target = validate_url(match.group().rstrip(URL_TAIL))
        targets[target.url] = target
    if len(targets) != 1:
        raise InputError("AMBIGUOUS_PRODUCT_INPUT", "输入包含多个不同目标，请仅提供一个商品链接")
    target = next(iter(targets.values()))
    standalone = len(matches) == 1 and value == matches[0].group().rstrip(URL_TAIL)
    source: SourceType = "share_text"
    if standalone:
        source = "h5_url" if target.item_id else "short_url"
    return ParsedInput(source, target.item_id, None if target.item_id else target)
