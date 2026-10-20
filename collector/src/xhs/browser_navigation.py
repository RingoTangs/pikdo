"""Pure navigation input and trusted main-document validation."""

import re
from urllib.parse import urlsplit

from xhs.browser_contracts import BrowserCollectionError
from xhs.errors import InputError
from xhs.input_parser import (
    H5_HOSTS,
    URL_PATTERN,
    URL_TAIL,
    Target,
    parse_input,
    preprocess_input,
    validate_url,
)


def navigation_input(raw: str) -> tuple[str, str | None]:
    """Reuse local input validation, but retain the original URL's navigation context."""
    parsed = parse_input(raw)
    if parsed.source_type == "direct_id":
        return f"https://www.xiaohongshu.com/goods-detail/{parsed.item_id}", parsed.item_id
    matches = list(URL_PATTERN.finditer(preprocess_input(raw)))
    urls = list(dict.fromkeys(match.group().rstrip(URL_TAIL) for match in matches))
    # Input parsing merges canonical equivalents. Different contexts must not be chosen silently.
    if len(urls) != 1:
        raise InputError("AMBIGUOUS_PRODUCT_INPUT", "浏览器导航需要唯一的原始商品链接")
    validate_url(urls[0])
    return urls[0], parsed.item_id


def navigation_target(url: str) -> Target:
    try:
        parts = urlsplit(url)
    except ValueError as exc:
        raise BrowserCollectionError(
            "BROWSER_UNTRUSTED_REDIRECT", "主页面地址格式不受支持"
        ) from exc
    if parts.scheme == "https" and parts.hostname in H5_HOSTS:
        if re.search(r"/(?:login|captcha|security|verify)(?:/|$)", parts.path, re.I):
            raise BrowserCollectionError("BROWSER_ACCESS_RESTRICTED", "页面要求登录或安全验证")
    try:
        return validate_url(url, redirect=True)
    except (InputError, ValueError) as exc:
        raise BrowserCollectionError(
            "BROWSER_UNTRUSTED_REDIRECT", "已阻止不受信任或不支持的主页面导航"
        ) from exc
