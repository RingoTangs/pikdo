"""Experimental, single-product collection from browser-generated responses only."""

import asyncio
import math
import re
from collections.abc import Callable
from uuid import uuid4

from playwright.async_api import (
    Browser,
    BrowserContext,
    Page,
    Response,
    async_playwright,
)
from playwright.async_api import Error as PlaywrightError

from xhs.browser_cleanup import CleanupFailure, CleanupTracker, event, finish_cleanup
from xhs.browser_contracts import BrowserCollectionError as BrowserCollectionError
from xhs.browser_contracts import BrowserProductResult as BrowserProductResult
from xhs.browser_contracts import ProbeStatus as ProbeStatus
from xhs.browser_navigation import navigation_input as navigation_input
from xhs.browser_navigation import navigation_target as navigation_target
from xhs.browser_page import RESTRICTED_STATUS as RESTRICTED_STATUS
from xhs.browser_page import RESTRICTION_TEXT as RESTRICTION_TEXT
from xhs.browser_page import PageCollectionSession
from xhs.browser_response import DETAIL_PATH as DETAIL_PATH
from xhs.browser_response import parse_browser_body as parse_browser_body
from xhs.browser_response import response_item_id as response_item_id
from xhs.errors import BROWSER_HTTP_ERRORS
from xhs.upstream_diagnostics import (
    ERROR_CAPTURE_TIMEOUT,
    MAX_ERROR_BODY_BYTES,
    CaptureStatus,
    Source,
    UpstreamDiagnostic,
    extract_upstream_error,
)


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
        # Resolve the legacy module symbols at call time, including test substitutions.
        session = PageCollectionSession(
            self,
            context,
            page,
            url,
            expected_id,
            navigation_target=lambda value: navigation_target(value),
            response_item_id=lambda response: response_item_id(response),
            parse_browser_body=lambda body, item_id: parse_browser_body(body, item_id),
            product_page=lambda value: _product_page(value),
        )
        return await session.run()


def _product_page(url: str) -> bool:
    try:
        return navigation_target(url).item_id is not None
    except BrowserCollectionError:
        return False
