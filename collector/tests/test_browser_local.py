"""Opt-in real Chromium tests, using loopback HTTP only (never Xiaohongshu)."""

import asyncio
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

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
ERROR_RAW = b' { "success" : false, "error_code":123, "msg":"synthetic SECRET" }\n'


@pytest.fixture
def local_site(monkeypatch):
    received = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            received.append(self.path)
            path = urlsplit(self.path).path
            if path in ("/short", "/second", "/evil", "/restricted"):
                self.send_response(302)
                target = {
                    "/short": f"/goods-detail/{ID}?xsec_token=LOCAL_SENTINEL&share_channel=test",
                    "/restricted": f"/goods-detail/{ID}?test_response_status=461",
                    "/second": "/evil",
                    "/evil": "/blocked",
                }[path]
                self.send_header("Location", target)
                self.end_headers()
            elif path == "/api":
                status = int(parse_qs(urlsplit(self.path).query).get("status", ["200"])[0])
                body = RAW if status == 200 else ERROR_RAW
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                status = parse_qs(urlsplit(self.path).query).get("test_response_status", ["200"])[0]
                html = (
                    "<html><body>Local product<script>"
                    'window.prior = localStorage.getItem("probe");'
                    'localStorage.setItem("probe", "stored");'
                    'document.cookie = "local_session=isolated";'
                    f'fetch("/api?item_id={ID}&status={status}")</script></body></html>'
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
            parts.path in {"/short", "/second", "/evil", "/restricted"}
            or parts.path == f"/goods-detail/{ID}"
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
    assert f"/api?item_id={ID}&status=200" in received


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

    with TestClient(create_app(browser_manager_factory=lambda: manager)) as client:
        browser = manager._browser
        first = client.post("/api/v1/xhs/products/collect", json={"input": short})
        second = client.post("/api/v1/xhs/products/collect", json={"input": ID})
        assert first.status_code == second.status_code == 200
        assert first.json()["item_id"] == second.json()["item_id"] == ID
        assert "raw_body" not in first.json() and "LOCAL_SENTINEL" not in first.text
        assert manager._browser is browser and browser.contexts == []
        assert client.get("/health").json() == {"status": "ok"}
    assert manager.state == "stopped" and not browser.is_connected()
    assert len(contexts) == 2 and contexts[0] is not contexts[1]
    assert previous_storage == [None, None]


def test_real_chromium_461_restriction_closes_context_and_latches_manager(local_site, monkeypatch):
    base, received = local_site
    short = "https://xhslink.com/m/localrestricted"
    monkeypatch.setattr(module, "navigation_input", lambda raw: (base + "/restricted", None))
    manager = BrowserManager(BrowserConfig(navigation_timeout=10, response_timeout=5))
    with TestClient(create_app(browser_manager_factory=lambda: manager)) as client:
        browser = manager._browser
        response = client.post("/api/v1/xhs/products/collect", json={"input": short})
        assert response.status_code == 503
        assert response.json()["code"] == "BROWSER_ACCESS_RESTRICTED"
        assert manager.state == "restricted" and browser.contexts == []
        before = list(received)
        followup = client.post("/api/v1/xhs/products/collect", json={"input": ID})
        assert followup.status_code == 503 and received == before
        assert client.get("/health").json() == {"status": "ok"}
    assert not browser.is_connected()


def test_real_chromium_error_body_and_atomic_export_are_exact(local_site, tmp_path):
    import hashlib
    import json

    from xhs.browser_probe import write_error_output

    base, received = local_site
    collector = BrowserProductCollector(navigation_timeout=10, response_timeout=5)
    with pytest.raises(BrowserCollectionError) as error:
        asyncio.run(collector.collect(base + "/restricted"))
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    diagnostic = error.value.upstream_diagnostic
    assert diagnostic.capture_status == "captured"
    assert diagnostic.raw_body == ERROR_RAW and diagnostic.business_code == 123
    assert diagnostic.business_message == "synthetic SECRET"
    output = tmp_path / "probe"
    write_error_output(output, diagnostic, error.value.code)
    directory = output / f"error-{diagnostic.collection_id}"
    assert (directory / "upstream-error.bin").read_bytes() == ERROR_RAW
    metadata = json.loads((directory / "diagnostic.json").read_bytes())
    assert metadata["body_sha256"] == hashlib.sha256(ERROR_RAW).hexdigest()
    assert "SECRET" not in json.dumps(metadata)
