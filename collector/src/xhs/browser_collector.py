"""Experimental, single-product collection from browser-generated responses only."""

import asyncio
import math
import re
from collections.abc import Awaitable, Callable, Coroutine
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from playwright.async_api import (
    Browser,
    BrowserContext,
    CDPSession,
    Frame,
    Page,
    Response,
    async_playwright,
)
from playwright.async_api import Error as PlaywrightError

from xhs.browser_cleanup import CleanupFailure, CleanupTracker, event, finish_cleanup
from xhs.errors import BROWSER_HTTP_ERRORS, InputError, ProductDataError
from xhs.input_parser import (
    H5_HOSTS,
    ITEM_ID,
    URL_PATTERN,
    URL_TAIL,
    Target,
    parse_input,
    preprocess_input,
    validate_url,
)
from xhs.product_decoder import decode_product_body
from xhs.schemas import Product
from xhs.upstream_diagnostics import (
    ERROR_CAPTURE_TIMEOUT,
    MAX_ERROR_BODY_BYTES,
    CaptureStatus,
    Source,
    UpstreamDiagnostic,
    extract_upstream_error,
)

DETAIL_PATH = "/api/store/jpd/edith/detail/h5/toc"
RESTRICTED_STATUS = {401, 403, 429, 461}
RESTRICTION_TEXT = re.compile(
    r"请先登录|请登录|登录后继续|登录后查看|安全验证|安全校验|访问受限|访问频繁|请求频繁|验证码"
)


class BrowserCollectionError(Exception):
    """Only fixed, safe diagnostics; never propagate browser exception text."""

    def __init__(
        self, code: str, message: str, *, upstream_diagnostic: UpstreamDiagnostic | None = None
    ):
        super().__init__(message)
        self.code = code
        self.message = message
        self.upstream_diagnostic = upstream_diagnostic


@dataclass(frozen=True)
class BrowserProductResult:
    item_id: str
    raw_body: bytes = field(repr=False)
    product: Product
    http_status: int
    collection_method: Literal["playwright"] = "playwright"


@dataclass
class ProbeStatus:
    browser_started: bool = False
    navigation: str = "not_started"
    item_id: str | None = None
    http_status: int | None = None
    navigation_http_status: int | None = None


def navigation_input(raw: str) -> tuple[str, str | None]:
    """Reuse local input validation, but retain the original URL's navigation context."""
    parsed = parse_input(raw)
    if parsed.source_type == "direct_id":
        return f"https://www.xiaohongshu.com/goods-detail/{parsed.item_id}", parsed.item_id
    matches = list(URL_PATTERN.finditer(preprocess_input(raw)))
    urls = list(dict.fromkeys(match.group().rstrip(URL_TAIL) for match in matches))
    # Input parsing merges canonical equivalents. Different contexts must not be chosen silently.
    if len(urls) != 1:
        raise InputError("AMBIGUOUS_PRODUCT_INPUT", "浏览器导航需要唯一的原始商品链接")
    validate_url(urls[0])
    return urls[0], parsed.item_id


def navigation_target(url: str) -> Target:
    try:
        parts = urlsplit(url)
    except ValueError as exc:
        raise BrowserCollectionError(
            "BROWSER_UNTRUSTED_REDIRECT", "主页面地址格式不受支持"
        ) from exc
    if parts.scheme == "https" and parts.hostname in H5_HOSTS:
        if re.search(r"/(?:login|captcha|security|verify)(?:/|$)", parts.path, re.I):
            raise BrowserCollectionError("BROWSER_ACCESS_RESTRICTED", "页面要求登录或安全验证")
    try:
        return validate_url(url, redirect=True)
    except (InputError, ValueError) as exc:
        raise BrowserCollectionError(
            "BROWSER_UNTRUSTED_REDIRECT", "已阻止不受信任或不支持的主页面导航"
        ) from exc


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


