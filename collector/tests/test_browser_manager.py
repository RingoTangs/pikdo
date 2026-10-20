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


@pytest.mark.parametrize("fault", ["detach_timeout", "context_error", "context_timeout"])
@scenario
async def test_461_and_cleanup_failure_latches_restriction_and_reaps_browser(fault, caplog):
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    first = asyncio.create_task(manager.collect(SHORT))
    context = await pw.browser.created.get()
    context.page.responses[0].status = 461

    async def blocked():
        await asyncio.Event().wait()

    if fault == "detach_timeout":
        context.page.cdp.detach = blocked
    elif fault == "context_error":
        context.close_error = PlaywrightError("Cookie=SECRET")
    else:
        context.close_gate = asyncio.Event()
    queued = asyncio.create_task(manager.collect(ID))
    pw.browser.gate.set()
    results = await asyncio.gather(first, queued, return_exceptions=True)
    assert [exc.code for exc in results] == ["BROWSER_ACCESS_RESTRICTED"] * 2
    assert manager.state == "restricted" and not manager._resource_healthy
    assert not pw.browser.connected and pw.browser.close_calls == 1
    assert context.closed and len(pw.browser.contexts) == 1
    assert manager._semaphore._value == 1 and not manager._workers
    with pytest.raises(BrowserCollectionError) as error:
        await manager.collect(ID)
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    assert "SECRET" not in caplog.text
    await manager.stop()
    assert pw.stopped and manager.state == "stopped"
    assert not manager._retired_collectors and not manager.cleanup.pending


@scenario
async def test_navigation_primary_not_overwritten_by_owned_browser_disconnect():
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.page.error = PlaywrightError("SECRET navigation")
    context.close_error = PlaywrightError("SECRET cleanup")
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_NAVIGATION_FAILED"
    assert manager.state == "unavailable" and context.closed
    assert pw.browser.close_calls == 1 and manager._semaphore._value == 1
    with pytest.raises(BrowserCollectionError) as next_error:
        await manager.collect(ID)
    assert next_error.value.code == "BROWSER_UNAVAILABLE"
    await manager.stop()


@scenario
async def test_caller_cancellation_and_context_timeout_invalidates_and_reaps():
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.close_gate = asyncio.Event()
    await context.page.entered.wait()
    task.cancel()
    await context.close_started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert context.closed and not pw.browser.connected
    assert manager.state == "unavailable"
    assert not manager._workers and manager._semaphore._value == 1
    await manager.stop()


@scenario
async def test_failed_browser_close_escalates_to_playwright_stop_and_blocks_reuse(caplog):
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    original_stop = pw.stop

    async def broken_close():
        pw.browser.close_calls += 1
        raise PlaywrightError("SECRET Cookie=SECRET")

    async def stop():
        await original_stop()
        pw.browser.connected = False
        for ctx in pw.browser.contexts:
            ctx.closed = True

    pw.browser.close = broken_close
    pw.stop = stop
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.page.responses[0].status = 461
    context.close_error = PlaywrightError("SECRET")
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    assert pw.stopped and context.closed and not pw.browser.connected
    assert "stage=browser_close outcome=error" in caplog.text
    assert "SECRET" not in caplog.text
    await manager.stop()
    assert pw.browser.close_calls == 1


@scenario
async def test_watchers_waiting_for_browser_shutdown_are_reaped_without_join_cycle():
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    watcher_started = asyncio.Event()
    browser_closed = asyncio.Event()
    original_close = pw.browser.close

    async def watch(**options):
        watcher_started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            await browser_closed.wait()

    async def close():
        await original_close()
        browser_closed.set()

    context.page.wait_for = watch
    context.page.responses[0].status = 461
    context.close_error = PlaywrightError("SECRET")
    pw.browser.close = close
    # Navigation cannot emit responses until its restriction watcher is running.
    original_goto = context.page.goto

    async def goto(*args, **kwargs):
        await watcher_started.wait()
        return await original_goto(*args, **kwargs)

    context.page.goto = goto
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await asyncio.wait_for(task, 0.5)
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    collectors = tuple(manager._retired_collectors)
    assert collectors and all(not collector.cleanup.pending for collector in collectors)
    assert any(
        f.stage == "watchers_join" for collector in collectors for f in collector.cleanup.failures
    )
    assert context.closed and pw.browser.close_calls == 1
    await manager.stop()
    assert not manager._retired_collectors and not manager.cleanup.pending


