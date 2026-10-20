"""One page's temporary capture state; Context and Browser remain borrowed."""

import asyncio
import re
from collections.abc import Awaitable, Callable, Coroutine
from typing import TYPE_CHECKING

from playwright.async_api import BrowserContext, CDPSession, Frame, Page, Response
from playwright.async_api import Error as PlaywrightError

from xhs.browser_cleanup import event, finish_cleanup
from xhs.browser_contracts import BrowserCollectionError, BrowserProductResult
from xhs.input_parser import Target
from xhs.schemas import Product
from xhs.upstream_diagnostics import CaptureStatus, Source

if TYPE_CHECKING:
    from xhs.browser_collector import BrowserProductCollector

RESTRICTED_STATUS = {401, 403, 429, 461}
RESTRICTION_TEXT = re.compile(
    r"请先登录|请登录|登录后继续|登录后查看|安全验证|安全校验|访问受限|访问频繁|请求频繁|验证码"
)


class PageCollectionSession:
    """State for exactly one Page, sharing its owner's cleanup tracker."""

    def __init__(
        self,
        collector: "BrowserProductCollector",
        context: BrowserContext,
        page: Page,
        url: str,
        expected_id: str | None,
        *,
        navigation_target: Callable[[str], Target],
        response_item_id: Callable[[Response], str | None],
        parse_browser_body: Callable[[bytes, str], Product],
        product_page: Callable[[str], bool],
    ) -> None:
        self.collector = collector
        self.context = context
        self.page = page
        self.url = url
        self.expected_id = expected_id
        self._navigation_target = navigation_target
        self._response_item_id = response_item_id
        self._parse_browser_body = parse_browser_body
        self._product_page = product_page
        loop = asyncio.get_running_loop()
        self.failure: asyncio.Future[BrowserCollectionError] = loop.create_future()
        self.responses: asyncio.Queue[tuple[str, Response]] = asyncio.Queue(maxsize=64)
        self.tasks: set[asyncio.Task] = set()
        self.cdp: CDPSession | None = None
        self.validated_item_id = None
        self.error_response: tuple[Source, Response] | None = None
        self.page_closed = False
        self.body_attempted = False
        self.body_status: CaptureStatus = "not_attempted"
        self.received_body: bytes | None = None
        self.product_valid = False
        self.main_frame_id: str | None = None
        self._paused_listener: Callable[[dict], None] | None = None

    async def run(self) -> BrowserProductResult:
        """Capture one product, then finish page cleanup before returning to its owner."""
        # Cache bound methods: removal must receive the exact registered callback objects.
        close_handler = self._on_close
        self._page_listeners = [
            (self.page, "response", self._on_response),
            (self.page, "framenavigated", self._on_frame),
            (self.page, "close", close_handler),
            (self.page, "crash", close_handler),
        ]
        for emitter, name, callback in self._page_listeners:
            emitter.on(name, callback)
        try:
            await self._initialize_cdp()
            self._schedule(self._watch_restriction())
            item_id = await self._navigate()
            return await self._read_product(item_id)
        except TimeoutError as exc:
            raise BrowserCollectionError(
                "BROWSER_NAVIGATION_TIMEOUT", "初始化页面导航控制超时"
            ) from exc
        except BrowserCollectionError as exc:
            if exc.code == "BROWSER_ACCESS_RESTRICTED" and self.collector._on_access_restricted:
                self.collector._on_access_restricted()
            await self._attach_diagnostic(exc)
            raise
        finally:
            await finish_cleanup(asyncio.create_task(self._close_watchers()))

    def _fail(self, code: str, message: str) -> None:
        if code == "BROWSER_ACCESS_RESTRICTED" and self.collector._on_access_restricted:
            self.collector._on_access_restricted()
        if not self.failure.done():
            self.failure.set_result(BrowserCollectionError(code, message))

    def _schedule(self, coroutine: Coroutine) -> None:
        task = self.collector.cleanup.own(asyncio.create_task(coroutine))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def _guarded[T](self, awaitable: Awaitable[T]) -> T:
        """Race a page operation against the first observed collection failure."""
        task = self.collector.cleanup.own(asyncio.ensure_future(awaitable))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        try:
            done, _ = await asyncio.wait({task, self.failure}, return_when=asyncio.FIRST_COMPLETED)
            # A restriction or closed page wins even if the operation completed in the same turn.
            if self.failure in done:
                raise self.failure.result()
            return task.result()
        finally:
            if not task.done():
                # Joining here could deadlock: some operations need Context.close() to finish.
                task.cancel()

    def _on_response(self, response: Response) -> None:
        request = response.request
        is_main = request.is_navigation_request() and request.frame == self.page.main_frame
        candidate = self._response_item_id(response)
        if is_main:
            self.collector.status.navigation_http_status = response.status
            if response.status >= 400:
                try:
                    self._navigation_target(response.url)
                except BrowserCollectionError:
                    pass
                else:
                    self.error_response = self.error_response or ("main_response", response)
        if candidate is not None and response.status >= 400:
            # CDP validates the goods-detail path before the page can request its API.
            if candidate == self.validated_item_id and (
                self.expected_id is None or candidate == self.expected_id
            ):
                self.error_response = self.error_response or ("product_response", response)
        if (is_main or candidate is not None) and response.status in RESTRICTED_STATUS:
            if candidate is not None:
                self.collector.status.http_status = response.status
            if is_main:
                self.collector.status.navigation = "restricted"
            event(
                "browser.collection.restricted",
                warning=True,
                collection_id=self.collector.collection_id,
                reason="main_response_status" if is_main else "product_response_status",
                http_status=response.status,
                error_code="BROWSER_ACCESS_RESTRICTED",
            )
            self._fail("BROWSER_ACCESS_RESTRICTED", "上游要求认证、验证或限制访问")
        elif candidate is not None:
            if self.responses.full():
                self._fail("BROWSER_INVALID_RESPONSE", "商品候选响应数量异常，已停止")
            else:
                self.responses.put_nowait((candidate, response))

    def _on_frame(self, frame: Frame) -> None:
        if frame == self.page.main_frame and frame.url != "about:blank":
            try:
                self._navigation_target(frame.url)
            except BrowserCollectionError as exc:
                self._fail(exc.code, exc.message)

    def _on_close(self) -> None:
        self.page_closed = True
        self._fail("BROWSER_PRODUCT_RESPONSE_MISSING", "商品响应完成前页面已关闭")

    async def _watch_restriction(self) -> None:
        try:
            await (
                self.page.get_by_text(RESTRICTION_TEXT)
                .filter(visible=True)
                .first.wait_for(
                    state="visible",
                    timeout=(self.collector.navigation_timeout + self.collector.response_timeout)
                    * 1000,
                )
            )
            self._fail("BROWSER_ACCESS_RESTRICTED", "页面要求登录、安全验证或限制访问")
        except PlaywrightError:
            pass

    async def _initialize_cdp(self) -> None:
        # Playwright route() only handles the first URL of an HTTP redirect chain.
        # CDP pauses every Document hop before sending it, without rebuilding requests.
        async with asyncio.timeout(self.collector.navigation_timeout):
            self.cdp = await self.context.new_cdp_session(self.page)
            tree = await self.cdp.send("Page.getFrameTree")
        self.main_frame_id = tree["frameTree"]["frame"]["id"]
        self._paused_listener = self._on_paused
        self.cdp.on("Fetch.requestPaused", self._paused_listener)
        async with asyncio.timeout(self.collector.navigation_timeout):
            await self.cdp.send(
                "Fetch.enable",
                {"patterns": [{"resourceType": "Document", "requestStage": "Request"}]},
            )

    async def _paused(self, event: dict) -> None:
        try:
            if event["frameId"] == self.main_frame_id:
                self.validated_item_id = self._navigation_target(event["request"]["url"]).item_id
            await self.cdp.send("Fetch.continueRequest", {"requestId": event["requestId"]})
        except BrowserCollectionError as exc:
            self._fail(exc.code, exc.message)
            try:
                await self.cdp.send(
                    "Fetch.failRequest",
                    {"requestId": event["requestId"], "errorReason": "BlockedByClient"},
                )
            except PlaywrightError:
                pass
        except (PlaywrightError, KeyError):
            self._fail("BROWSER_NAVIGATION_FAILED", "无法检查浏览器导航")

    def _on_paused(self, event: dict) -> None:
        self._schedule(self._paused(event))

    async def _navigate(self) -> str:
        """Keep navigation and product-page waiting inside one shared deadline."""
        self.collector.status.navigation = "navigating"
        try:
            async with asyncio.timeout(self.collector.navigation_timeout):
                await self._guarded(
                    self.page.goto(self.url, wait_until="domcontentloaded", timeout=0)
                )
                await self._guarded(
                    self.page.wait_for_url(
                        lambda value: bool(self._product_page(str(value))),
                        wait_until="domcontentloaded",
                        timeout=0,
                    )
                )
        except TimeoutError as exc:
            self.collector.status.navigation = "timeout"
            raise BrowserCollectionError(
                "BROWSER_NAVIGATION_TIMEOUT", "页面导航或进入商品页超时"
            ) from exc
        target = self._navigation_target(self.page.url)
        item_id = target.item_id
        if not item_id:
            raise BrowserCollectionError("BROWSER_NAVIGATION_FAILED", "未进入商品详情页")
        self.collector.status.item_id = item_id
        self.collector.status.navigation = "product_page"
        if self.expected_id is not None and self.expected_id != item_id:
            raise BrowserCollectionError("BROWSER_PRODUCT_ID_MISMATCH", "导航商品 ID 与输入不一致")
        return item_id

    async def _read_product(self, item_id: str) -> BrowserProductResult:
        """Wait for a matching response and validate its body within the response deadline."""
        try:
            async with asyncio.timeout(self.collector.response_timeout):
                while True:
                    candidate, response = await self._guarded(self.responses.get())
                    if candidate != item_id:
                        continue
                    self.collector.status.http_status = response.status
                    self.error_response = ("product_response", response)
                    if not 200 <= response.status < 300:
                        raise BrowserCollectionError(
                            "BROWSER_INVALID_RESPONSE", "商品响应 HTTP 状态异常"
                        )
                    try:
                        self.body_attempted = True
                        self.body_status = "timeout"
                        body = await self._guarded(response.body())
                        self.received_body = body
                    except PlaywrightError as exc:
                        self.body_status = "read_error"
                        raise BrowserCollectionError(
                            "BROWSER_PRODUCT_RESPONSE_MISSING", "无法读取完整商品响应正文"
                        ) from exc
                    product = self._parse_browser_body(body, item_id)
                    self.product_valid = True
                    # Recheck identity in case the page navigated while reading the body.
                    if self._navigation_target(self.page.url).item_id != item_id:
                        raise BrowserCollectionError(
                            "BROWSER_PRODUCT_ID_MISMATCH", "读取响应时页面商品发生变化"
                        )
                    if await self._guarded(
                        self.page.get_by_text(RESTRICTION_TEXT).filter(visible=True).count()
                    ):
                        raise BrowserCollectionError(
                            "BROWSER_ACCESS_RESTRICTED", "页面要求登录、安全验证或限制访问"
                        )
                    if self.failure.done():
                        raise self.failure.result()
                    return BrowserProductResult(item_id, body, product, response.status)
        except TimeoutError as exc:
            raise BrowserCollectionError(
                "BROWSER_PRODUCT_RESPONSE_TIMEOUT", "等待商品详情响应或正文超时"
            ) from exc

    async def _attach_diagnostic(self, exc: BrowserCollectionError) -> None:
        # Later navigation/restriction failures must not turn a valid product into error evidence.
        if self.error_response is not None and not self.product_valid:
            source, response = self.error_response
            if (
                source == "product_response"
                and self._response_item_id(response) != self.validated_item_id
            ):
                return
            self.collector._log_upstream_detected(response, source, exc.code)
            if self.received_body is not None:
                # Reuse exact bytes already read; never issue a second body read for business errors.
                diagnostic = self.collector._diagnostic(response, source, self.received_body)
            elif self.body_attempted:
                diagnostic = self.collector._diagnostic(
                    response, source, capture_status=self.body_status
                )
            else:
                try:
                    diagnostic = await self.collector._capture_error(
                        response, source, lambda: self.page_closed
                    )
                except asyncio.CancelledError:
                    self.collector._log_diagnostic(
                        self.collector._diagnostic(response, source, capture_status="unavailable")
                    )
                    raise
            exc.upstream_diagnostic = diagnostic
            self.collector._log_diagnostic(diagnostic)

    async def _close_watchers(self) -> None:
        """Remove callbacks before cancelling tasks, leaving resource escalation to the owner."""

        async def remove_listeners() -> None:
            failed = False
            listeners = list(self._page_listeners)
            if self.cdp is not None and self._paused_listener is not None:
                listeners.append((self.cdp, "Fetch.requestPaused", self._paused_listener))
            for emitter, name, callback in listeners:
                try:
                    emitter.remove_listener(name, callback)
                except Exception:
                    failed = True
            if failed:
                raise RuntimeError("Listener removal failed")

        await self.collector.cleanup.run("listeners_remove", remove_listeners())
        # Snapshot only after listeners stop scheduling work. Timed-out tasks remain owned by
        # CleanupTracker so Context/Browser shutdown can release them and reap them later.
        snapshot = tuple(self.tasks)

        async def cancel_watchers() -> None:
            for task in snapshot:
                if not task.done() and not task.cancelling():
                    task.cancel()

        async def join_watchers() -> None:
            if snapshot:
                await asyncio.wait(snapshot)

        await self.collector.cleanup.run("watchers_cancel", cancel_watchers())
        await self.collector.cleanup.run("watchers_join", join_watchers())
        if self.cdp is not None:
            await self.collector.cleanup.run("cdp_detach", self.cdp.detach())
