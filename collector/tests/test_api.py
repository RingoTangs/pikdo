"""Product API tests inject a collection service, never HTTPX product responses."""

import tomllib
from pathlib import Path

import pytest
from conftest import ITEM_ID, FakeCollectionService, offline_app
from fastapi.testclient import TestClient

from app import COLLECT_PATH, create_app
from xhs.browser_collector import BrowserCollectionError
from xhs.errors import BROWSER_HTTP_ERRORS

VERSION = tomllib.loads((Path(__file__).parents[1] / "pyproject.toml").read_text())["project"][
    "version"
]


def test_success_and_health(payload):
    service = FakeCollectionService(payload=payload)
    with TestClient(offline_app(service=service)) as client:
        assert service.started
        assert client.get("/health").json() == {"status": "ok"}
        assert service.calls == []
        assert client.get("/docs").status_code == 200
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID.upper()})
        assert response.status_code == 200
        result = response.json()
        assert result["item_id"] == ITEM_ID
        assert result["price"]["current"] == "9.99"
        assert result["sales"]["estimated_count"] == 23000
        assert result["sales"]["exact"] is False
        assert result["fetched_at"].endswith("+08:00")
        assert client.post(COLLECT_PATH, json={"input": ITEM_ID}).status_code == 200
        assert service.calls == [ITEM_ID.upper(), ITEM_ID]
    assert service.stopped


@pytest.mark.parametrize("item_id", ["bad", "g" * 24, "a" * 23, "a" * 25])
def test_invalid_id(item_id):
    service = FakeCollectionService()
    with TestClient(offline_app(service=service)) as client:
        response = client.post(COLLECT_PATH, json={"input": item_id})
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_PRODUCT_INPUT"
    assert set(response.json()) == {"code", "message"}
    assert service.calls == []


@pytest.mark.parametrize("code", list(BROWSER_HTTP_ERRORS))
def test_browser_error_mapping_and_no_sensitive_details(code):
    service = FakeCollectionService()
    service.error = BrowserCollectionError(code, "SECRET Cookie xsec_token=SECRET")
    with TestClient(offline_app(service=service)) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
        assert client.get("/health").json() == {"status": "ok"}
    assert response.status_code == BROWSER_HTTP_ERRORS[code][0]
    assert response.json()["code"] == code
    assert set(response.json()) == {"code", "message"}
    assert "SECRET" not in response.text


@pytest.mark.parametrize(
    "value",
    [
        ITEM_ID,
        f"https://www.xiaohongshu.com/goods-detail/{ITEM_ID}?xsec_token=SECRET",
        "https://xhslink.com/m/4tQFfvENODH?xsec_token=SECRET",
        "【小红书】商品 https://xhslink.com/m/4tQFfvENODH?xsec_token=SECRET 点击查看",
    ],
)
def test_post_preserves_original_input_and_returns_product_only(value):
    service = FakeCollectionService()
    with TestClient(offline_app(service=service)) as client:
        response = client.post(COLLECT_PATH, json={"input": value})
    assert response.status_code == 200
    assert response.json()["item_id"] == ITEM_ID
    assert service.calls == [value]
    assert all(
        key not in response.json() for key in ("raw_body", "collection_method", "http_status")
    )
    assert "SECRET" not in response.text and "xsec_token" not in response.text


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"input": ""},
        {"input": 123},
        {"input": None},
        {"input": []},
        {"input": "a" * 8193},
        {"input": ITEM_ID, "secret": "SECRET"},
        {"input": "https://127.0.0.1/?xsec_token=SECRET"},
        {"input": "https://xhslink.com/m/one https://xhslink.com/m/two?token=SECRET"},
        {"input": "没有链接 SECRET"},
    ],
)
def test_post_invalid_input_is_safe_before_collection(body):
    service = FakeCollectionService()
    with TestClient(offline_app(service=service)) as client:
        response = client.post(COLLECT_PATH, json=body)
    assert response.status_code == 422
    assert set(response.json()) == {"code", "message"}
    assert "SECRET" not in response.text and service.calls == []


def test_post_invalid_json_is_safe():
    with TestClient(offline_app()) as client:
        response = client.post(COLLECT_PATH, content=b'{"input":"xsec_token=SECRET",')
    assert response.status_code == 422 and "SECRET" not in response.text


def test_actual_decoder_business_and_json_errors(payload):
    for body, expected, code in [
        (b"not json SECRET", 502, "BROWSER_INVALID_RESPONSE"),
        (b'{"success":false}', 502, "BROWSER_INVALID_RESPONSE"),
        ('{"success":false,"msg":"商品已下架"}'.encode(), 404, "BROWSER_PRODUCT_UNAVAILABLE"),
        ('{"success":false,"msg":"安全验证 SECRET"}'.encode(), 503, "BROWSER_ACCESS_RESTRICTED"),
    ]:
        with TestClient(offline_app(service=FakeCollectionService(body=body))) as client:
            response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
        assert response.status_code == expected and response.json()["code"] == code
        assert "SECRET" not in response.text


