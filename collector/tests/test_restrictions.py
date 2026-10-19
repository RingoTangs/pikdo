import asyncio
import json

import httpx
import pytest
from conftest import ITEM_ID

from xhs.client import XhsClient
from xhs.errors import CollectionError, ResolveError

PATH = f"/api/v1/xhs/products/{ITEM_ID}"
RESOLVE = "/api/v1/xhs/products/resolve"
SHORT = {"input": "https://xhslink.com/m/example?token=SECRET"}


@pytest.mark.parametrize(
    "status,short_first",
    [(s, short) for s in (401, 403, 429, 461) for short in (False, True)] + [(200, False)],
)
def test_restriction_blocks_both_network_paths(status, short_first, caplog):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(status, json={"success": False, "msg": "请完成安全验证 SECRET"})

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            client = XhsClient(http)
            with pytest.raises((CollectionError, ResolveError)) as first:
                if short_first:
                    await client.fetch_short_redirect("xhslink.com", "/m/example")
                else:
                    await client.fetch_product(ITEM_ID)
            expected = (
                "SHORT_URL_ACCESS_RESTRICTED" if short_first else "UPSTREAM_ACCESS_RESTRICTED"
            )
            assert first.value.code == expected
            errors = [first.value]
            for operation in (
                client.fetch_product(ITEM_ID),
                client.fetch_short_redirect("xhslink.com", "/m/example"),
            ):
                with pytest.raises((CollectionError, ResolveError)) as subsequent:
                    await operation
                assert subsequent.value.code == "UPSTREAM_REQUEST_BLOCKED"
                errors.append(subsequent.value)
            for error in errors:
                assert error.headers["X-Collector-Upstream-Status"] == str(status)
                assert error.headers["X-Collector-Blocked"] == "true"
                assert "SECRET" not in str(error)
            from xhs.resolver import ProductResolver

            resolver = ProductResolver(client)
            for value in (ITEM_ID, f"https://www.xiaohongshu.com/goods-detail/{ITEM_ID}"):
                assert (await resolver.resolve(value)).item_id == ITEM_ID

    asyncio.run(exercise())
    assert len(requests) == 1
    safe_logs = [r.getMessage() for r in caplog.records if r.name == "xhs.client"]
    assert len(safe_logs) == 1
    assert "SECRET" not in safe_logs[0]
    assert "reason=" in safe_logs[0]


def test_queued_requests_do_not_escape_guard():
    async def exercise():
        entered = asyncio.Event()
        release = asyncio.Event()
        calls = []

        async def handler(request):
            calls.append(request)
            entered.set()
            await release.wait()
            return httpx.Response(429)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            client = XhsClient(http)
            first = asyncio.create_task(client.fetch_product(ITEM_ID))
            await entered.wait()
            queued = [
                asyncio.create_task(client.fetch_short_redirect("xhslink.com", "/m/test")),
                asyncio.create_task(client.fetch_product_result(ITEM_ID)),
            ]
            release.set()
            errors = await asyncio.gather(first, *queued, return_exceptions=True)
            assert isinstance(errors[0], CollectionError)
            assert isinstance(errors[1], ResolveError)
            assert [e.code for e in errors] == [
                "UPSTREAM_ACCESS_RESTRICTED",
                "UPSTREAM_REQUEST_BLOCKED",
                "UPSTREAM_REQUEST_BLOCKED",
            ]
            assert len(calls) == 1

    asyncio.run(exercise())


@pytest.mark.parametrize("failure", ["timeout", "connection", "http", "json"])
def test_other_errors_do_not_latch(failure, payload):
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            if failure == "timeout":
                raise httpx.ReadTimeout("SECRET", request=request)
            if failure == "connection":
                raise httpx.ConnectError("SECRET", request=request)
            return httpx.Response(500 if failure == "http" else 200, content=b"invalid")
        return httpx.Response(200, content=json.dumps(payload, default=str))

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            client = XhsClient(http)
            with pytest.raises(CollectionError) as error:
                await client.fetch_product(ITEM_ID)
            assert error.value.status_code in (502, 504)
            assert "X-Collector-Blocked" not in error.value.headers
            assert (await client.fetch_product(ITEM_ID)).item_id == ITEM_ID

    asyncio.run(exercise())
    assert len(calls) == 2


def test_new_httpx_client_resets_local_guard(payload):
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            return httpx.Response(461)
        return httpx.Response(200, content=json.dumps(payload, default=str))

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            first = XhsClient(http)
            with pytest.raises(CollectionError):
                await first.fetch_product(ITEM_ID)
            with pytest.raises(CollectionError) as error:
                await first.fetch_product(ITEM_ID)
            assert error.value.code == "UPSTREAM_REQUEST_BLOCKED"
            assert (await XhsClient(http).fetch_product(ITEM_ID)).item_id == ITEM_ID

    asyncio.run(exercise())
    assert len(calls) == 2
