"""Offline shared-browser lifecycle and concurrency tests with real collector core."""

import asyncio
from functools import wraps

import pytest
from playwright.async_api import Error as PlaywrightError
from test_browser_collector import ID, SHORT, FakeCDP, FakeContext, FakePage

from xhs.browser import BrowserConfig, BrowserManager
from xhs.browser_collector import BrowserCollectionError
from xhs.errors import InputError


def scenario(function):
    @wraps(function)
    def run(*args, **kwargs):
        return asyncio.run(function(*args, **kwargs))

    return run


class Page(FakePage):
    def __init__(self, gate):
        super().__init__(FakeCDP())
        self.gate = gate
        self.entered = asyncio.Event()

    async def goto(self, *args, **kwargs):
        self.entered.set()
        if self.gate is not None:
            await self.gate.wait()
        return await super().goto(*args, **kwargs)


class Context(FakeContext):
    def __init__(self, gate):
        super().__init__(Page(gate))
        self.close_gate = None
        self.close_started = asyncio.Event()

    async def close(self):
        self.close_started.set()
        if self.close_gate is not None:
            await self.close_gate.wait()
        await super().close()


class Browser:
    def __init__(self):
        self.contexts = []
        self.created = asyncio.Queue()
        self.connected = True
        self.listeners = {}
        self.close_calls = 0
        self.gate = None
        self.creation_gate = None
        self.creation_started = asyncio.Event()

    def on(self, event, handler):
        self.listeners[event] = handler

    def is_connected(self):
        return self.connected

    def disconnect(self):
        self.connected = False
        self.listeners["disconnected"]()

    async def new_context(self):
        self.creation_started.set()
        if self.creation_gate:
            await self.creation_gate.wait()
        context = Context(self.gate)
        self.contexts.append(context)
        self.created.put_nowait(context)
        return context

    async def close(self):
        self.close_calls += 1
        self.disconnect()
        for context in self.contexts:
            # Actual Browser.close also reaps Contexts whose own close failed.
            context.closed = True


class Playwright:
    def __init__(self):
        self.browser = Browser()
        self.chromium = self
        self.launch_calls = 0
        self.started = False
        self.stopped = False
        self.launch_error = None
        self.options = None

    async def start(self):
        self.started = True
        return self

    async def launch(self, **options):
        self.launch_calls += 1
        self.options = options
        if self.launch_error:
            raise self.launch_error
        return self.browser

    async def stop(self):
        self.stopped = True


def build(**options):
    playwright = Playwright()
    defaults = dict(navigation_timeout=1, response_timeout=0.1, cleanup_timeout=0.1)
    defaults.update(options)
    manager = BrowserManager(BrowserConfig(**defaults), playwright_factory=lambda: playwright)
    return manager, playwright


@scenario
async def test_start_stop_idempotent_single_browser_and_context_isolation():
    manager, pw = build(headless=False, channel="chrome")
    await manager.start()
    await manager.start()
    first = await manager.collect(SHORT)
    second = await manager.collect(ID)
    assert first.raw_body == second.raw_body and first.item_id == ID
    assert pw.launch_calls == 1 and len(pw.browser.contexts) == 2
    assert pw.browser.contexts[0] is not pw.browser.contexts[1]
    assert all(context.closed for context in pw.browser.contexts)
    assert pw.browser.close_calls == 0
    assert pw.options["headless"] is False and pw.options["channel"] == "chrome"
    await manager.stop()
    await manager.stop()
    assert manager.state == "stopped" and pw.stopped and pw.browser.close_calls == 1
    with pytest.raises(BrowserCollectionError):
        await manager.start()


@scenario
async def test_launch_failure_cleans_playwright_and_fails_fast():
    manager, pw = build()
    pw.launch_error = PlaywrightError("SECRET")
    with pytest.raises(BrowserCollectionError) as error:
        await manager.start()
    assert error.value.code == "BROWSER_LAUNCH_FAILED" and "SECRET" not in str(error.value)
    assert pw.stopped and manager.state == "unavailable"
    await manager.stop()
    await manager.stop()


@scenario
async def test_concurrency_one_queue_timeout_creates_no_extra_context():
    manager, pw = build(queue_timeout=0.02)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(SHORT))
    context = await pw.browser.created.get()
    await context.page.entered.wait()
    with pytest.raises(BrowserCollectionError) as error:
        await manager.collect(ID)
    assert error.value.code == "BROWSER_BUSY"
    assert len(pw.browser.contexts) == pw.launch_calls == 1
    pw.browser.gate.set()
    await first
    assert (await manager.collect(ID)).item_id == ID
    await manager.stop()


@scenario
async def test_failure_releases_permit_without_latching():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(SHORT))
    context = await pw.browser.created.get()
    context.page.error = PlaywrightError("SECRET")
    context.page.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await first
    assert error.value.code == "BROWSER_NAVIGATION_FAILED"
    assert context.closed and manager.state == "ready"
    assert (await manager.collect(ID)).item_id == ID
    await manager.stop()


@scenario
async def test_caller_cancellation_waits_for_cleanup_before_releasing_permit():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(SHORT))
    context = await pw.browser.created.get()
    context.close_gate = asyncio.Event()
    await context.page.entered.wait()
    first.cancel()
    await context.close_started.wait()
    second = asyncio.create_task(manager.collect(ID))
    await asyncio.sleep(0)
    assert len(pw.browser.contexts) == 1 and not first.done()
    first.cancel()  # A repeated cancellation must not abandon Context cleanup.
    context.close_gate.set()
    with pytest.raises(asyncio.CancelledError):
        await first
    pw.browser.gate.set()
    assert (await second).item_id == ID
    assert all(ctx.closed for ctx in pw.browser.contexts)
    assert not manager._workers
    await manager.stop()


