"""One process-local Browser with isolated, bounded single-product collections."""

import asyncio
import math
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from playwright.async_api import Browser, Playwright, async_playwright
from playwright.async_api import Error as PlaywrightError

from xhs.browser_collector import (
    BrowserCollectionError,
    BrowserProductCollector,
    BrowserProductResult,
    finish_cleanup,
    navigation_input,
)


@dataclass(frozen=True)
class BrowserConfig:
    headless: bool = True
    channel: str | None = None
    max_concurrent_collections: int = 1
    queue_timeout: float = 5.0
    navigation_timeout: float = 30.0
    response_timeout: float = 15.0
    cleanup_timeout: float = 5.0

    def __post_init__(self) -> None:
        if type(self.headless) is not bool or self.channel not in (None, "chrome"):
            raise ValueError("Only headless bool and the optional chrome channel are supported")
        if type(self.max_concurrent_collections) is not int or self.max_concurrent_collections < 1:
            raise ValueError("max_concurrent_collections must be a positive integer")
        for value in (
            self.queue_timeout,
            self.navigation_timeout,
            self.response_timeout,
            self.cleanup_timeout,
        ):
            if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                raise ValueError("timeouts must be finite positive seconds")


class ProductCollectionService(Protocol):
    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def collect(self, raw: str) -> BrowserProductResult: ...


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

    @property
    def state(self) -> str:
        return self._state

    async def start(self) -> None:
        async with self._lifecycle_lock:
            if self._state == "ready":
                return
            if self._state != "new":
                raise BrowserCollectionError("BROWSER_UNAVAILABLE", "浏览器管理器不可重新启动")
            self._state = "starting"

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
                self._state = "ready"
            except BaseException as exc:
                if not initialization.done():
                    try:
                        await finish_cleanup(initialization)
                    except (Exception, asyncio.CancelledError):
                        pass
                self._state = "unavailable"
                await finish_cleanup(asyncio.create_task(self._release_resources()))
                if not isinstance(exc, Exception):
                    raise
                raise BrowserCollectionError(
                    "BROWSER_LAUNCH_FAILED", "无法初始化生产浏览器，请检查二进制及运行权限"
                ) from None

    def _on_disconnected(self) -> None:
        if self._state not in {"stopping", "stopped", "restricted"}:
            self._terminate("unavailable")

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
        if self._state != "restricted":
            self._state = state
        for task in tuple(self._workers):
            if task is not origin and not task.done() and not task.cancelling():
                task.cancel()

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
        acquired = False
        try:
            try:
                async with asyncio.timeout(self.config.queue_timeout):
                    await self._semaphore.acquire()
                acquired = True
            except TimeoutError:
                self._check_ready()
                raise BrowserCollectionError("BROWSER_BUSY", "等待浏览器采集许可超时") from None
            self._check_ready()
            origin = asyncio.current_task()
            collector = self._collector_factory(
                navigation_timeout=self.config.navigation_timeout,
                response_timeout=self.config.response_timeout,
                cleanup_timeout=self.config.cleanup_timeout,
                on_access_restricted=lambda: self._terminate("restricted", origin),
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
            else:
                self._check_ready()
            raise
        finally:
            if acquired:
                self._semaphore.release()

    async def _close_browser(self) -> None:
        async def close() -> None:
            if self._browser is not None:
                try:
                    async with asyncio.timeout(self.config.cleanup_timeout):
                        await self._browser.close()
                except (PlaywrightError, TimeoutError):
                    pass

        if self._close_task is None:
            self._close_task = asyncio.create_task(close())
        await finish_cleanup(self._close_task)

    async def _release_resources(self) -> None:
        try:
            await self._close_browser()
        finally:
            if self._playwright is not None:
                try:
                    async with asyncio.timeout(self.config.cleanup_timeout):
                        await self._playwright.stop()
                except (PlaywrightError, TimeoutError):
                    pass
            self._browser = None
            self._playwright = None

    async def stop(self) -> None:
        async def shutdown() -> None:
            async with self._lifecycle_lock:
                self._state = "stopping"
                workers = tuple(self._workers)
                for task in workers:
                    if not task.done() and not task.cancelling():
                        task.cancel()
                if workers:
                    # Context acquisition and cleanup are bounded by collector deadlines.
                    await asyncio.gather(*workers, return_exceptions=True)
                await self._release_resources()
                self._state = "stopped"

        if self._stop_task is None:
            self._stop_task = asyncio.create_task(shutdown())
        await finish_cleanup(self._stop_task)
