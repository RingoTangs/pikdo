"""One process-local Browser with isolated, bounded single-product collections."""

import asyncio
from collections.abc import Callable

from playwright.async_api import Browser, Playwright, async_playwright

from xhs.browser_cleanup import CleanupFailure, CleanupTracker, event
from xhs.browser_collector import (
    BrowserCollectionError,
    BrowserProductCollector,
    BrowserProductResult,
    finish_cleanup,
    navigation_input,
)
from xhs.browser_config import BrowserConfig as BrowserConfig
from xhs.browser_contracts import ProductCollectionService as ProductCollectionService
from xhs.browser_contracts import ReadinessSnapshot as ReadinessSnapshot


class BrowserManager:
    def __init__(
        self,
        config: BrowserConfig | None = None,
        *,
        playwright_factory: Callable = async_playwright,
        collector_factory: Callable = BrowserProductCollector,
    ) -> None:
        self.config = config or BrowserConfig()
        self._playwright_factory = playwright_factory
        self._collector_factory = collector_factory
        self._playwright: Playwright | None = None
        self._browser: Browser | None = None
        self._state = "new"
        self._semaphore = asyncio.Semaphore(self.config.max_concurrent_collections)
        self._workers: set[asyncio.Task] = set()
        self._lifecycle_lock = asyncio.Lock()
        self._stop_task: asyncio.Task | None = None
        self._close_task: asyncio.Task | None = None
        self._resource_healthy = True
        self._driver_stopped = False
        self._retired_collectors: set[BrowserProductCollector] = set()
        self.cleanup = CleanupTracker(
            self.config.cleanup_timeout, "manager", self._on_manager_cleanup_failure
        )

    def _on_manager_cleanup_failure(self, failure: CleanupFailure) -> None:
        self._resource_healthy = False
        if self._state not in {"restricted", "stopping", "stopped"}:
            self._set_state("unavailable")

    def _set_state(self, state: str) -> None:
        previous = self._state
        self._state = state
        if previous != state:
            event(
                "browser.manager.state_changed",
                previous=previous,
                state=state,
                resource_healthy=self._resource_healthy,
            )

    @property
    def state(self) -> str:
        return self._state

    def readiness_snapshot(self) -> ReadinessSnapshot:
        """Observe process-local state without transitioning it or allocating resources."""
        state = self._state if isinstance(self._state, str) else "unknown"
        healthy = self._resource_healthy is True
        browser = self._browser
        try:
            connected = browser is not None and browser.is_connected() is True
        except Exception:
            connected = False
        reasons = {
            "new": "not_started",
            "starting": "starting",
            "restricted": "access_restricted",
            "stopping": "stopping",
            "stopped": "stopped",
        }
        if state == "ready":
            reason = (
                "resource_unhealthy"
                if not healthy
                else "browser_disconnected"
                if not connected
                else None
            )
        elif state == "unavailable":
            reason = "resource_unhealthy" if not healthy else "browser_unavailable"
        elif state in reasons:
            reason = reasons[state]
        else:
            state, reason = "unknown", "browser_unavailable"
        return ReadinessSnapshot(
            state, state == "ready" and healthy and connected, healthy, connected, reason
        )

    async def start(self) -> None:
        async with self._lifecycle_lock:
            if self._state == "ready":
                return
            if self._state != "new":
                raise BrowserCollectionError("BROWSER_UNAVAILABLE", "浏览器管理器不可重新启动")
            self._set_state("starting")

            async def initialize() -> None:
                async with asyncio.timeout(self.config.navigation_timeout):
                    self._playwright = await self._playwright_factory().start()
                    options = {
                        "headless": self.config.headless,
                        "timeout": self.config.navigation_timeout * 1000,
                    }
                    if self.config.channel:
                        options["channel"] = self.config.channel
                    self._browser = await self._playwright.chromium.launch(**options)
                    self._browser.on("disconnected", self._on_disconnected)
                    if not self._browser.is_connected():
                        raise BrowserCollectionError("BROWSER_LAUNCH_FAILED", "浏览器启动后已断开")

            initialization = asyncio.create_task(initialize())
            try:
                await asyncio.shield(initialization)
                self._set_state("ready")
            except BaseException as exc:
                if not initialization.done():
                    try:
                        await finish_cleanup(initialization)
                    except (Exception, asyncio.CancelledError):
                        pass
                self._set_state("unavailable")
                await finish_cleanup(asyncio.create_task(self._release_resources()))
                if not isinstance(exc, Exception):
                    raise
                raise BrowserCollectionError(
                    "BROWSER_LAUNCH_FAILED", "无法初始化生产浏览器，请检查二进制及运行权限"
                ) from None

    def _on_disconnected(self) -> None:
        if self._close_task is None and self._state not in {"stopping", "stopped", "restricted"}:
            self._resource_healthy = False
            self._terminate("unavailable")
            self._schedule_close()

    def _check_ready(self) -> None:
        if self._state == "ready" and (self._browser is None or not self._browser.is_connected()):
            self._terminate("unavailable", asyncio.current_task())
        if self._state == "restricted":
            raise BrowserCollectionError(
                "BROWSER_ACCESS_RESTRICTED", "此前访问受限，已停止浏览器采集"
            )
        if self._state != "ready" or self._browser is None or not self._browser.is_connected():
            raise BrowserCollectionError("BROWSER_UNAVAILABLE", "浏览器采集服务不可用")

    def _terminate(self, state: str, origin: asyncio.Task | None = None) -> None:
        if self._state in {"stopping", "stopped"}:
            return
        if state == "unavailable":
            self._resource_healthy = False
        # Restriction stays latched even when cleanup also makes the resource unhealthy.
        if self._state != "restricted":
            self._set_state(state)
        for task in tuple(self._workers):
            if task is not origin and not task.done() and not task.cancelling():
                task.cancel()

    def _on_cleanup_failure(
        self, failure: CleanupFailure, origin: asyncio.Task, collector: BrowserProductCollector
    ) -> None:
        self._resource_healthy = False
        event(
            "browser.manager.resource_unhealthy",
            warning=True,
            collection_id=collector.collection_id,
            state=self._state,
            cleanup_error=failure.error_code,
            stage=failure.stage,
            outcome=failure.outcome,
        )
        self._retired_collectors.add(collector)
        self._terminate("unavailable", origin)
        self._schedule_close()

    async def collect(self, raw: str) -> BrowserProductResult:
        navigation_input(raw)  # Invalid inputs never allocate a Context or queue slot.
        self._check_ready()
        worker = asyncio.create_task(self._run(raw))
        self._workers.add(worker)
        try:
            return await asyncio.shield(worker)
        except asyncio.CancelledError:
            if not worker.done() and not worker.cancelling():
                worker.cancel()
            try:
                await finish_cleanup(worker)
            except (asyncio.CancelledError, BrowserCollectionError):
                pass
            # Manager cancellation differs from an HTTP caller cancelling its own request.
            if asyncio.current_task().cancelling():
                raise
            self._check_ready()
            raise
        finally:
            self._workers.discard(worker)

    async def _run(self, raw: str) -> BrowserProductResult:
        """Hold the permit through collection cleanup and any required browser teardown."""
        acquired = False
        collector = None
        try:
            try:
                async with asyncio.timeout(self.config.queue_timeout):
                    await self._semaphore.acquire()
                acquired = True
            except TimeoutError:
                # A latched restriction/unavailability takes precedence over queue timeout.
                self._check_ready()
                raise BrowserCollectionError("BROWSER_BUSY", "等待浏览器采集许可超时") from None
            self._check_ready()
            origin = asyncio.current_task()
            collector = self._collector_factory(
                navigation_timeout=self.config.navigation_timeout,
                response_timeout=self.config.response_timeout,
                cleanup_timeout=self.config.cleanup_timeout,
                error_capture_timeout=self.config.error_capture_timeout,
                max_error_body_bytes=self.config.max_error_body_bytes,
                on_access_restricted=lambda: self._terminate("restricted", origin),
                on_cleanup_failure=lambda failure: self._on_cleanup_failure(
                    failure, origin, collector
                ),
            )
            result = await collector.collect_with_browser(raw, self._browser)
            self._check_ready()
            return result
        except BrowserCollectionError as exc:
            if exc.code == "BROWSER_ACCESS_RESTRICTED":
                self._terminate("restricted", asyncio.current_task())
            elif exc.code == "BROWSER_UNAVAILABLE":
                self._terminate("unavailable", asyncio.current_task())
                await self._close_browser()
            elif collector is None or not collector.cleanup.failures:
                try:
                    self._check_ready()
                except BrowserCollectionError as state_error:
                    state_error.upstream_diagnostic = exc.upstream_diagnostic
                    raise state_error from None
            raise
        finally:
            try:
                if not self._resource_healthy:
                    await finish_cleanup(asyncio.create_task(self._close_browser()))
            finally:
                # Release only an acquired permit, including caller cancellation during cleanup.
                if acquired:
                    self._semaphore.release()

    def _schedule_close(self) -> None:
        async def close() -> None:
            if self._browser is not None:
                closed = await self.cleanup.run("browser_close", self._browser.close())
                if not closed and self._playwright is not None:
                    # A failed Browser.close cannot confirm Context/task reclamation.
                    self._driver_stopped = await self.cleanup.run(
                        "playwright_stop", self._playwright.stop()
                    )
            for collector in tuple(self._retired_collectors):
                await collector.reap_pending()

        if self._close_task is None:
            # All failure paths share one teardown; its disconnect event is expected.
            self._close_task = asyncio.create_task(close())

    async def _close_browser(self) -> None:
        self._schedule_close()
        await finish_cleanup(self._close_task)

    async def _release_resources(self) -> None:
        await self._close_browser()
        stopped = self._driver_stopped
        if self._playwright is not None and not stopped:
            stopped = await self.cleanup.run("playwright_stop", self._playwright.stop())
            self._driver_stopped = stopped
        for collector in tuple(self._retired_collectors):
            await collector.reap_pending()
            if not collector.cleanup.pending:
                self._retired_collectors.discard(collector)
        # Retain unconfirmed resources so explicit stop can finish failed transport teardown.
        if stopped or not self.cleanup.failures:
            self._browser = None
            self._playwright = None
        if self.cleanup.pending:
            await self.cleanup.run("watchers_join", self.cleanup.join())

    async def stop(self) -> None:
        async def shutdown() -> None:
            async with self._lifecycle_lock:
                self._set_state("stopping")
                workers = tuple(self._workers)
                for task in workers:
                    if not task.done() and not task.cancelling():
                        task.cancel()
                if workers:
                    # Context acquisition and cleanup are bounded by collector deadlines.
                    await asyncio.gather(*workers, return_exceptions=True)
                await self._release_resources()
                self._set_state("stopped")

        if self._stop_task is None:
            self._stop_task = asyncio.create_task(shutdown())
        await finish_cleanup(self._stop_task)
