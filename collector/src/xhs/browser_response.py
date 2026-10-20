"""Strict browser-generated response matching and product decoding."""

from urllib.parse import parse_qs, urlsplit

from playwright.async_api import Response

from xhs.browser_contracts import BrowserCollectionError
from xhs.errors import ProductDataError
from xhs.input_parser import ITEM_ID
from xhs.product_decoder import decode_product_body
from xhs.schemas import Product

DETAIL_PATH = "/api/store/jpd/edith/detail/h5/toc"


def response_item_id(response: Response) -> str | None:
    try:
        parts = urlsplit(response.url)
        if (
            parts.scheme != "https"
            or parts.hostname != "mall.xiaohongshu.com"
            or parts.netloc.lower() not in {"mall.xiaohongshu.com", "mall.xiaohongshu.com:443"}
            or parts.path != DETAIL_PATH
            or response.request.method != "GET"
        ):
            return None
        values = parse_qs(parts.query, keep_blank_values=True).get("item_id", [])
        if len(values) == 1 and ITEM_ID.fullmatch(values[0]):
            return values[0].lower()
    except ValueError:
        pass
    return None


def parse_browser_body(body: bytes, item_id: str) -> Product:
    try:
        product = decode_product_body(body, item_id)
    except ProductDataError as exc:
        if exc.code == "UPSTREAM_ACCESS_RESTRICTED":
            code = "BROWSER_ACCESS_RESTRICTED"
        elif exc.code == "PRODUCT_UNAVAILABLE":
            code = "BROWSER_PRODUCT_UNAVAILABLE"
        elif exc.code == "UPSTREAM_IDENTITY_MISMATCH":
            code = "BROWSER_PRODUCT_ID_MISMATCH"
        else:
            code = "BROWSER_INVALID_RESPONSE"
        raise BrowserCollectionError(code, "商品响应未通过业务或身份验证") from exc
    explicit_id = product.basic_info.product_item_id
    if explicit_id is None or ITEM_ID.fullmatch(explicit_id) is None:
        raise BrowserCollectionError("BROWSER_INVALID_RESPONSE", "响应缺少明确的商品 ID")
    if explicit_id.lower() != item_id:
        raise BrowserCollectionError("BROWSER_PRODUCT_ID_MISMATCH", "响应商品 ID 与页面不一致")
    return product