def test_delisted_button(payload):
    payload["data"]["template_data"][0]["bottomBarMainH5"]["buttons"] = [
        {"type": "unBuyableGoShop"}
    ]
    with TestClient(offline_app(service=FakeCollectionService(payload=payload))) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
    assert response.status_code == 404 and response.json()["code"] == "BROWSER_PRODUCT_UNAVAILABLE"


def test_startup_failure_fails_fast_and_stops_service():
    class FailedService(FakeCollectionService):
        async def start(self):
            raise BrowserCollectionError("BROWSER_LAUNCH_FAILED", "browser unavailable")

    service = FailedService()
    with pytest.raises(BrowserCollectionError):
        with TestClient(create_app(browser_manager_factory=lambda: service)):
            pytest.fail("Lifespan must not yield after startup failure")
    assert service.stopped


def test_missing_sales_remains_null(payload):
    del payload["data"]["template_data"][0]["priceH5"]["itemAnalysisDataText"]
    with TestClient(offline_app(service=FakeCollectionService(payload=payload))) as client:
        result = client.post(COLLECT_PATH, json={"input": ITEM_ID}).json()
    assert result["sales"] == {"display": None, "estimated_count": None, "exact": None}


def test_v02_api_serialization(payload):
    with TestClient(offline_app(service=FakeCollectionService(payload=payload))) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
        assert client.get("/openapi.json").json()["info"]["version"] == VERSION
    assert response.status_code == 200
    result = response.json()
    assert result["price"] == {"current": "9.99", "display": "19.99", "currency": "CNY"}
    assert result["shop"]["grade"] == "4.0"
    assert result["shop"]["seller_score"] == "4.20"
    assert result["shop"]["sold_exact"] is False
    assert result["pricing_details"]["bottom_price_raw"] == 999
    assert result["pricing_details"]["formula"]["steps"][0]["price"] == "19.99"
    assert len(result["media"]["images"]) == 6
    assert len(result["media"]["detail_images"]) == 9
    assert result["shipping"]["location"] == "广东潮州"
    assert result["shipping"]["fee"] == "包邮"
    assert result["variants"]["sku_num"] == 6
    assert len(result["services"]["items"]) == 3
    assert len(result["purchase"]["buttons"]) == 2
    assert result["metadata"]["template_version"] == "75"
    assert "template" not in result and "template_data" not in result


def test_v02_api_filters_sensitive_business_json(payload):
    data = payload["data"]["template_data"][0]
    data["descriptionMain"]["tags"] = [
        {
            "name": "business",
            "xsec_token": "SECRET_TOKEN",
            "x-s": "SECRET_SIGNATURE",
            "x-t": "SECRET_TIMESTAMP",
            "share_id": "SECRET_SHARE",
            "trackers": {"event": "SECRET_EVENT"},
            "url": "//example.com/image?imageView2/2/w/1080&xsec_token=SECRET_TOKEN&sign=SECRET_SIGN&ap=3",
        },
        '{"name":"nested","authorization":"SECRET_AUTH"}',
    ]
    data["bottomBarMainH5"]["couponInfos"] = [{"name": "coupon", "cookie": "SECRET_COOKIE"}]
    data["carouselH5"]["images"][0]["media"] = {
        "url": "https://example.com/a?utm_source=SECRET_TRACE",
        "tracking": "SECRET_EVENT",
    }
    data["sellerH5"]["link"] = "https://example.com/shop?xsec_token=SECRET_TOKEN"
    with TestClient(offline_app(service=FakeCollectionService(payload=payload))) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
    assert response.status_code == 200
    assert "SECRET_" not in response.text
    result = response.json()
    assert result["basic_info"]["product_tags"][0] == {
        "name": "business",
        "url": "https://example.com/image?imageView2/2/w/1080&ap=3",
    }
    assert result["basic_info"]["product_tags"][1] == {"name": "nested"}
    assert result["purchase"]["coupon_infos"] == [{"name": "coupon"}]


def test_v02_numeric_json_precision():
    body = (
        '{"success":true,"data":{"template_data":[{"descriptionMain":{"name":"Test","itemId":"6ab10b35d528ea0015dd1b7e"},'
        '"priceH5":{"dealPrice":{"price":9.99000000000000000000000001}}}]}}'
    )
    with TestClient(offline_app(service=FakeCollectionService(body=body.encode()))) as client:
        result = client.post(COLLECT_PATH, json={"input": ITEM_ID}).json()
    assert result["price"]["current"] == "9.99000000000000000000000001"
    assert result["pricing_details"]["deal_price"]["price"] == "9.99000000000000000000000001"


