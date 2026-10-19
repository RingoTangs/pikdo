"""Offline browser lifecycle, navigation policy and response matching tests."""

import asyncio
import json
from collections import defaultdict
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest
from playwright.async_api import Error as PlaywrightError

from xhs import browser_collector as module
from xhs.browser_collector import (
    BrowserCollectionError,
    BrowserProductCollector,
    navigation_input,
    navigation_target,
    response_item_id,
)
from xhs.errors import InputError

ID = "6ab10b35d528ea0015dd1b7e"
OTHER = "6ab10b35d528ea0015dd1b7d"
H5 = f"https://www.xiaohongshu.com/goods-detail/{ID}"
SHORT = "https://xhslink.com/m/4tQFfvENODH"
API = "https://mall.xiaohongshu.com" + module.DETAIL_PATH
RAW = (Path(__file__).parents[1] / "examples/response.json").read_bytes()


class Response:
    def __init__(self, body=RAW, *, item_id=ID, url=None, method="GET", status=200, frame=None):
        self.url = url or f"{API}?item_id={item_id}"
        self.status = status
        self.request = SimpleNamespace(
            method=method, frame=frame, is_navigation_request=lambda: frame is not None
        )
        self.raw = body
        self.body_calls = 0
        self.body_error = None
        self.body_wait = False

    async def body(self):
        self.body_calls += 1
        if self.body_error:
            raise self.body_error
        if self.body_wait:
            await asyncio.Event().wait()
        return self.raw


class FakeCDP:
    def __init__(self):
        self.listeners = {}
        self.commands = []
        self.detached = False
        self.events = {}

    def on(self, event, handler):
        self.listeners[event] = handler

    def remove_listener(self, event, handler):
        assert self.listeners.pop(event) is handler

    async def send(self, command, params=None):
        self.commands.append((command, params))
        if command == "Page.getFrameTree":
            return {"frameTree": {"frame": {"id": "main"}}}
        if command in ("Fetch.continueRequest", "Fetch.failRequest"):
            self.events[params["requestId"]].set_result(command)
        return {}

    async def navigate(self, url, frame="main"):
        request_id = str(len(self.events))
        future = asyncio.get_running_loop().create_future()
        self.events[request_id] = future
        self.listeners["Fetch.requestPaused"](
            {"requestId": request_id, "frameId": frame, "request": {"url": url}}
        )
        return await future

    async def detach(self):
        self.detached = True


class FakePage:
    def __init__(self, cdp):
        self.listeners = defaultdict(list)
        self.main_frame = SimpleNamespace(url="about:blank")
        self.url = "about:blank"
        self.cdp = cdp
        self.opened = []
        self.responses = [Response()]
        self.redirects = [H5 + "?xsec_token=SENTINEL&instation_link=nested"]
        self.error = None
        self.hang = False
        self.restricted = False
        self.close_early = False
        self.restriction_seen = None

    def on(self, event, handler):
        self.listeners[event].append(handler)

    def remove_listener(self, event, handler):
        self.listeners[event].remove(handler)

    def emit(self, event, value=None):
        for handler in self.listeners[event]:
            handler() if value is None else handler(value)

    def get_by_text(self, pattern):
        return self

    @property
    def first(self):
        return self

    def filter(self, **options):
        return self

    async def count(self):
        return int(self.restricted)

    async def wait_for(self, **options):
        self.restriction_seen = asyncio.Event()
        if self.restricted:
            self.restriction_seen.set()
            return
        await self.restriction_seen.wait()

    async def goto(self, url, **options):
        assert self.listeners["response"]
        assert any(command == "Fetch.enable" for command, _ in self.cdp.commands)
        if self.error:
            raise self.error
        if self.hang:
            await asyncio.Event().wait()
        for target in [url, *self.redirects]:
            if await self.cdp.navigate(target) == "Fetch.failRequest":
                raise PlaywrightError("SENTINEL token-bearing URL")
            self.opened.append(target)
        # Responses deliberately precede the committed page identity.
        for response in self.responses:
            self.emit("response", response)
        self.url = self.redirects[-1] if self.redirects else url
        self.main_frame.url = self.url
        self.emit("framenavigated", self.main_frame)
        if self.close_early:
            self.emit("close")

    async def wait_for_url(self, predicate, **options):
        if not predicate(self.url):
            await asyncio.Event().wait()