@scenario
async def test_unexpected_disconnect_with_cleanup_failure_stays_unavailable():
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.close_error = PlaywrightError("SECRET")
    await context.page.entered.wait()
    pw.browser.disconnect()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_UNAVAILABLE"
    assert manager.state == "unavailable" and context.closed
    assert manager._semaphore._value == 1 and not manager._workers
    await manager.stop()
    assert pw.stopped


@scenario
async def test_browser_close_timeout_escalation_and_failed_driver_stop_remain_diagnosable(caplog):
    manager, pw = build(cleanup_timeout=0.01)
    pw.browser.gate = asyncio.Event()

    async def blocked_close():
        pw.browser.close_calls += 1
        await asyncio.Event().wait()

    original_stop = pw.stop
    attempts = 0

    async def stop():
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise PlaywrightError("SECRET stop")
        await original_stop()
        pw.browser.connected = False
        for context in pw.browser.contexts:
            context.closed = True

    pw.browser.close = blocked_close
    pw.stop = stop
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    context.page.responses[0].status = 461
    context.close_error = PlaywrightError("SECRET close")
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    assert manager.state == "restricted" and not manager._resource_healthy
    assert manager._browser is pw.browser  # Unconfirmed resources remain known to stop().
    assert "stage=browser_close outcome=timeout" in caplog.text
    assert "stage=playwright_stop outcome=error" in caplog.text
    assert "SECRET" not in caplog.text
    await manager.stop()  # Explicit shutdown can finish the failed transport teardown.
    assert attempts == 2 and pw.stopped and context.closed
    assert not manager.cleanup.pending and manager._browser is None


@scenario
async def test_restricted_is_latched_before_error_body_finishes_and_evidence_survives():
    from test_browser_collector import Response

    manager, pw = build(error_capture_timeout=0.1)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    entered, release = asyncio.Event(), asyncio.Event()
    raw = b' { "code":123, "msg":"error SECRET" }\n'
    response = Response(raw, status=461)

    async def body():
        entered.set()
        await release.wait()
        return raw

    response.body = body
    context.page.responses = [response]
    pw.browser.gate.set()
    await entered.wait()
    assert manager.state == "restricted" and not task.done()
    with pytest.raises(BrowserCollectionError) as rejected:
        await manager.collect(ID)
    assert rejected.value.code == "BROWSER_ACCESS_RESTRICTED"
    assert rejected.value.upstream_diagnostic is None
    assert len(pw.browser.contexts) == 1
    release.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.upstream_diagnostic.raw_body == raw
    assert context.closed and manager._semaphore._value == 1
    await manager.stop()
    assert pw.stopped and not manager._workers


@pytest.mark.parametrize(
    "options",
    [{"error_capture_timeout": 0}, {"max_error_body_bytes": 0}, {"max_error_body_bytes": True}],
)
def test_diagnostic_config_validation(options):
    with pytest.raises(ValueError):
        BrowserConfig(**options)


@scenario
async def test_json_restriction_latches_before_diagnostic_and_cdp_cleanup_finish():
    from test_browser_collector import Response

    manager, pw = build(cleanup_timeout=0.1)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    entered, release = asyncio.Event(), asyncio.Event()
    raw = '{"success":false,"code":123,"msg":"安全验证 SECRET"}'.encode()
    context.page.responses = [Response(raw)]

    async def detach():
        entered.set()
        await release.wait()

    context.page.cdp.detach = detach
    pw.browser.gate.set()
    await entered.wait()
    assert manager.state == "restricted" and not task.done()
    with pytest.raises(BrowserCollectionError):
        await manager.collect(ID)
    release.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_ACCESS_RESTRICTED"
    assert error.value.upstream_diagnostic.http_status == 200
    assert error.value.upstream_diagnostic.business_code == 123
    assert error.value.upstream_diagnostic.raw_body == raw
    await manager.stop()


@scenario
async def test_runtime_state_error_preserves_existing_upstream_diagnostic():
    manager, pw = build()
    pw.browser.gate = asyncio.Event()
    await manager.start()
    task = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()

    async def body():
        pw.browser.connected = False  # Event callback has not arrived yet.
        raise PlaywrightError("SECRET disconnected")

    context.page.responses[0].body = body
    pw.browser.gate.set()
    with pytest.raises(BrowserCollectionError) as error:
        await task
    assert error.value.code == "BROWSER_UNAVAILABLE"
    assert error.value.upstream_diagnostic.http_status == 200
    assert error.value.upstream_diagnostic.capture_status == "read_error"
    assert manager.state == "unavailable" and context.closed
    await manager.stop()


