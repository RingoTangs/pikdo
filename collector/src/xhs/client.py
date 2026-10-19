import json
import re
from dataclasses import dataclass, field
from decimal import Decimal

import httpx

from xhs.errors import CollectionError, ResolveError
from xhs.parser import parse_product
from xhs.schemas import Product
from xhs.values import obj

SHORT_HOSTS = frozenset({"xhslink.com", "www.xhslink.com"})
SHORT_PATH = re.compile(r"/(?:[A-Za-z0-9_-]+/)?[A-Za-z0-9_-]+/?")

UPSTREAM_URL = "https://mall.xiaohongshu.com/api/store/jpd/edith/detail/h5/toc"


@dataclass(frozen=True)
class ProductFetchResult:
    raw_body: bytes = field(repr=False)
    product: Product


def reject_json_constant(value: str) -> None:
    raise ValueError("Nonfinite JSON constant")


def decode_product_body(raw_body: bytes, item_id: str, *, verify_identity: bool = True) -> Product:
    try:
        if verify_identity:
            payload = json.loads(raw_body, parse_float=Decimal, parse_constant=reject_json_constant)
        else:
            payload = json.loads(raw_body, parse_float=Decimal)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise CollectionError("UPSTREAM_INVALID_JSON", "上游返回无效 JSON") from exc
    product = parse_product(payload, item_id)
    if verify_identity:
        data = payload["data"]["template_data"][0]
        identities: set[str] = set()
        sku_ids: set[str] = set()
        for block, key in (
            ("descriptionMain", "itemId"),
            ("descriptionH5", "skuId"),
            ("selectedH5", "skuId"),
            ("carouselH5", "skuId"),
            ("bottomBarMainH5", "skuId"),
        ):
            value = obj(data.get(block)).get(key)
            if value is None:
                continue
            if not isinstance(value, str) or re.fullmatch(r"[0-9a-fA-F]{24}", value) is None:
                raise CollectionError("UPSTREAM_IDENTITY_INVALID", "上游商品身份格式无效")
            identities.add(value.lower())
            if key == "skuId":
                sku_ids.add(value.lower())
        if not identities:
            raise CollectionError("UPSTREAM_IDENTITY_MISSING", "上游缺少可验证的商品身份")
        if item_id not in identities or len(sku_ids) > 1:
            raise CollectionError("UPSTREAM_IDENTITY_MISMATCH", "上游商品或 SKU 身份与请求不一致")
    return product


class XhsClient:
    def __init__(self, http: httpx.AsyncClient):
        self.http = http

    async def fetch_product(self, item_id: str) -> Product:
        # Preserve public API semantics; archival identity checks are stricter.
        return (await self._fetch_product_result(item_id, verify_identity=False)).product

    async def fetch_product_result(self, item_id: str) -> ProductFetchResult:
        if re.fullmatch(r"[0-9a-fA-F]{24}", item_id) is None:
            raise CollectionError("INVALID_ITEM_ID", "商品 ID 必须为 24 位十六进制字符串", 422)
        return await self._fetch_product_result(item_id.lower(), verify_identity=True)

    async def _fetch_product_result(
        self, item_id: str, *, verify_identity: bool
    ) -> ProductFetchResult:
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
        raw_body = response.content
        return ProductFetchResult(
            raw_body=raw_body,
            product=decode_product_body(raw_body, item_id, verify_identity=verify_identity),
        )

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