class FakeContext:
    def __init__(self, page):
        self.page = page
        self.closed = False
        self.error = None
        self.close_error = None

    async def new_page(self):
        if self.error:
            raise self.error
        return self.page

    async def new_cdp_session(self, page):
        return page.cdp

    async def close(self):
        self.closed = True
        if self.close_error:
            raise self.close_error


class FakeBrowser:
    def __init__(self, context):
        self.context = context
        self.closed = False

    async def new_context(self):
        return self.context

    async def close(self):
        self.closed = True


class FakePlaywright:
    def __init__(self):
        self.cdp = FakeCDP()
        self.page = FakePage(self.cdp)
        self.context = FakeContext(self.page)
        self.browser = FakeBrowser(self.context)
        self.chromium = self
        self.options = None
        self.error = None
        self.stopped = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        assert self.browser.closed or self.error
        self.stopped = True

    async def launch(self, **options):
        self.options = options
        if self.error:
            raise self.error
        return self.browser


@pytest.fixture
def browser(monkeypatch):
    fake = FakePlaywright()
    monkeypatch.setattr(module, "async_playwright", lambda: fake)
    return fake


def collect(browser, raw=SHORT, **options):
    try:
        return asyncio.run(
            BrowserProductCollector(
                navigation_timeout=0.08, response_timeout=0.04, **options
            ).collect(raw)
        )
    finally:
        assert browser.context.closed
        assert browser.browser.closed
        assert browser.stopped
        assert not any(browser.page.listeners.values())


def test_success_early_response_original_bytes_and_cleanup(browser):
    result = collect(browser)
    assert result.item_id == ID
    assert result.collection_method == "playwright" and result.http_status == 200
    assert result.product.title and result.product.shop.name
    assert result.product.price.current == Decimal("9.99")
    assert result.raw_body is RAW
    assert result.raw_body == RAW
    assert browser.page.responses[0].body_calls == 1
    assert "raw_body=" not in repr(result)
    assert browser.page.opened == [SHORT, H5 + "?xsec_token=SENTINEL&instation_link=nested"]
    assert browser.cdp.detached


@pytest.mark.parametrize("headless,channel", [(True, None), (False, "chrome")])
def test_launch_configuration(browser, headless, channel):
    collect(browser, headless=headless, channel=channel)
    assert browser.options["headless"] == headless
    assert browser.options.get("channel") == channel
    assert browser.options["timeout"] == 80


@pytest.mark.parametrize(
    "url,method",
    [
        ("https://evil.test" + module.DETAIL_PATH + f"?item_id={ID}", "GET"),
        (API + f"/extra?item_id={ID}", "GET"),
        (API + f"?item_id={ID}", "POST"),
        (API + f"?item_id={ID}&item_id={ID}", "GET"),
        (API + f"?item_id={ID}&item_id=", "GET"),
        (API + "?item_id=invalid", "GET"),
        ("http://mall.xiaohongshu.com" + module.DETAIL_PATH + f"?item_id={ID}", "GET"),
        (API.replace(".com/", ".com:444/") + f"?item_id={ID}", "GET"),
        (API.replace("mall.", "user:pass@mall.") + f"?item_id={ID}", "GET"),
    ],
)
def test_ignore_unrelated_responses(browser, url, method):
    irrelevant = Response(url=url, method=method)
    other = Response(item_id=OTHER)
    browser.page.responses = [irrelevant, other, Response()]
    assert collect(browser).item_id == ID
    assert irrelevant.body_calls == other.body_calls == 0


@pytest.mark.parametrize(
    "target",
    ["https://evil.test/", "http://127.0.0.1/", "https://10.0.0.1/", H5 + "/extra"],
)
def test_redirect_hops_blocked_before_continue(browser, target):
    browser.page.redirects = ["https://xhslink.com/m/second?xsec_token=SENTINEL", target]
    with pytest.raises(BrowserCollectionError, match="不受信任") as error:
        collect(browser)
    assert error.value.code == "BROWSER_UNTRUSTED_REDIRECT"
    assert target not in browser.page.opened
    assert browser.cdp.commands[-1][0] == "Fetch.failRequest"


@pytest.mark.parametrize("status", [401, 403, 429, 461])
@pytest.mark.parametrize("navigation", [False, True])
def test_http_restrictions(browser, status, navigation):
    browser.page.responses = [
        Response(status=status, frame=browser.page.main_frame if navigation else None)
    ]
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser)
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"


