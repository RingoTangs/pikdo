"""Offline product resolution tests. All network access is intercepted."""

import logging

import httpx
import pytest
from conftest import offline_app
from fastapi.testclient import TestClient

from xhs.errors import ResolveError
from xhs.resolver import RESOLVE_PATH, parse_input, preprocess_input, validate_url

ITEM_ID = "6ab10b35d528ea0015dd1b7e"
OTHER_ID = "6ab10b35d528ea0015dd1b7d"
H5 = f"https://www.xiaohongshu.com/goods-detail/{ITEM_ID}"
SHORT = "https://xhslink.com/m/9izjN4oPZd4"
SHARE = f"【小红书】【前3000单必出盘】 😆 3ukhXCGSwNE 😆 {SHORT} 点击链接或者复制本条信息打开【小红书app】查看 CZ6125"


def no_network(request: httpx.Request) -> httpx.Response:
    pytest.fail("Local parsing must not send HTTP requests")


def expected(source: str) -> dict[str, str]:
    return {"source_type": source, "item_id": ITEM_ID, "canonical_url": H5}


@pytest.mark.parametrize(
    "value,source",
    [
        (ITEM_ID, "direct_id"),
        (ITEM_ID.upper(), "direct_id"),
        (f"  {ITEM_ID}\n", "direct_id"),
        (H5, "h5_url"),
        (H5.replace("www.", ""), "h5_url"),
        (H5 + "/", "h5_url"),
        (H5 + "?xsec_token=SENTINEL_TOKEN", "h5_url"),
        (H5 + "#section", "h5_url"),
        (H5.replace(ITEM_ID, ITEM_ID.upper()), "h5_url"),
        (H5.replace("https://www.", "HTTPS://WWW."), "h5_url"),
        (H5.replace(".com/", ".com:443/"), "h5_url"),
        (f"[商品]({H5})", "share_text"),
        (f"<{H5}>", "share_text"),
        (f"({H5})", "share_text"),
        (f"查看：{H5}。", "share_text"),
        (f"😆 商品\n  {H5}\n 点击查看", "share_text"),
    ],
)
def test_local_inputs_do_not_access_network(value: str, source: str) -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.status_code == 200
    assert response.json() == expected(source)


@pytest.mark.parametrize(
    "value,source",
    [
        (SHORT, "short_url"),
        (SHORT + " ", "short_url"),
        (SHARE, "share_text"),
        (SHARE.replace("https://", r"https\://") + "&#x20;", "share_text"),
        (SHARE.replace("https://", r"https:\/\/").replace("/m/", r"\/m\/"), "share_text"),
        (SHARE.replace(" 点击", "\n\n   点击") + "   ", "share_text"),
        (f"[分享商品]({SHORT})", "share_text"),
        (f"<{SHORT}>", "share_text"),
        (f"（{SHORT}）", "share_text"),
        (f"分享：{SHORT}，点击查看。", "share_text"),
        (SHARE.replace(SHORT, SHORT + "?a=1&amp;b=2"), "share_text"),
        (SHORT.replace("xhslink.com", "www.xhslink.com"), "short_url"),
    ],
)
def test_short_and_share_variants(value: str, source: str) -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.url.host in {"xhslink.com", "www.xhslink.com"}
        assert request.url.path == "/m/9izjN4oPZd4"
        assert not request.url.query
        return httpx.Response(302, headers={"Location": H5})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.status_code == 200
    assert response.json() == expected(source)
    assert len(requests) == 1


@pytest.mark.parametrize(
    "value",
    [
        "a" * 23,
        "a" * 25,
        "g" * 24,
        "",
        "   ",
        "x" * 8193,
        f"http://www.xiaohongshu.com/goods-detail/{ITEM_ID}",
        f"https://www.xiaohongshu.com.evil.test/goods-detail/{ITEM_ID}",
        f"https://xiaohongshu.com@evil.test/goods-detail/{ITEM_ID}",
        f"https://user:pass@www.xiaohongshu.com/goods-detail/{ITEM_ID}",
        H5.replace(".com/", ".com:8000/"),
        H5.replace(".com/", ".com:invalid/"),
        H5.replace(".com/", ".com./"),
        H5.replace("www.xiaohongshu.com", "127.0.0.1"),
        "https://localhost/m/a",
        "https://[::1]/m/a",
        "https://10.0.0.1/m/a",
        "https://169.254.169.254/m/a",
        "https://2130706433/m/a",
        "https://xhslink.com.evil.test/m/a",
        "https://evil.test@xhslink.com/m/a",
        "https://xhslink.com:80/m/a",
        "https://xhslink.com:444/m/a",
        "https://xhslink%2ecom/m/a",
        "https://xhslink.com\\@evil.test/m/a",
        r"https\://evil.test/m/a",
        "https://xhslink.com\x00/m/a",
    ],
)
def test_invalid_inputs_fail_before_network(value: str) -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_PRODUCT_INPUT"
    assert set(response.json()) == {"code", "message"}


