"""Resolve product inputs without fetching H5 pages or trusting redirect hosts."""

import html
import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit

import httpx

from xhs.client import SHORT_HOSTS, SHORT_PATH, XhsClient
from xhs.errors import ResolveError
from xhs.schemas import ResolveResponse, SourceType

MAX_INPUT_LENGTH = 8192
MAX_REDIRECTS = 3
H5_HOSTS = frozenset({"xiaohongshu.com", "www.xiaohongshu.com"})
ITEM_ID = re.compile(r"[0-9a-fA-F]{24}")
PRODUCT_PATH = re.compile(r"/goods-detail/([0-9a-fA-F]{24})/?")
URL_PATTERN = re.compile(r"(?<![A-Za-z0-9_])https?://[^\s<>\"'，。；：！？、（）【】《》]+", re.I)
URL_TAIL = ".,;:!?)]}）】》>"
RESOLVE_PATH = "/api/v1/xhs/products/resolve"


def preprocess_input(raw: str) -> str:
    """One HTML decode and finite URL escapes only; never decode URL parameters."""
    if not isinstance(raw, str) or not 1 <= len(raw) <= MAX_INPUT_LENGTH:
        raise ResolveError("INVALID_PRODUCT_INPUT", "输入必须为 1–8192 字符的字符串")
    value = html.unescape(raw).strip()
    value = re.sub(r"\b(https?)\\?:\\?/\\?/", r"\1://", value, flags=re.I)
    value = URL_PATTERN.sub(lambda match: match.group().replace(r"\/", "/"), value)
    if not value:
        raise ResolveError("INVALID_PRODUCT_INPUT", "输入不能为空")
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
    status = 502 if redirect else 422
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
        raise ResolveError(code, "链接协议、域名或访问地址不受信任", status) from exc
    if host in H5_HOSTS:
        match = PRODUCT_PATH.fullmatch(parts.path)
        if match is None:
            raise ResolveError("NOT_PRODUCT_LINK", "链接不是受支持的商品详情链接")
        item_id = match.group(1).lower()
        return Target("www.xiaohongshu.com", f"/goods-detail/{item_id}", item_id)
    if SHORT_PATH.fullmatch(parts.path) is None:
        raise ResolveError("NOT_PRODUCT_LINK", "短链接路径不受支持")
    return Target(host, parts.path.rstrip("/"))


def redirect_target(current_url: str, location: str | None) -> Target:
    if not location or not location.strip():
        raise ResolveError("SHORT_URL_MISSING_LOCATION", "短链接重定向缺少 Location", 502)
    if "\\" in location or any(ord(c) < 32 or ord(c) == 127 for c in location):
        raise ResolveError("UNTRUSTED_REDIRECT_TARGET", "重定向地址不受信任", 502)
    try:
        reference = urlsplit(location.strip())
        if (reference.scheme or location.strip().startswith("//")) and not reference.netloc:
            raise ValueError
        value = urljoin(current_url, location.strip())
    except ValueError as exc:
        raise ResolveError("UNTRUSTED_REDIRECT_TARGET", "重定向地址不受信任", 502) from exc
    return validate_url(value, redirect=True)


async def check_short_redirect_response(response: httpx.Response) -> None:
    # HTTPX constructs response.next_request even with follow_redirects=False.
    # Validate first, then canonicalize so that it never builds an unsafe/token-bearing request.
    if response.request.url.host in SHORT_HOSTS and response.status_code in (
        301,
        302,
        303,
        307,
        308,
    ):
        target = redirect_target(str(response.request.url), response.headers.get("location"))
        response.headers["location"] = target.url


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
        raise ResolveError(code, "无法从输入内容中识别商品链接")
    targets: dict[str, Target] = {}
    for match in matches:
        # A complete URL consumes its query/fragment; nested URLs are not rescanned.
        target = validate_url(match.group().rstrip(URL_TAIL))
        targets[target.url] = target
    if len(targets) != 1:
        raise ResolveError("AMBIGUOUS_PRODUCT_INPUT", "输入包含多个不同目标，请仅提供一个商品链接")
    target = next(iter(targets.values()))
    standalone = len(matches) == 1 and value == matches[0].group().rstrip(URL_TAIL)
    source: SourceType = "share_text"
    if standalone:
        source = "h5_url" if target.item_id else "short_url"
    return ParsedInput(source, target.item_id, None if target.item_id else target)


def resolved(source_type: SourceType, item_id: str) -> ResolveResponse:
    return ResolveResponse(
        source_type=source_type,
        item_id=item_id,
        canonical_url=f"https://www.xiaohongshu.com/goods-detail/{item_id}",
    )


class ProductResolver:
    def __init__(self, client: XhsClient) -> None:
        self.client = client

    async def resolve(self, raw: str) -> ResolveResponse:
        parsed = parse_input(raw)
        if parsed.item_id is not None:
            return resolved(parsed.source_type, parsed.item_id)
        target = parsed.short_target
        assert target is not None
        visited: set[str] = set()
        for _ in range(MAX_REDIRECTS):
            if target.url in visited:
                break
            visited.add(target.url)
            status, location = await self.client.fetch_short_redirect(target.hostname, target.path)
            if status in (301, 302, 303, 307, 308):
                target = redirect_target(target.url, location)
                if target.item_id:
                    return resolved(parsed.source_type, target.item_id)
            elif status in (401, 403, 429, 461):
                raise ResolveError("SHORT_URL_ACCESS_RESTRICTED", "短链接访问受限", 503)
            elif status == 200:
                raise ResolveError(
                    "SHORT_URL_HTML_UNSUPPORTED", "暂不支持没有 HTTP 重定向的短链接页面", 502
                )
            else:
                raise ResolveError("SHORT_URL_HTTP_ERROR", "短链接返回异常 HTTP 状态", 502)
        raise ResolveError("SHORT_URL_REDIRECT_LIMIT", "短链接重定向循环或超过三次跳转", 502)