@pytest.mark.parametrize(
    "kind,code",
    [
        ("navigation_error", "BROWSER_NAVIGATION_FAILED"),
        ("navigation_timeout", "BROWSER_NAVIGATION_TIMEOUT"),
        ("missing", "BROWSER_PRODUCT_RESPONSE_TIMEOUT"),
        ("other_only", "BROWSER_PRODUCT_RESPONSE_TIMEOUT"),
        ("body_timeout", "BROWSER_PRODUCT_RESPONSE_TIMEOUT"),
        ("body_error", "BROWSER_PRODUCT_RESPONSE_MISSING"),
        ("json", "BROWSER_INVALID_RESPONSE"),
        ("business", "BROWSER_INVALID_RESPONSE"),
        ("http", "BROWSER_INVALID_RESPONSE"),
        ("identity", "BROWSER_PRODUCT_ID_MISMATCH"),
        ("restricted_body", "BROWSER_ACCESS_RESTRICTED"),
        ("restricted_page", "BROWSER_ACCESS_RESTRICTED"),
        ("login_redirect", "BROWSER_ACCESS_RESTRICTED"),
        ("closed", "BROWSER_PRODUCT_RESPONSE_MISSING"),
    ],
)
def test_failure_classification_and_cleanup(browser, kind, code):
    page = browser.page
    if kind == "navigation_error":
        page.error = PlaywrightError("SENTINEL sensitive URL")
    elif kind == "navigation_timeout":
        page.hang = True
    elif kind == "missing":
        page.responses = []
    elif kind == "other_only":
        page.responses = [Response(item_id=OTHER)]
    elif kind == "body_timeout":
        page.responses[0].body_wait = True
    elif kind == "body_error":
        page.responses[0].body_error = PlaywrightError("SENTINEL body failed")
    elif kind == "json":
        page.responses = [Response(b"invalid SENTINEL")]
    elif kind == "business":
        page.responses = [Response(b'{"success":false}')]
    elif kind == "http":
        page.responses = [Response(status=500)]
    elif kind == "identity":
        page.responses = [Response(RAW.replace(ID.encode(), OTHER.encode()))]
    elif kind == "restricted_body":
        page.responses = [Response('{"success":false,"msg":"请登录"}'.encode())]
    elif kind == "restricted_page":
        page.restricted = True
    elif kind == "login_redirect":
        page.redirects = ["https://www.xiaohongshu.com/login"]
    elif kind == "closed":
        page.close_early = True
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser)
    assert error.value.code == code
    assert "SENTINEL" not in str(error.value)


def test_launch_failure_stops_playwright(browser):
    browser.error = PlaywrightError("SENTINEL launch failed")
    with pytest.raises(BrowserCollectionError) as error:
        asyncio.run(BrowserProductCollector().collect(SHORT))
    assert error.value.code == "BROWSER_LAUNCH_FAILED"
    assert browser.stopped


def test_partial_initialization_and_close_failure(browser):
    browser.context.error = PlaywrightError("new page failed")
    browser.context.close_error = PlaywrightError("close failed")
    with pytest.raises(BrowserCollectionError) as error:
        asyncio.run(BrowserProductCollector().collect(SHORT))
    assert error.value.code == "BROWSER_LAUNCH_FAILED"
    assert browser.context.closed and browser.browser.closed and browser.stopped


def test_cancellation_closes_everything(browser):
    browser.page.hang = True

    async def exercise():
        task = asyncio.create_task(BrowserProductCollector().collect(SHORT))
        while not browser.options:
            await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(exercise())
    assert browser.browser.closed and browser.context.closed and browser.stopped


def test_item_identity_required_even_if_sku_matches(browser):
    raw = (
        '{"success":true,"data":{"template_data":[{"descriptionMain":'
        f'{{"name":"title","itemId":"{OTHER}"}},"descriptionH5":{{"skuId":"{ID}"}}}}]}}}}'
    ).encode()
    browser.page.responses = [Response(raw)]
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser)
    assert error.value.code == "BROWSER_PRODUCT_ID_MISMATCH"


def test_different_sku_is_valid_product(browser):
    payload = json.loads(RAW)
    data = payload["data"]["template_data"][0]
    for block in ("descriptionH5", "selectedH5", "carouselH5", "bottomBarMainH5"):
        data[block]["skuId"] = OTHER
    browser.page.responses = [Response(json.dumps(payload).encode())]
    result = collect(browser)
    assert result.item_id == ID
    assert result.product.basic_info.sku_id == OTHER


def test_navigation_id_mismatch(browser):
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser, OTHER)
    assert error.value.code == "BROWSER_PRODUCT_ID_MISMATCH"