@pytest.mark.parametrize(
    "state,healthy,connection,reason",
    [
        ("new", True, None, "not_started"),
        ("starting", True, None, "starting"),
        ("ready", True, True, None),
        ("ready", False, True, "resource_unhealthy"),
        ("ready", True, None, "browser_disconnected"),
        ("ready", True, False, "browser_disconnected"),
        ("ready", True, "error", "browser_disconnected"),
        ("ready", False, False, "resource_unhealthy"),
        ("restricted", True, True, "access_restricted"),
        ("restricted", False, False, "access_restricted"),
        ("unavailable", False, False, "resource_unhealthy"),
        ("unavailable", True, True, "browser_unavailable"),
        ("stopping", True, True, "stopping"),
        ("stopped", True, True, "stopped"),
        ("SECRET unknown state", True, True, "browser_unavailable"),
    ],
)
def test_readiness_snapshot_mapping_and_read_only_behavior(
    state, healthy, connection, reason, caplog
):
    caplog.set_level("DEBUG", logger="xhs.browser")
    manager, pw = build()
    manager._state = state
    manager._resource_healthy = healthy
    manager._browser = pw.browser if connection is not None else None
    pw.browser.connected = connection

    def error():
        raise PlaywrightError("Cookie=SECRET https://example.test/?token=SECRET")

    if connection == "error":
        pw.browser.is_connected = error
    original = (
        manager._state,
        manager._resource_healthy,
        manager._browser,
        manager._semaphore._value,
        tuple(manager._workers),
        tuple(manager.cleanup.failures),
    )
    for _ in range(3):
        snapshot = manager.readiness_snapshot()
        assert snapshot.ready is (reason is None)
        assert snapshot.reason == reason
        assert snapshot.state == ("unknown" if "SECRET" in state else state)
        assert snapshot.resource_healthy is healthy
        assert snapshot.browser_connected is (connection is True)
        assert "SECRET" not in repr(snapshot)
    assert original == (
        manager._state,
        manager._resource_healthy,
        manager._browser,
        manager._semaphore._value,
        tuple(manager._workers),
        tuple(manager.cleanup.failures),
    )
    assert not pw.started and pw.launch_calls == pw.browser.close_calls == 0
    assert pw.browser.contexts == [] and manager._close_task is None
    assert caplog.records == []


def test_readiness_snapshot_is_immutable_and_contains_only_values():
    from dataclasses import FrozenInstanceError, fields

    manager, _ = build()
    snapshot = manager.readiness_snapshot()
    with pytest.raises(FrozenInstanceError):
        snapshot.ready = True
    assert not hasattr(snapshot, "__dict__")
    assert {field.name for field in fields(snapshot)} == {
        "state",
        "ready",
        "resource_healthy",
        "browser_connected",
        "reason",
    }


@scenario
async def test_snapshot_reads_lifecycle_without_transitions():
    manager, pw = build()
    assert manager.readiness_snapshot().reason == "not_started"
    await manager.start()
    assert manager.readiness_snapshot().ready
    # A disconnected Browser must fail closed even before the callback is delivered.
    pw.browser.connected = False
    assert manager.readiness_snapshot().reason == "browser_disconnected"
    assert manager.state == "ready" and manager._resource_healthy
    await manager.stop()
    assert manager.readiness_snapshot().reason == "stopped"


@scenario
async def test_active_and_queued_collections_do_not_affect_readiness():
    manager, pw = build(queue_timeout=1)
    pw.browser.gate = asyncio.Event()
    await manager.start()
    active = asyncio.create_task(manager.collect(ID))
    context = await pw.browser.created.get()
    await context.page.entered.wait()
    queued = asyncio.create_task(manager.collect(ID))
    await asyncio.sleep(0)
    before = (len(manager._workers), manager._semaphore._value, len(pw.browser.contexts))
    for _ in range(3):
        assert manager.readiness_snapshot().ready
    assert before == (len(manager._workers), manager._semaphore._value, len(pw.browser.contexts))
    assert len(pw.browser.contexts) == 1 and pw.launch_calls == 1
    pw.browser.gate.set()
    await asyncio.gather(active, queued)
    await manager.stop()


@pytest.mark.parametrize("state", [None, [], {"SECRET": True}])
def test_unknown_state_types_fail_closed_without_mutation(state):
    manager, _ = build()
    manager._state = state
    snapshot = manager.readiness_snapshot()
    assert snapshot.state == "unknown" and snapshot.reason == "browser_unavailable"
    assert not snapshot.ready and manager._state is state