@pytest.mark.parametrize(
    "value",
    [
        f"https://www.xiaohongshu.com/explore/{ITEM_ID}",
        H5 + "/extra",
        H5.replace(ITEM_ID, "g" * 24),
        f"https://www.xiaohongshu.com/?item_id={ITEM_ID}",
        "https://xhslink.com/",
        "https://xhslink.com/a/b/c",
        "https://xhslink.com/m/%2e%2e",
    ],
)
def test_non_product_links(value: str) -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.status_code == 422
    assert response.json()["code"] == "NOT_PRODUCT_LINK"


@pytest.mark.parametrize("value", ["没有链接 😆 CZ6125", f"追踪代码 {ITEM_ID}", "商品分享&#x20;"])
def test_missing_link_does_not_search_for_id(value: str) -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.status_code == 422
    assert response.json()["code"] == "PRODUCT_LINK_NOT_FOUND"


@pytest.mark.parametrize(
    "value",
    [
        f"{H5} {H5.replace(ITEM_ID, OTHER_ID)}",
        f"{SHORT} https://xhslink.com/m/different",
        f"{H5} {SHORT}",
    ],
)
def test_ambiguous_links_fail_before_network(value: str) -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.status_code == 422
    assert response.json()["code"] == "AMBIGUOUS_PRODUCT_INPUT"


def test_repeated_equivalent_h5_links_are_not_conflicts() -> None:
    value = f"{H5}?xsec_token=one {H5.replace('www.', '')}/#other"
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json={"input": value})
    assert response.json() == expected("share_text")


@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
def test_redirect_extracts_only_path_id_without_h5_request(status: int) -> None:
    requests = []
    location = (
        H5
        + f"?xsec_token=SENTINEL_TOKEN&instation_link=https%3A%2F%2Fwww.xiaohongshu.com%2Fgoods-detail%2F{OTHER_ID}&trade_ext={OTHER_ID}#fragment"
    )

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.url.host == "xhslink.com"
        return httpx.Response(status, headers={"Location": location})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.json() == expected("short_url")
    assert "SENTINEL_TOKEN" not in response.text
    assert len(requests) == 1


def test_h5_nested_urls_are_not_rescanned() -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(
            RESOLVE_PATH,
            json={"input": H5 + f"?nested=https://www.xiaohongshu.com/goods-detail/{OTHER_ID}"},
        )
    assert response.json() == expected("h5_url")


@pytest.mark.parametrize(
    "location",
    ["/m/second", "second", "https://www.xhslink.com/m/second", "//www.xhslink.com/m/second"],
)
def test_trusted_relative_and_absolute_short_redirects(location: str) -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.url.host in {"xhslink.com", "www.xhslink.com"}
        return httpx.Response(302, headers={"Location": location if len(requests) == 1 else H5})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.json() == expected("short_url")
    assert len(requests) == 2


@pytest.mark.parametrize("length,success", [(3, True), (4, False)])
def test_redirect_budget(length: int, success: bool) -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        location = H5 if len(requests) == length else f"/m/hop{len(requests)}"
        return httpx.Response(302, headers={"Location": location})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert len(requests) == 3
    assert response.status_code == (200 if success else 502)
    if success:
        assert response.json() == expected("short_url")
    else:
        assert response.json()["code"] == "SHORT_URL_REDIRECT_LIMIT"


def test_redirect_cycle_is_not_repeated() -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(302, headers={"Location": SHORT})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.json()["code"] == "SHORT_URL_REDIRECT_LIMIT"
    assert len(requests) == 1


@pytest.mark.parametrize(
    "location",
    [
        "https://evil.test/m/a",
        "http://xhslink.com/m/a",
        "//evil.test/m/a",
        "https://127.0.0.1/m/a",
        "https://10.0.0.1/m/a",
        "https://[::1]/m/a",
        "https://localhost/m/a",
        "https://169.254.169.254/m/a",
        "https://user:pass@xhslink.com/m/a",
        "https://xhslink.com:8080/m/a",
        "https://xhslink.com.evil.test/m/a",
        "https://xhslink.com\\@evil.test/m/a",
        "javascript:alert(1)",
        "https://xhslink.com\t.evil.test/m/a",
    ],
)
def test_untrusted_redirect_is_never_requested(location: str) -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(302, headers={"Location": location})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.status_code == 502
    assert response.json()["code"] == "UNTRUSTED_REDIRECT_TARGET"
    assert len(requests) == 1