@pytest.mark.parametrize("value", [SHORT + "?xsec_token=SENTINEL", H5 + "?xsec_token=SENTINEL"])
def test_original_navigation_context_retained(value):
    assert navigation_input(f"【小红书】标题 {value} 查看")[0] == value


def test_bare_id_canonical_url_and_invalid_input():
    assert navigation_input(ID.upper()) == (H5, ID)
    with pytest.raises(InputError):
        navigation_input("https://127.0.0.1/")
    with pytest.raises(InputError):
        navigation_input(H5 + "?xsec_token=one " + H5 + "?xsec_token=two")
    with pytest.raises(BrowserCollectionError):
        navigation_target("https://www.xiaohongshu.com/?item_id=" + ID)


@pytest.mark.parametrize("timeout", [0, -1, float("nan"), float("inf")])
def test_invalid_configuration(timeout):
    with pytest.raises(ValueError):
        BrowserProductCollector(response_timeout=timeout)


def test_invalid_channel():
    with pytest.raises(ValueError):
        BrowserProductCollector(channel="stealth")


def test_api_item_id_matching():
    assert response_item_id(Response(url=API + f"?item_id={ID.upper()}&version=0.0.5")) == ID


def test_noncanonical_body_precision_and_no_reencoding(browser):
    raw = (
        '{ "success" : true,\r\n"data":{"template_data":[{'
        f'"descriptionMain":{{"name":"商品","itemId":"{ID}"}},'
        '"priceH5":{"highlightPrice":9.99000000000000000000001},'
        '"private":{"xsec_token":"SENTINEL"},"order":[3,1,2]}]}}\r\n'
    ).encode()
    browser.page.responses = [Response(raw)]
    result = collect(browser)
    assert result.raw_body is raw
    assert result.product.price.current == Decimal("9.99000000000000000000001")
    assert result.product.sales.estimated_count is None
    assert result.product.fetched_at.tzinfo.key == "Asia/Shanghai"


@pytest.mark.parametrize(
    "kind", ["missing_product", "missing_all", "invalid_product", "conflicting_skus"]
)
def test_unverifiable_identity_rejected(browser, kind):
    payload = json.loads(RAW)
    data = payload["data"]["template_data"][0]
    if kind in {"missing_product", "missing_all"}:
        data["descriptionMain"].pop("itemId")
    if kind == "missing_all":
        for block in ("descriptionH5", "selectedH5", "carouselH5", "bottomBarMainH5"):
            data[block].pop("skuId", None)
    elif kind == "invalid_product":
        data["descriptionMain"]["itemId"] = "bad"
    elif kind == "conflicting_skus":
        data["selectedH5"]["skuId"] = OTHER
    browser.page.responses = [Response(json.dumps(payload).encode())]
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser)
    assert error.value.code in {"BROWSER_INVALID_RESPONSE", "BROWSER_PRODUCT_ID_MISMATCH"}


def test_query_nested_id_never_changes_product_identity(browser):
    browser.page.redirects = [H5 + "?instation_link=" + H5.replace(ID, OTHER)]
    assert collect(browser).item_id == ID


def test_visible_restriction_checked_before_success(browser):
    # Simulate the locator watcher not completing before a fast successful response.
    original_body = browser.page.responses[0].body

    async def body():
        browser.page.restricted = True
        return await original_body()

    browser.page.responses[0].body = body
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser)
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"


def test_explicit_delisting_is_distinct_from_request_failure(browser):
    browser.page.responses = [Response('{"success":false,"msg":"商品已下架"}'.encode())]
    with pytest.raises(BrowserCollectionError) as error:
        collect(browser)
    assert error.value.code == "BROWSER_PRODUCT_UNAVAILABLE"


def test_external_browser_reuses_core_and_never_closes_shared_browser(browser):
    result = asyncio.run(BrowserProductCollector().collect_with_browser(SHORT, browser.browser))
    assert result.item_id == ID and result.raw_body == RAW
    assert browser.options is None and not browser.stopped
    assert not browser.browser.closed and browser.context.closed
    assert browser.cdp.detached and not any(browser.page.listeners.values())


def test_external_browser_failure_still_closes_context(browser):
    browser.page.error = PlaywrightError("SECRET")
    with pytest.raises(BrowserCollectionError) as error:
        asyncio.run(BrowserProductCollector().collect_with_browser(SHORT, browser.browser))
    assert error.value.code == "BROWSER_NAVIGATION_FAILED"
    assert browser.context.closed and not browser.browser.closed
    assert browser.options is None
