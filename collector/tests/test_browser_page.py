"""Regression checks for page-session isolation and legacy runtime injection."""

import asyncio

import pytest
from test_browser_collector import ID, RAW, SHORT, FakePlaywright, Response
from test_browser_manager import Browser

from xhs import browser_collector as module
from xhs.browser_collector import BrowserCollectionError, BrowserProductCollector
from xhs.browser_page import PageCollectionSession


def test_legacy_helpers_are_resolved_during_collection(monkeypatch):
    fake = FakePlaywright()
    collector = BrowserProductCollector()
    monkeypatch.setattr(module, "async_playwright", lambda: fake)
    monkeypatch.setattr(module, "navigation_input", lambda raw: (SHORT, None))
    calls = []
    original_goto = fake.page.goto

    def record(name, original):
        def replacement(*args):
            calls.append(name)
            return original(*args)

        return replacement

    async def goto(*args, **kwargs):
        # The Session already exists: callbacks must not have captured the old functions.
        for name in (
            "navigation_target",
            "response_item_id",
            "parse_browser_body",
            "_product_page",
        ):
            monkeypatch.setattr(module, name, record(name, getattr(module, name)))
        return await original_goto(*args, **kwargs)

    fake.page.goto = goto
    result = asyncio.run(collector.collect("test-only entry"))
    assert result.raw_body is RAW
    assert set(calls) == {
        "navigation_target",
        "response_item_id",
        "parse_browser_body",
        "_product_page",
    }
    assert fake.context.closed and fake.browser.closed and fake.stopped


def test_listener_identity_order_and_cleanup_stages(monkeypatch):
    fake = FakePlaywright()
    collector = BrowserProductCollector()
    monkeypatch.setattr(module, "async_playwright", lambda: fake)
    registered, removed, stages = [], [], []

    def instrument(emitter):
        original_on = emitter.on
        original_remove = emitter.remove_listener

        def on(name, callback):
            registered.append((emitter, name, callback))
            original_on(name, callback)

        def remove(name, callback):
            removed.append((emitter, name, callback))
            original_remove(name, callback)

        emitter.on = on
        emitter.remove_listener = remove

    instrument(fake.page)
    instrument(fake.cdp)
    original_run = collector.cleanup.run

    async def run(stage, operation):
        stages.append(stage)
        return await original_run(stage, operation)

    collector.cleanup.run = run
    asyncio.run(collector.collect(SHORT))
    assert [name for _, name, _ in registered] == [
        "response",
        "framenavigated",
        "close",
        "crash",
        "Fetch.requestPaused",
    ]
    assert len(registered) == len(removed)
    for (emitter, name, callback), (removed_emitter, removed_name, removed_callback) in zip(
        registered, removed, strict=True
    ):
        assert removed_emitter is emitter and removed_name == name
        assert removed_callback is callback
    assert stages == [
        "listeners_remove",
        "watchers_cancel",
        "watchers_join",
        "cdp_detach",
        "context_close",
        "browser_close",
        "playwright_stop",
    ]
    assert not collector.cleanup.pending


def test_reused_collector_has_fresh_page_failure_and_diagnostic_state():
    async def exercise():
        browser = Browser()
        collector = BrowserProductCollector()
        original_create = browser.new_context
        cases = iter(("closed", "success", "invalid"))
        raw_error = b"invalid JSON for this collection"

        async def create():
            context = await original_create()
            case = next(cases)
            context.page.close_early = case == "closed"
            if case == "invalid":
                context.page.responses = [Response(raw_error)]
            return context

        browser.new_context = create
        with pytest.raises(BrowserCollectionError) as closed:
            await collector.collect_with_browser(SHORT, browser)
        assert closed.value.code == "BROWSER_PRODUCT_RESPONSE_MISSING"
        result = await collector.collect_with_browser(SHORT, browser)
        assert result.raw_body is RAW
        with pytest.raises(BrowserCollectionError) as invalid:
            await collector.collect_with_browser(SHORT, browser)
        assert invalid.value.code == "BROWSER_INVALID_RESPONSE"
        assert invalid.value.upstream_diagnostic.raw_body is raw_error
        assert all(context.closed for context in browser.contexts)
        assert not collector.cleanup.pending and browser.close_calls == 0

    asyncio.run(exercise())


def test_concurrent_collectors_keep_response_and_error_evidence_isolated():
    async def exercise():
        browser = Browser()
        collectors = [BrowserProductCollector(), BrowserProductCollector()]
        original_create = browser.new_context
        raw_error = b' { "success": false, "code": "first" }\n'
        release = asyncio.Event()
        reading = 0

        async def create():
            context = await original_create()
            raw = raw_error if len(browser.contexts) == 1 else RAW
            response = Response(raw)

            async def body():
                nonlocal reading
                reading += 1
                if reading == 2:
                    release.set()
                await release.wait()
                return raw

            response.body = body
            context.page.responses = [response]
            return context

        browser.new_context = create
        async with asyncio.timeout(1):
            first, second = await asyncio.gather(
                *(collector.collect_with_browser(SHORT, browser) for collector in collectors),
                return_exceptions=True,
            )
        assert isinstance(first, BrowserCollectionError)
        assert first.code == "BROWSER_INVALID_RESPONSE"
        assert first.upstream_diagnostic.raw_body is raw_error
        assert second.raw_body is RAW and second.item_id == ID
        assert all(context.closed for context in browser.contexts)
        assert all(not collector.cleanup.pending for collector in collectors)
        assert browser.close_calls == 0

    asyncio.run(exercise())


def test_guarded_failure_wins_when_operation_also_failed():
    async def exercise():
        fake = FakePlaywright()
        collector = BrowserProductCollector()
        session = PageCollectionSession(
            collector,
            fake.context,
            fake.page,
            SHORT,
            None,
            navigation_target=module.navigation_target,
            response_item_id=module.response_item_id,
            parse_browser_body=module.parse_browser_body,
            product_page=module._product_page,
        )
        session._fail("BROWSER_ACCESS_RESTRICTED", "fixed restriction")
        operation = asyncio.get_running_loop().create_future()
        operation.set_exception(RuntimeError("secondary failure"))
        with pytest.raises(BrowserCollectionError) as error:
            await session._guarded(operation)
        assert error.value is session.failure.result()
        await asyncio.sleep(0)  # CleanupTracker retrieves the operation's secondary exception.
        assert not collector.cleanup.pending

    asyncio.run(exercise())