@pytest.mark.parametrize(
    "status,headers,code,http_status",
    [
        (302, {}, "SHORT_URL_MISSING_LOCATION", 502),
        (302, {"Location": " "}, "SHORT_URL_MISSING_LOCATION", 502),
        (
            302,
            {"Location": f"https://www.xiaohongshu.com/explore/{ITEM_ID}"},
            "NOT_PRODUCT_LINK",
            422,
        ),
        (200, {}, "SHORT_URL_HTML_UNSUPPORTED", 502),
        (401, {}, "SHORT_URL_ACCESS_RESTRICTED", 503),
        (403, {}, "SHORT_URL_ACCESS_RESTRICTED", 503),
        (429, {}, "SHORT_URL_ACCESS_RESTRICTED", 503),
        (461, {}, "SHORT_URL_ACCESS_RESTRICTED", 503),
        (404, {}, "SHORT_URL_HTTP_ERROR", 502),
        (500, {}, "SHORT_URL_HTTP_ERROR", 502),
    ],
)
def test_short_http_errors(
    status: int, headers: dict[str, str], code: str, http_status: int
) -> None:
    with TestClient(
        offline_app(
            httpx.MockTransport(
                lambda request: httpx.Response(
                    status, headers=headers, text="<html>verification</html>"
                )
            )
        )
    ) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.status_code == http_status
    assert response.json()["code"] == code
    assert set(response.json()) == {"code", "message"}


@pytest.mark.parametrize(
    "error,status,code",
    [
        (httpx.ReadTimeout, 504, "SHORT_URL_TIMEOUT"),
        (httpx.ConnectTimeout, 504, "SHORT_URL_TIMEOUT"),
        (httpx.ConnectError, 502, "SHORT_URL_CONNECTION_FAILED"),
    ],
)
def test_short_transport_errors(error: type[httpx.RequestError], status: int, code: str) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise error("SENTINEL_TOKEN", request=request)

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.status_code == status
    assert response.json()["code"] == code
    assert "SENTINEL_TOKEN" not in response.text


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"input": None},
        {"input": 123},
        {"input": []},
        {"input": "x" * 8193},
        {"input": SHORT, "token": "SENTINEL_TOKEN"},
    ],
)
def test_request_validation_does_not_echo_input(body: dict) -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(RESOLVE_PATH, json=body)
    assert response.status_code == 422
    assert set(response.json()) == {"code", "message"}
    assert response.json()["code"] == "INVALID_PRODUCT_INPUT"
    assert "SENTINEL_TOKEN" not in response.text


def test_invalid_json_request_is_sanitized() -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        response = client.post(
            RESOLVE_PATH,
            content='{"input":"SENTINEL_TOKEN",',
            headers={"content-type": "application/json"},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_PRODUCT_INPUT"
    assert "SENTINEL_TOKEN" not in response.text


def test_resolver_reuses_client_and_is_documented() -> None:
    with TestClient(offline_app(httpx.MockTransport(no_network))) as client:
        assert client.app.state.resolver.client is client.app.state.xhs
        spec = client.get("/openapi.json").json()
        assert "post" in spec["paths"][RESOLVE_PATH]
        assert spec["info"]["version"] == "0.4.0a1"
        assert client.get("/docs").status_code == 200
        assert client.get("/health").json() == {"status": "ok"}
        assert client.get("/api/v1/xhs/products/invalid").json()["item_id"] == "invalid"


def test_token_is_absent_from_response_and_logs(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO)

    def handler(request: httpx.Request) -> httpx.Response:
        assert not request.url.query
        return httpx.Response(302, headers={"Location": H5 + "?xsec_token=SENTINEL_TOKEN"})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHARE + "?xsec_token=SENTINEL_TOKEN"})
    assert response.status_code == 200
    assert "SENTINEL_TOKEN" not in response.text
    assert "SENTINEL_TOKEN" not in caplog.text


def test_preprocessing_is_finite_and_does_not_trust_unknown_domains() -> None:
    assert preprocess_input(r"https\://evil.test/m/a&#x20;") == "https://evil.test/m/a"
    assert preprocess_input("&amp;amp;") == "&amp;"
    with pytest.raises(ResolveError):
        parse_input("https://evil.test/m/a")
    with pytest.raises(ResolveError):
        parse_input("https%3A%2F%2Fxhslink.com%2Fm%2Fa")
    with pytest.raises(ResolveError):
        validate_url("https://xhslink.com\n.evil.test/m/a")


@pytest.mark.parametrize(
    "location", ["https:///m/next", "https:m/next", "///m/next", "https://[invalid/m/a"]
)
def test_malformed_absolute_location_is_not_joined_into_trusted_host(location: str) -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(302, headers={"Location": location})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.status_code == 502
    assert response.json()["code"] == "UNTRUSTED_REDIRECT_TARGET"
    assert len(calls) == 1


def test_untrusted_second_hop_is_not_requested() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        assert request.url.host == "xhslink.com"
        return httpx.Response(
            302,
            headers={"Location": "/m/second" if len(calls) == 1 else "https://evil.test/m/third"},
        )

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": SHORT})
    assert response.json()["code"] == "UNTRUSTED_REDIRECT_TARGET"
    assert len(calls) == 2


def test_repeated_short_links_resolve_once() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(302, headers={"Location": H5})

    with TestClient(offline_app(httpx.MockTransport(handler))) as client:
        response = client.post(RESOLVE_PATH, json={"input": f"{SHORT} {SHORT}?xsec_token=fake"})
    assert response.json() == expected("share_text")
    assert len(calls) == 1
