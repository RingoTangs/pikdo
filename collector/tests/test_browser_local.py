"""Opt-in real Chromium tests, using loopback HTTP only (never Xiaohongshu)."""

import asyncio
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlsplit

import httpx
import pytest
from fastapi.testclient import TestClient

from app import create_app
from xhs import browser_collector as module
from xhs.browser import BrowserConfig, BrowserManager
from xhs.browser_collector import BrowserCollectionError, BrowserProductCollector

pytestmark = pytest.mark.skipif(
    os.environ.get("PIKDO_LOCAL_BROWSER_TESTS") != "1",
    reason="opt-in loopback Chromium test: PIKDO_LOCAL_BROWSER_TESTS=1",
)
ID = "6ab10b35d528ea0015dd1b7e"
RAW = (Path(__file__).parents[1] / "examples/response.json").read_bytes()


@pytest.fixture
def local_site(monkeypatch):
    received = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            received.append(self.path)
            path = urlsplit(self.path).path
            if path in ("/short", "/second", "/evil"):
                self.send_response(302)
                target = {
                    "/short": f"/goods-detail/{ID}?xsec_token=LOCAL_SENTINEL&share_channel=test",
                    "/second": "/evil",
                    "/evil": "/blocked",
                }[path]
                self.send_header("Location", target)
                self.end_headers()
            elif path == "/api":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(RAW)
            else:
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                html = (
                    "<html><body>Local product<script>"
                    'window.prior = localStorage.getItem("probe");'
                    'localStorage.setItem("probe", "stored");'
                    'document.cookie = "local_session=isolated";'
                    f'fetch("/api?item_id={ID}")</script></body></html>'
                )
                self.wfile.write(html.encode())

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    original_target = module.navigation_target
    original_match = module.response_item_id

    def target(url):
        parts = urlsplit(url)
        if url.startswith(base + "/") and (
            parts.path in {"/short", "/second", "/evil"} or parts.path == f"/goods-detail/{ID}"
        ):
            return SimpleNamespace(item_id=ID if parts.path.startswith("/goods-detail/") else None)
        return original_target(url)

    def match(response):
        parts = urlsplit(response.url)
        if response.url.startswith(base + "/") and parts.path == "/api":
            mapped = "https://mall.xiaohongshu.com" + module.DETAIL_PATH + "?" + parts.query
            return original_match(SimpleNamespace(url=mapped, request=response.request))
        return None

    # Test-only loopback trust substitution; production never allows these addresses.
    monkeypatch.setattr(module, "navigation_target", target)
    monkeypatch.setattr(module, "response_item_id", match)
    monkeypatch.setattr(module, "navigation_input", lambda raw: (raw, None))
    try:
        yield base, received
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_real_chromium_redirect_and_page_generated_response(local_site):
    base, received = local_site
    result = asyncio.run(
        BrowserProductCollector(navigation_timeout=10, response_timeout=5).collect(base + "/short")
    )
    assert result.raw_body == RAW and result.item_id == ID
    assert received[0] == "/short"
    assert f"/goods-detail/{ID}?xsec_token=LOCAL_SENTINEL&share_channel=test" in received
    assert f"/api?item_id={ID}" in received


def test_real_chromium_blocks_later_302_hop_before_request(local_site):
    base, received = local_site
    with pytest.raises(BrowserCollectionError) as error:
        asyncio.run(BrowserProductCollector(navigation_timeout=10).collect(base + "/second"))
    assert error.value.code == "BROWSER_UNTRUSTED_REDIRECT"
    assert received == ["/second", "/evil"]


def test_fastapi_shared_browser_and_real_context_storage_isolation(local_site, monkeypatch):
    base, received = local_site
    short = "https://xhslink.com/m/localtest"
    contexts = []
    previous_storage = []

    class InspectingCollector(BrowserProductCollector):
        async def _collect_page(self, context, page, url, expected_id):
            contexts.append(context)
            assert await context.cookies() == []
            result = await super()._collect_page(context, page, url, expected_id)
            previous_storage.append(await page.evaluate("window.prior"))
            return result

    def local_input(raw):
        return (base + "/short", None) if raw == short else (base + f"/goods-detail/{ID}", ID)

    monkeypatch.setattr(module, "navigation_input", local_input)
    manager = BrowserManager(
        BrowserConfig(navigation_timeout=10, response_timeout=5),
        collector_factory=InspectingCollector,
    )

    def deny_httpx(request):
        pytest.fail("Product API integration must not issue HTTPX requests")

    with TestClient(
        create_app(
            transport=httpx.MockTransport(deny_httpx), browser_manager_factory=lambda: manager
        )
    ) as client:
        browser = manager._browser
        first = client.post("/api/v1/xhs/products/collect", json={"input": short})
        second = client.get(f"/api/v1/xhs/products/{ID}")
        assert first.status_code == second.status_code == 200
        assert first.json()["item_id"] == second.json()["item_id"] == ID
        assert "raw_body" not in first.json() and "LOCAL_SENTINEL" not in first.text
        assert manager._browser is browser and browser.contexts == []
        assert client.get("/health").json() == {"status": "ok"}
    assert manager.state == "stopped" and not browser.is_connected()
    assert len(contexts) == 2 and contexts[0] is not contexts[1]
    assert previous_storage == [None, None]
