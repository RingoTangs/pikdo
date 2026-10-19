"""Legacy HTTPX detail client remains covered for collect-examples, independently of API."""

import asyncio
import json
from decimal import Decimal

import httpx
import pytest
from conftest import ITEM_ID

from xhs.client import UPSTREAM_URL, XhsClient
from xhs.errors import CollectionError


def fetch(handler):
    async def exercise():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            return await XhsClient(http).fetch_product(ITEM_ID)

    return asyncio.run(exercise())


def test_httpx_success_and_request_contract(payload):
    calls = []

    def handler(request):
        calls.append(request)
        assert str(request.url).split("?")[0] == UPSTREAM_URL
        assert dict(request.url.params) == {"version": "0.0.5", "item_id": ITEM_ID}
        return httpx.Response(200, content=json.dumps(payload, default=str))

    assert fetch(handler).price.current == Decimal("9.99")
    assert len(calls) == 1


@pytest.mark.parametrize(
    "status,expected,code",
    [
        (401, 503, "UPSTREAM_ACCESS_RESTRICTED"),
        (403, 503, "UPSTREAM_ACCESS_RESTRICTED"),
        (429, 503, "UPSTREAM_ACCESS_RESTRICTED"),
        (461, 503, "UPSTREAM_ACCESS_RESTRICTED"),
        (500, 502, "UPSTREAM_HTTP_ERROR"),
        (404, 502, "UPSTREAM_HTTP_ERROR"),
        (302, 502, "UPSTREAM_HTTP_ERROR"),
    ],
)
def test_http_errors(status, expected, code):
    with pytest.raises(CollectionError) as error:
        fetch(lambda request: httpx.Response(status))
    assert (error.value.status_code, error.value.code) == (expected, code)


@pytest.mark.parametrize(
    "exception,expected,code",
    [
        (httpx.ReadTimeout, 504, "UPSTREAM_TIMEOUT"),
        (httpx.ConnectTimeout, 504, "UPSTREAM_TIMEOUT"),
        (httpx.ConnectError, 502, "UPSTREAM_REQUEST_FAILED"),
    ],
)
def test_transport_errors(exception, expected, code):
    def handler(request):
        raise exception("SECRET", request=request)

    with pytest.raises(CollectionError) as error:
        fetch(handler)
    assert (error.value.status_code, error.value.code) == (expected, code)
    assert "SECRET" not in str(error.value)


@pytest.mark.parametrize(
    "payload,status,code",
    [
        ({"success": False, "msg": "unknown"}, 502, "UPSTREAM_BUSINESS_ERROR"),
        ({"success": False, "msg": "请完成安全验证"}, 503, "UPSTREAM_ACCESS_RESTRICTED"),
        ({"success": False, "msg": "商品不存在"}, 404, "PRODUCT_UNAVAILABLE"),
        ({"success": False, "msg": "当前商品已下架"}, 404, "PRODUCT_UNAVAILABLE"),
        ({"success": True, "data": {"template_data": []}}, 502, "UPSTREAM_INVALID_DATA"),
        ({"success": True, "data": {"template_data": [None]}}, 502, "UPSTREAM_INVALID_DATA"),
    ],
)
def test_business_errors(payload, status, code):
    with pytest.raises(CollectionError) as error:
        fetch(lambda request: httpx.Response(200, json=payload))
    assert (error.value.status_code, error.value.code) == (status, code)


def test_invalid_json():
    with pytest.raises(CollectionError) as error:
        fetch(lambda request: httpx.Response(200, text="<html>error</html>"))
    assert error.value.code == "UPSTREAM_INVALID_JSON"