@scenario
async def test_cancel_during_context_creation_does_not_abandon_new_context():
    manager, pw = build()
    pw.browser.creation_gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    await pw.browser.creation_started.wait()
    task.cancel()
    await asyncio.sleep(0)
    task.cancel()
    pw.browser.creation_gate.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert len(pw.browser.contexts) == 1 and pw.browser.contexts[0].closed
    assert not manager._workers
    await manager.stop()


@scenario
async def test_cancel_queued_request_does_not_leak_permit():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(ID))
    await pw.browser.created.get()
    queued = asyncio.create_task(manager.collect(ID))
    await asyncio.sleep(0)
    queued.cancel()
    with pytest.raises(asyncio.CancelledError):
        await queued
    pw.browser.gate.set()
    await first
    assert (await manager.collect(ID)).item_id == ID
    await manager.stop()


@pytest.mark.parametrize("capacity", [1, 2])
@scenario
async def test_restriction_stops_active_and_queued_requests(capacity):
    manager, pw = build(max_concurrent_collections=capacity)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    second = asyncio.create_task(manager.collect(SHORT))
    if capacity == 2:
        peer = await pw.browser.created.get()
        await peer.page.entered.wait()
    else:
        await asyncio.sleep(0)
    context.page.responses[0].status = 403
    context.page.gate = None
    # Wake only the first page; the peer is interrupted by the manager.
    context.page.error = None
    if capacity == 2:
        context.page.gate = asyncio.Event()
        # goto is already awaiting the original shared Event; release both, but delay peer.
        peer.page.hang = True
    pw.browser.gate.set()
    results = await asyncio.gather(first, second, return_exceptions=True)
    assert [result.code for result in results] == ["BROWSER_ACCESS_RESTRICTED"] * 2
    assert manager.state == "restricted"
    count = len(pw.browser.contexts)
    with pytest.raises(BrowserCollectionError) as error:
        await manager.collect(ID)
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    assert len(pw.browser.contexts) == count and all(ctx.closed for ctx in pw.browser.contexts)
    await manager.stop()


@scenario
async def test_disconnected_browser_invalidates_running_queued_and_future_requests():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(ID))
    await pw.browser.created.get()
    queued = asyncio.create_task(manager.collect(ID))
    await asyncio.sleep(0)
    pw.browser.disconnect()
    results = await asyncio.gather(first, queued, return_exceptions=True)
    assert [error.code for error in results] == ["BROWSER_UNAVAILABLE"] * 2
    with pytest.raises(BrowserCollectionError) as error:
        await manager.collect(ID)
    assert error.value.code == "BROWSER_UNAVAILABLE" and pw.launch_calls == 1
    await manager.stop()


@scenario
async def test_shutdown_cancels_requests_and_reaps_contexts():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    queued = asyncio.create_task(manager.collect(ID))
    await asyncio.sleep(0)
    await manager.stop()
    results = await asyncio.gather(first, queued, return_exceptions=True)
    assert [error.code for error in results] == ["BROWSER_UNAVAILABLE"] * 2
    assert context.closed and pw.stopped and pw.browser.close_calls == 1


@scenario
async def test_context_cleanup_failure_invalidates_manager_and_closes_browser():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.close_error = PlaywrightError("SECRET")
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_UNAVAILABLE" and manager.state == "unavailable"
    assert pw.browser.close_calls == 1
    await manager.stop()


@scenario
async def test_local_input_validation_precedes_state_and_queue_checks():
    manager, pw = build()
    with pytest.raises(InputError):
        await manager.collect("https://127.0.0.1/?token=SECRET")
    assert not pw.started
    await manager.stop()


@pytest.mark.parametrize(
    "options",
    [
        {"headless": "true"},
        {"channel": "stealth"},
        {"max_concurrent_collections": 0},
        {"max_concurrent_collections": True},
        {"queue_timeout": 0},
        {"response_timeout": float("inf")},
        {"cleanup_timeout": float("nan")},
    ],
)
def test_config_validation(options):
    with pytest.raises(ValueError):
        BrowserConfig(**options)


@scenario
async def test_cleanup_timeout_invalidates_browser_and_preserves_permit_cleanup():
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.close_gate = asyncio.Event()
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_UNAVAILABLE"
    assert context.closed and pw.browser.close_calls == 1
    assert not manager._workers
    await manager.stop()


@scenario
async def test_startup_cancellation_cleans_late_created_browser():
    manager, pw = build()
    gate = asyncio.Event()
    entered = asyncio.Event()
    original_launch = pw.launch

    async def launch(**options):
        entered.set()
        await gate.wait()
        return await original_launch(**options)

    pw.launch = launch
    task = asyncio.create_task(manager.start())
    await entered.wait()
    task.cancel()
    await asyncio.sleep(0)
    task.cancel()
    gate.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert pw.stopped and pw.browser.close_calls == 1 and manager.state == "unavailable"
    await manager.stop()


@scenario
async def test_shutdown_cancellation_still_finishes_cleanup():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    collection = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.close_gate = asyncio.Event()
    shutdown = asyncio.create_task(manager.stop())
    await context.close_started.wait()
    shutdown.cancel()
    context.close_gate.set()
    with pytest.raises(asyncio.CancelledError):
        await shutdown
    results = await asyncio.gather(collection, return_exceptions=True)
    assert results[0].code == "BROWSER_UNAVAILABLE"
    assert pw.stopped and context.closed and manager.state == "stopped"
    await manager.stop()


@scenario
async def test_disconnection_detected_even_before_event_callback():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.page.error = PlaywrightError("SECRET closed browser")
    pw.browser.connected = False  # Simulate the event notification arriving later.
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_UNAVAILABLE" and manager.state == "unavailable"
    assert context.closed
    await manager.stop()
