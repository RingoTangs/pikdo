import json
import re
from decimal import Decimal

import httpx

from xhs.errors import CollectionError, ResolveError
from xhs.parser import parse_product
from xhs.schemas import Product

SHORT_HOSTS = frozenset({"xhslink.com", "www.xhslink.com"})
SHORT_PATH = re.compile(r"/(?:[A-Za-z0-9_-]+/)?[A-Za-z0-9_-]+/?")

UPSTREAM_URL = "https://mall.xiaohongshu.com/api/store/jpd/edith/detail/h5/toc"


class XhsClient:
    def __init__(self, http: httpx.AsyncClient):
        self.http = http

    async def fetch_product(self, item_id: str) -> Product:
        try:
            response = await self.http.get(
                UPSTREAM_URL, params={"version": "0.0.5", "item_id": item_id}
            )
        except httpx.TimeoutException as exc:
            raise CollectionError("UPSTREAM_TIMEOUT", "请求上游超时", 504) from exc
        except httpx.RequestError as exc:
            raise CollectionError("UPSTREAM_REQUEST_FAILED", "无法连接上游服务") from exc
        if response.status_code in (401, 403, 429, 461):
            raise CollectionError("UPSTREAM_ACCESS_RESTRICTED", "上游访问受限", 503)
        if not 200 <= response.status_code < 300:
            raise CollectionError(
                "UPSTREAM_HTTP_ERROR", f"上游 HTTP 状态异常：{response.status_code}"
            )
        try:
            payload = json.loads(response.content, parse_float=Decimal)
        except (ValueError, UnicodeError) as exc:
            raise CollectionError("UPSTREAM_INVALID_JSON", "上游返回无效 JSON") from exc
        return parse_product(payload, item_id)

    async def fetch_short_redirect(self, hostname: str, path: str) -> tuple[int, str | None]:
        # Defense in depth: construct a fixed-authority URL, even for internal callers.
        if hostname not in SHORT_HOSTS or SHORT_PATH.fullmatch(path) is None:
            raise ResolveError("INVALID_PRODUCT_INPUT", "短链接地址不受支持")
        try:
            response = await self.http.get(f"https://{hostname}{path}", follow_redirects=False)
        except httpx.TimeoutException as exc:
            raise ResolveError("SHORT_URL_TIMEOUT", "短链接请求超时", 504) from exc
        except httpx.RequestError as exc:
            raise ResolveError("SHORT_URL_CONNECTION_FAILED", "无法连接短链接服务", 502) from exc
        return response.status_code, response.headers.get("location")
