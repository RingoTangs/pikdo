"""Pure product JSON decoding and identity validation; no network dependencies."""

import json
import re
from decimal import Decimal

from xhs.errors import ProductDataError
from xhs.parser import parse_product
from xhs.schemas import Product
from xhs.values import obj


def reject_json_constant(value: str) -> None:
    raise ValueError("Nonfinite JSON constant")


def decode_product_body(raw_body: bytes, item_id: str) -> Product:
    try:
        payload = json.loads(raw_body, parse_float=Decimal, parse_constant=reject_json_constant)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise ProductDataError("UPSTREAM_INVALID_JSON", "上游返回无效 JSON") from exc
    product = parse_product(payload, item_id)
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
            raise ProductDataError("UPSTREAM_IDENTITY_INVALID", "上游商品身份格式无效")
        identities.add(value.lower())
        if key == "skuId":
            sku_ids.add(value.lower())
    if not identities:
        raise ProductDataError("UPSTREAM_IDENTITY_MISSING", "上游缺少可验证的商品身份")
    if item_id not in identities or len(sku_ids) > 1:
        raise ProductDataError("UPSTREAM_IDENTITY_MISMATCH", "上游商品或 SKU 身份与请求不一致")
    return product