class BrowserProductCollector:
    def __init__(
        self,
        *,
        headless: bool = True,
        channel: str | None = None,
        navigation_timeout: float = 30,
        response_timeout: float = 15,
        status: ProbeStatus | None = None,
        cleanup_timeout: float = 5.0,
        error_capture_timeout: float = ERROR_CAPTURE_TIMEOUT,
        max_error_body_bytes: int = MAX_ERROR_BODY_BYTES,
        on_access_restricted: Callable[[], None] | None = None,
        on_cleanup_failure: Callable[[CleanupFailure], None] | None = None,
    ) -> None:
        if channel not in (None, "chrome"):
            raise ValueError("channel must be chrome or None")
        if any(
            not math.isfinite(v) or v <= 0
            for v in (navigation_timeout, response_timeout, cleanup_timeout, error_capture_timeout)
        ):
            raise ValueError("timeouts must be finite positive seconds")
        if type(max_error_body_bytes) is not int or max_error_body_bytes < 1:
            raise ValueError("max_error_body_bytes must be a positive integer")
        self.error_capture_timeout = error_capture_timeout
        self.max_error_body_bytes = max_error_body_bytes
        self.headless = headless
        self.channel = channel
        self.navigation_timeout = navigation_timeout
        self.response_timeout = response_timeout
        self.cleanup_timeout = cleanup_timeout
        self._on_access_restricted = on_access_restricted
        self.collection_id = uuid4().hex
        self.cleanup = CleanupTracker(cleanup_timeout, self.collection_id, on_cleanup_failure)
        self.status = status if status is not None else ProbeStatus()

    def _reset_status(self) -> None:
        self.status.browser_started = False
        self.status.navigation = "not_started"
        self.status.item_id = None
        self.status.http_status = None
        self.status.navigation_http_status = None
        event("browser.collection.started", collection_id=self.collection_id)

    async def collect(self, raw: str) -> BrowserProductResult:
        url, expected_id = navigation_input(raw)
        self._reset_status()

        browser = None
        playwright_context = async_playwright()
        playwright = None
        primary = None
        try:
            try:
                playwright = await playwright_context.__aenter__()
                options = {"headless": self.headless, "timeout": self.navigation_timeout * 1000}
                if self.channel:
                    options["channel"] = self.channel
                browser = await playwright.chromium.launch(**options)
            except PlaywrightError:
                raise BrowserCollectionError(
                    "BROWSER_LAUNCH_FAILED", "无法初始化浏览器，请检查浏览器二进制和运行权限"
                ) from None
            self.status.browser_started = True
            result = await self._collect_context(browser, url, expected_id, standalone=True)
        except BaseException as exc:
            primary = exc
            raise
        finally:

            async def release() -> None:
                if browser is not None:
                    await self.cleanup.run("browser_close", browser.close())
                if playwright is not None:
                    await self.cleanup.run(
                        "playwright_stop", playwright_context.__aexit__(None, None, None)
                    )
                await self.reap_pending()

            await finish_cleanup(asyncio.create_task(release()))
            if primary is None and self.cleanup.failures:
                raise BrowserCollectionError("BROWSER_UNAVAILABLE", "浏览器资源无法安全释放")

        event(
            "browser.collection.completed",
            collection_id=self.collection_id,
            navigation_http_status=self.status.navigation_http_status,
            product_http_status=self.status.http_status,
        )
        return result

    async def collect_with_browser(self, raw: str, browser: Browser) -> BrowserProductResult:
        """Borrow a Browser; own only this collection's isolated Context."""
        url, expected_id = navigation_input(raw)
        self._reset_status()
        self.status.browser_started = True
        try:
            result = await self._collect_context(browser, url, expected_id, standalone=False)
            event(
                "browser.collection.completed",
                collection_id=self.collection_id,
                navigation_http_status=self.status.navigation_http_status,
                product_http_status=self.status.http_status,
            )
            return result
        except PlaywrightError as exc:
            raise BrowserCollectionError("BROWSER_NAVIGATION_FAILED", "浏览器操作失败") from exc

    async def _collect_context(
        self, browser: Browser, url: str, expected_id: str | None, *, standalone: bool
    ) -> BrowserProductResult:
        context = None

        async def create_context() -> BrowserContext:
            async with asyncio.timeout(self.navigation_timeout):
                return await browser.new_context()

        primary = None

        creation = asyncio.create_task(create_context())
        try:
            try:
                # Shield acquisition so cancellation cannot abandon a just-created Context.
                context = await asyncio.shield(creation)
            except asyncio.CancelledError:
                try:
                    context = await finish_cleanup(creation)
                except (PlaywrightError, TimeoutError):
                    pass
                raise
            except TimeoutError as exc:
                raise BrowserCollectionError(
                    "BROWSER_NAVIGATION_TIMEOUT", "创建浏览器 Context 超时"
                ) from exc
            try:
                async with asyncio.timeout(self.navigation_timeout):
                    page = await context.new_page()
            except TimeoutError as exc:
                raise BrowserCollectionError(
                    "BROWSER_NAVIGATION_TIMEOUT", "创建商品页面超时"
                ) from exc
            except PlaywrightError as exc:
                raise BrowserCollectionError(
                    "BROWSER_LAUNCH_FAILED" if standalone else "BROWSER_NAVIGATION_FAILED",
                    "无法创建商品页面",
                ) from exc
            return await self._collect_page(context, page, url, expected_id)
        except BaseException as exc:
            if isinstance(exc, PlaywrightError):
                exc = BrowserCollectionError("BROWSER_NAVIGATION_FAILED", "浏览器操作失败")
            primary = exc
            code = (
                exc.code
                if isinstance(exc, BrowserCollectionError) and exc.code in BROWSER_HTTP_ERRORS
                else "CANCELLED"
                if isinstance(exc, asyncio.CancelledError)
                else "BROWSER_NAVIGATION_FAILED"
            )
            if code == "BROWSER_ACCESS_RESTRICTED" and self._on_access_restricted:
                self._on_access_restricted()
            event(
                "browser.collection.failed",
                warning=True,
                collection_id=self.collection_id,
                error_code=code,
                navigation_http_status=self.status.navigation_http_status,
                product_http_status=self.status.http_status,
            )
            raise exc from None
        finally:

            async def release() -> None:
                nonlocal context
                if context is None and creation.done() and not creation.cancelled():
                    if creation.exception() is None:
                        context = creation.result()
                if context is not None:
                    await self.cleanup.run("context_close", context.close())
                await self.reap_pending()

            await finish_cleanup(asyncio.create_task(release()))
            if primary is None and self.cleanup.failures:
                raise BrowserCollectionError("BROWSER_UNAVAILABLE", "浏览器资源无法安全释放")

    async def reap_pending(self) -> None:
        if any(not task.done() for task in self.cleanup.pending):
            await self.cleanup.run("watchers_join", self.cleanup.join())

    def _diagnostic(
        self,
        response: Response,
        source: Source,
        body: bytes | None = None,
        capture_status: CaptureStatus = "not_attempted",
    ) -> UpstreamDiagnostic:
        return extract_upstream_error(
            body,
            response.status,
            source=source,
            collection_id=self.collection_id,
            capture_status=capture_status,
            max_body_bytes=self.max_error_body_bytes,
        )

    def _log_upstream_detected(self, response: Response, source: Source, code: str) -> None:
        fields = self._diagnostic(response, source).safe_metadata()
        event(
            "browser.upstream.error_detected",
            warning=True,
            collection_id=fields["collection_id"],
            source=fields["source"],
            http_status=fields["http_status"],
            collector_error=code if code in BROWSER_HTTP_ERRORS else "BROWSER_INVALID_RESPONSE",
        )

    def _log_diagnostic(self, diagnostic: UpstreamDiagnostic) -> None:
        fields = diagnostic.safe_metadata()
        event(
            "browser.upstream.capture_completed"
            if diagnostic.capture_status in {"captured", "empty"}
            else "browser.upstream.capture_failed",
            warning=diagnostic.capture_status not in {"captured", "empty"},
            **fields,
        )

    async def _capture_error(
        self, response: Response, source: Source, closed: Callable[[], bool]
    ) -> UpstreamDiagnostic:
        if closed():
            return self._diagnostic(response, source, capture_status="unavailable")
        try:
            headers = response.headers
        except PlaywrightError:
            return self._diagnostic(response, source, capture_status="read_error")
        length = headers.get("content-length")
        if (
            not isinstance(length, str)
            or re.fullmatch(r"[0-9]{1,18}", length) is None
            or headers.get("content-encoding", "identity").strip().lower() not in {"", "identity"}
            or headers.get("transfer-encoding", "identity").strip().lower() not in {"", "identity"}
        ):
            return self._diagnostic(response, source, capture_status="skipped_unknown_length")
        if int(length) > self.max_error_body_bytes:
            return self._diagnostic(response, source, capture_status="too_large")
        event(
            "browser.upstream.capture_started",
            collection_id=self.collection_id,
            source=source,
            http_status=response.status,
        )
        task = self.cleanup.own(asyncio.create_task(response.body()))
        try:
            done, _ = await asyncio.wait({task}, timeout=self.error_capture_timeout)
            if not done:
                task.cancel()
                return self._diagnostic(response, source, capture_status="timeout")
            if task.cancelled():
                return self._diagnostic(response, source, capture_status="unavailable")
            try:
                body = task.result()
            except Exception:
                return self._diagnostic(
                    response, source, capture_status="unavailable" if closed() else "read_error"
                )
            return self._diagnostic(response, source, body)
        except asyncio.CancelledError:
            task.cancel()
            raise

    async def _collect_page(
        self, context: BrowserContext, page: Page, url: str, expected_id: str | None
    ) -> BrowserProductResult:
        loop = asyncio.get_running_loop()
        failure: asyncio.Future[BrowserCollectionError] = loop.create_future()
        responses: asyncio.Queue[tuple[str, Response]] = asyncio.Queue(maxsize=64)
        tasks: set[asyncio.Task] = set()
        cdp: CDPSession | None = None
        on_paused = None
        validated_item_id = None
        error_response: tuple[Source, Response] | None = None
        page_closed = False
        body_attempted = False
        body_status: CaptureStatus = "not_attempted"
        received_body: bytes | None = None
        product_valid = False

        def fail(code: str, message: str) -> None:
            if code == "BROWSER_ACCESS_RESTRICTED" and self._on_access_restricted:
                self._on_access_restricted()
            if not failure.done():
                failure.set_result(BrowserCollectionError(code, message))

        def schedule(coroutine: Coroutine) -> None:
            task = self.cleanup.own(asyncio.create_task(coroutine))
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        async def guarded[T](awaitable: Awaitable[T]) -> T:
            task = self.cleanup.own(asyncio.ensure_future(awaitable))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
            try:
                done, _ = await asyncio.wait({task, failure}, return_when=asyncio.FIRST_COMPLETED)
                if failure in done:
                    raise failure.result()
                return task.result()
            finally:
                if not task.done():
                    task.cancel()

        def on_response(response: Response) -> None:
            nonlocal error_response
            request = response.request
            is_main = request.is_navigation_request() and request.frame == page.main_frame
            candidate = response_item_id(response)
            if is_main:
                self.status.navigation_http_status = response.status
                if response.status >= 400:
                    try:
                        navigation_target(response.url)
                    except BrowserCollectionError:
                        pass
                    else:
                        error_response = error_response or ("main_response", response)
            if candidate is not None and response.status >= 400:
                # CDP validates the goods-detail path before the page can request its API.
                if candidate == validated_item_id and (
                    expected_id is None or candidate == expected_id
                ):
                    error_response = error_response or ("product_response", response)
            if (is_main or candidate is not None) and response.status in RESTRICTED_STATUS:
                if candidate is not None:
                    self.status.http_status = response.status
                if is_main:
                    self.status.navigation = "restricted"
                event(
                    "browser.collection.restricted",
                    warning=True,
                    collection_id=self.collection_id,
                    reason="main_response_status" if is_main else "product_response_status",
                    http_status=response.status,
                    error_code="BROWSER_ACCESS_RESTRICTED",
                )
                fail("BROWSER_ACCESS_RESTRICTED", "上游要求认证、验证或限制访问")
            elif candidate is not None:
                if responses.full():
                    fail("BROWSER_INVALID_RESPONSE", "商品候选响应数量异常，已停止")
                else:
                    responses.put_nowait((candidate, response))

        def on_frame(frame: Frame) -> None:
            if frame == page.main_frame and frame.url != "about:blank":
                try:
                    navigation_target(frame.url)
                except BrowserCollectionError as exc:
                    fail(exc.code, exc.message)

        def on_close() -> None:
            nonlocal page_closed
            page_closed = True
            fail("BROWSER_PRODUCT_RESPONSE_MISSING", "商品响应完成前页面已关闭")

        async def watch_restriction() -> None:
            try:
                await (
                    page.get_by_text(RESTRICTION_TEXT)
                    .filter(visible=True)
                    .first.wait_for(
                        state="visible",
                        timeout=(self.navigation_timeout + self.response_timeout) * 1000,
                    )
                )
                fail("BROWSER_ACCESS_RESTRICTED", "页面要求登录、安全验证或限制访问")
            except PlaywrightError:
                pass

        page.on("response", on_response)
        page.on("framenavigated", on_frame)
        page.on("close", on_close)
        page.on("crash", on_close)
        try:
            # Playwright route() only handles the first URL of an HTTP redirect chain.
            # CDP pauses every Document hop before sending it, without rebuilding requests.
            async with asyncio.timeout(self.navigation_timeout):
                cdp = await context.new_cdp_session(page)
                tree = await cdp.send("Page.getFrameTree")
            main_frame_id = tree["frameTree"]["frame"]["id"]

            async def paused(event: dict) -> None:
                nonlocal validated_item_id
                try:
                    if event["frameId"] == main_frame_id:
                        validated_item_id = navigation_target(event["request"]["url"]).item_id
                    await cdp.send("Fetch.continueRequest", {"requestId": event["requestId"]})
                except BrowserCollectionError as exc:
                    fail(exc.code, exc.message)
                    try:
                        await cdp.send(
                            "Fetch.failRequest",
                            {"requestId": event["requestId"], "errorReason": "BlockedByClient"},
                        )
                    except PlaywrightError:
                        pass
                except (PlaywrightError, KeyError):
                    fail("BROWSER_NAVIGATION_FAILED", "无法检查浏览器导航")

            def on_paused(event: dict) -> None:
                schedule(paused(event))

            cdp.on("Fetch.requestPaused", on_paused)
            async with asyncio.timeout(self.navigation_timeout):
                await cdp.send(
                    "Fetch.enable",
                    {"patterns": [{"resourceType": "Document", "requestStage": "Request"}]},
                )
            schedule(watch_restriction())
            self.status.navigation = "navigating"
            try:
                async with asyncio.timeout(self.navigation_timeout):
                    await guarded(page.goto(url, wait_until="domcontentloaded", timeout=0))
                    await guarded(
                        page.wait_for_url(
                            lambda value: bool(_product_page(str(value))),
                            wait_until="domcontentloaded",
                            timeout=0,
                        )
                    )
            except TimeoutError as exc:
                self.status.navigation = "timeout"
                raise BrowserCollectionError(
                    "BROWSER_NAVIGATION_TIMEOUT", "页面导航或进入商品页超时"
                ) from exc
            target = navigation_target(page.url)
            item_id = target.item_id
            if not item_id:
                raise BrowserCollectionError("BROWSER_NAVIGATION_FAILED", "未进入商品详情页")
            self.status.item_id = item_id
            self.status.navigation = "product_page"
            if expected_id is not None and expected_id != item_id:
                raise BrowserCollectionError(
                    "BROWSER_PRODUCT_ID_MISMATCH", "导航商品 ID 与输入不一致"
                )
            try:
                async with asyncio.timeout(self.response_timeout):
                    while True:
                        candidate, response = await guarded(responses.get())
                        if candidate != item_id:
                            continue
                        self.status.http_status = response.status
                        error_response = ("product_response", response)
                        if not 200 <= response.status < 300:
                            raise BrowserCollectionError(
                                "BROWSER_INVALID_RESPONSE", "商品响应 HTTP 状态异常"
                            )
                        try:
                            body_attempted = True
                            body_status = "timeout"
                            body = await guarded(response.body())
                            received_body = body
                        except PlaywrightError as exc:
                            body_status = "read_error"
                            raise BrowserCollectionError(
                                "BROWSER_PRODUCT_RESPONSE_MISSING", "无法读取完整商品响应正文"
                            ) from exc
                        product = parse_browser_body(body, item_id)
                        product_valid = True
                        # Recheck identity in case the page navigated while reading the body.
                        if navigation_target(page.url).item_id != item_id:
                            raise BrowserCollectionError(
                                "BROWSER_PRODUCT_ID_MISMATCH", "读取响应时页面商品发生变化"
                            )
                        if await guarded(
                            page.get_by_text(RESTRICTION_TEXT).filter(visible=True).count()
                        ):
                            raise BrowserCollectionError(
                                "BROWSER_ACCESS_RESTRICTED", "页面要求登录、安全验证或限制访问"
                            )
                        if failure.done():
                            raise failure.result()
                        return BrowserProductResult(item_id, body, product, response.status)
            except TimeoutError as exc:
                raise BrowserCollectionError(
                    "BROWSER_PRODUCT_RESPONSE_TIMEOUT", "等待商品详情响应或正文超时"
                ) from exc
        except TimeoutError as exc:
            raise BrowserCollectionError(
                "BROWSER_NAVIGATION_TIMEOUT", "初始化页面导航控制超时"
            ) from exc
        except BrowserCollectionError as exc:
            if exc.code == "BROWSER_ACCESS_RESTRICTED" and self._on_access_restricted:
                self._on_access_restricted()
            if error_response is not None and not product_valid:
                source, response = error_response
                if source == "product_response" and response_item_id(response) != validated_item_id:
                    raise
                self._log_upstream_detected(response, source, exc.code)
                if received_body is not None:
                    diagnostic = self._diagnostic(response, source, received_body)
                elif body_attempted:
                    diagnostic = self._diagnostic(response, source, capture_status=body_status)
                else:
                    try:
                        diagnostic = await self._capture_error(
                            response, source, lambda: page_closed
                        )
                    except asyncio.CancelledError:
                        self._log_diagnostic(
                            self._diagnostic(response, source, capture_status="unavailable")
                        )
                        raise
                exc.upstream_diagnostic = diagnostic
                self._log_diagnostic(diagnostic)
            raise
        finally:

            async def close_watchers() -> None:
                async def remove_listeners() -> None:
                    failed = False
                    listeners = [
                        (page, "response", on_response),
                        (page, "framenavigated", on_frame),
                        (page, "close", on_close),
                        (page, "crash", on_close),
                    ]
                    if cdp is not None and on_paused is not None:
                        listeners.append((cdp, "Fetch.requestPaused", on_paused))
                    for emitter, name, callback in listeners:
                        try:
                            emitter.remove_listener(name, callback)
                        except Exception:
                            failed = True
                    if failed:
                        raise RuntimeError("Listener removal failed")

                await self.cleanup.run("listeners_remove", remove_listeners())
                snapshot = tuple(tasks)

                async def cancel_watchers() -> None:
                    for task in snapshot:
                        if not task.done() and not task.cancelling():
                            task.cancel()

                async def join_watchers() -> None:
                    if snapshot:
                        await asyncio.wait(snapshot)

                await self.cleanup.run("watchers_cancel", cancel_watchers())
                await self.cleanup.run("watchers_join", join_watchers())
                if cdp is not None:
                    await self.cleanup.run("cdp_detach", cdp.detach())

            await finish_cleanup(asyncio.create_task(close_watchers()))


def _product_page(url: str) -> bool:
    try:
        return navigation_target(url).item_id is not None
    except BrowserCollectionError:
        return False
