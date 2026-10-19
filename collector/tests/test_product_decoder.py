"""Pure decoder semantics remain compatible with legacy HTTPX and archive callers."""

import ast
import json
from decimal import Decimal
from pathlib import Path

import pytest
from conftest import ITEM_ID

from xhs import client, product_decoder
from xhs.errors import CollectionError
from xhs.product_decoder import decode_product_body


def body(*, item_id=ITEM_ID, sku_id=None, price="9.99000000000000000000001"):
    main = {"name": "商品"}
    if item_id is not None:
        main["itemId"] = item_id
    data = {"descriptionMain": main, "priceH5": {"dealPrice": {"price": "PRICE"}}}
    if sku_id is not None:
        data["descriptionH5"] = {"skuId": sku_id}
    return (
        json.dumps({"success": True, "data": {"template_data": [data]}})
        .replace('"PRICE"', price)
        .encode()
    )


def test_decimal_bytes_and_legacy_exports():
    raw = body()
    before = bytes(raw)
    assert decode_product_body(raw, ITEM_ID).price.current == Decimal("9.99000000000000000000001")
    assert raw == before
    assert client.decode_product_body is product_decoder.decode_product_body
    assert client.reject_json_constant is product_decoder.reject_json_constant


def test_legacy_identity_mode_preserved():
    other = "a" * 24
    # The legacy strict decoder accepts a matching SKU; browser's extra check stays stricter.
    assert (
        decode_product_body(body(item_id=other, sku_id=ITEM_ID), ITEM_ID).basic_info.product_item_id
        == other
    )
    assert (
        decode_product_body(body(item_id=None), ITEM_ID, verify_identity=False).item_id == ITEM_ID
    )
    with pytest.raises(CollectionError) as error:
        decode_product_body(body(item_id=None), ITEM_ID)
    assert error.value.code == "UPSTREAM_IDENTITY_MISSING"


@pytest.mark.parametrize(
    "raw,code",
    [
        (b"invalid", "UPSTREAM_INVALID_JSON"),
        (b'{"success":false}', "UPSTREAM_BUSINESS_ERROR"),
        ('{"success":false,"msg":"商品已下架"}'.encode(), "PRODUCT_UNAVAILABLE"),
        ('{"success":false,"msg":"请登录"}'.encode(), "UPSTREAM_ACCESS_RESTRICTED"),
        (body(item_id="bad"), "UPSTREAM_IDENTITY_INVALID"),
        (body(item_id="a" * 24), "UPSTREAM_IDENTITY_MISMATCH"),
        (body(price="NaN"), "UPSTREAM_INVALID_JSON"),
    ],
)
def test_error_semantics(raw, code):
    with pytest.raises(CollectionError) as error:
        decode_product_body(raw, ITEM_ID)
    assert error.value.code == code


def test_nonfinite_legacy_mode_is_unchanged():
    assert (
        decode_product_body(body(price="NaN"), ITEM_ID, verify_identity=False).price.current is None
    )


def test_pure_decoder_has_no_transport_imports():
    tree = ast.parse(Path(product_decoder.__file__).read_text())
    imports = [node.module for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)]
    imports += [
        alias.name
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    ]
    assert not any(
        name and name.startswith(("httpx", "playwright", "xhs.client")) for name in imports
    )