def test_api_excludes_sensitive_parameters_from_original_fixture(payload):
    with TestClient(offline_app(service=FakeCollectionService(payload=payload))) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
    assert response.status_code == 200
    assert "headerBarMainPopup" not in response.json()
    assert "xsec_token" not in response.text.lower()
    assert "instation_link" not in response.text.lower()
    assert "trackersComponent" not in response.text
    for service in response.json()["services"]["popup_items"]:
        assert "sign=" not in (service["icon"] or "")


def test_unknown_browser_error_does_not_echo_code_or_message():
    service = FakeCollectionService()
    service.error = BrowserCollectionError("SECRET_CODE", "Cookie=SECRET")
    with TestClient(offline_app(service=service)) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
    assert response.status_code == 502
    assert response.json()["code"] == "BROWSER_NAVIGATION_FAILED"
    assert "SECRET" not in response.text


def test_only_two_business_api_paths_and_current_models():
    service = FakeCollectionService()
    application = offline_app(service=service)
    with TestClient(application) as client:
        spec = client.get("/openapi.json").json()
        assert spec["info"]["version"] == VERSION
        assert set(spec["paths"]) == {"/health", COLLECT_PATH}
        assert set(spec["paths"]["/health"]) == {"get"}
        assert set(spec["paths"][COLLECT_PATH]) == {"post"}
        operation = spec["paths"][COLLECT_PATH]["post"]
        assert operation["requestBody"]["content"]["application/json"]["schema"] == {
            "$ref": "#/components/schemas/CollectRequest"
        }
        assert operation["responses"]["200"]["content"]["application/json"]["schema"] == {
            "$ref": "#/components/schemas/Product"
        }
        for status in (422, 404, 502, 503, 504):
            assert operation["responses"][str(status)]["content"]["application/json"]["schema"] == {
                "$ref": "#/components/schemas/CollectErrorResponse"
            }
        schemas = spec["components"]["schemas"]
        request = schemas["CollectRequest"]
        assert request["required"] == ["input"]
        assert request["additionalProperties"] is False
        assert request["properties"]["input"]["type"] == "string"
        assert request["properties"]["input"]["minLength"] == 1
        assert request["properties"]["input"]["maxLength"] == 8192
        assert "ErrorResponse" not in schemas
        assert not any(name.startswith("Resolve") for name in schemas)
        assert not any(
            route.path == "/api/v1/xhs/products/{item_id}" for route in application.routes
        )
        assert client.get(f"/api/v1/xhs/products/{ITEM_ID}").status_code == 404
        response = client.post("/api/v1/xhs/products/resolve", json={"input": "SECRET"})
        assert response.status_code == 404 and "SECRET" not in response.text
        assert client.get(COLLECT_PATH).status_code == 405
        assert not hasattr(client.app.state, "resolver") and not hasattr(client.app.state, "xhs")
        assert service.calls == []


def test_461_plus_cleanup_failure_returns_safe_503_and_blocks_followups(caplog):
    from playwright.async_api import Error as PlaywrightError
    from test_browser_manager import build

    manager, pw = build(cleanup_timeout=0.01)
    original_create = pw.browser.new_context

    async def create():
        context = await original_create()
        context.page.responses[0].status = 461
        context.close_error = PlaywrightError(
            "https://xhslink.com/m/test?token=SECRET Cookie=SECRET"
        )
        return context

    pw.browser.new_context = create
    with TestClient(create_app(browser_manager_factory=lambda: manager)) as client:
        for _ in range(2):
            response = client.post(
                COLLECT_PATH, json={"input": "https://xhslink.com/m/test?token=SECRET"}
            )
            assert response.status_code == 503
            assert response.json()["code"] == "BROWSER_ACCESS_RESTRICTED"
            assert set(response.json()) == {"code", "message"}
            assert "SECRET" not in response.text
        assert client.get("/health").json() == {"status": "ok"}
        assert len(pw.browser.contexts) == 1 and pw.browser.close_calls == 1
        assert manager.state == "restricted"
    assert pw.stopped and "SECRET" not in caplog.text


def test_upstream_diagnostic_never_changes_public_error_shape(caplog):
    from xhs.upstream_diagnostics import extract_upstream_error

    service = FakeCollectionService()
    diagnostic = extract_upstream_error(b'{"code":"SECRET","msg":"Cookie=SECRET"}', 461)
    service.error = BrowserCollectionError(
        "BROWSER_ACCESS_RESTRICTED", "safe", upstream_diagnostic=diagnostic
    )
    with TestClient(offline_app(service=service)) as client:
        response = client.post(COLLECT_PATH, json={"input": ITEM_ID})
    assert response.status_code == 503
    assert response.json() == {
        "code": "BROWSER_ACCESS_RESTRICTED",
        "message": BROWSER_HTTP_ERRORS["BROWSER_ACCESS_RESTRICTED"][1],
    }
    assert "SECRET" not in response.text + caplog.text
