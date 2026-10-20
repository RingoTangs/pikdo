"""Bounded cleanup and fixed-field diagnostics shared by browser resource owners."""

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

logger = logging.getLogger("xhs.browser")


@dataclass(frozen=True)
class CleanupFailure:
    stage: str
    outcome: str
    error_code: str = "BROWSER_UNAVAILABLE"


def event(name: str, *, warning: bool = False, **fields: str | int | float | bool | None) -> None:
    # Callers supply only fixed classifications, generated IDs and numeric measurements.
    message = " ".join([f"event={name}", *(f"{key}={value}" for key, value in fields.items())])
    logger.log(logging.WARNING if warning else logging.INFO, message)


async def finish_cleanup[T](task: asyncio.Task[T]) -> T:
    """Finish owned cleanup; caller cancellation wins even if cleanup also fails."""
    cancelled = False
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            cancelled = True
        except Exception:
            break
    if cancelled:
        # Retrieve the secondary exception without exposing it or replacing cancellation.
        if not task.cancelled():
            task.exception()
        raise asyncio.CancelledError
    return task.result()


class CleanupTracker:
    def __init__(
        self,
        timeout: float,
        collection_id: str,
        on_failure: Callable[[CleanupFailure], None] | None = None,
    ) -> None:
        self.timeout = timeout
        self.collection_id = collection_id
        self.on_failure = on_failure
        self.failures: list[CleanupFailure] = []
        self.pending: set[asyncio.Task] = set()

    def own(self, task: asyncio.Task) -> asyncio.Task:
        self.pending.add(task)

        def done(completed: asyncio.Task) -> None:
            self.pending.discard(completed)
            if not completed.cancelled():
                completed.exception()  # Do not leak unhandled task tracebacks with sensitive URLs.

        task.add_done_callback(done)
        return task

    def failed(self, stage: str, outcome: str, elapsed_ms: float) -> None:
        failure = CleanupFailure(stage, outcome)
        self.failures.append(failure)
        event(
            "browser.cleanup.failed",
            warning=True,
            collection_id=self.collection_id,
            stage=stage,
            outcome=outcome,
            cleanup_error=failure.error_code,
            elapsed_ms=elapsed_ms,
        )
        if self.on_failure:
            self.on_failure(failure)

    async def run(self, stage: str, operation: Awaitable) -> bool:
        started = time.monotonic()
        event("browser.cleanup.started", collection_id=self.collection_id, stage=stage)
        task = self.own(asyncio.ensure_future(operation))
        if stage == "watchers_join":
            # Join wrappers only wait on other tasks. Tracking them would let concurrent
            # Context/Browser shutdown joins wait on one another instead of resources.
            self.pending.discard(task)
        done, _ = await asyncio.wait({task}, timeout=self.timeout)
        outcome = "completed"
        if not done:
            task.cancel()
            outcome = "timeout"
        elif task.cancelled():
            outcome = "cancelled"
        elif task.exception() is not None:
            outcome = "error"
        elapsed_ms = round((time.monotonic() - started) * 1000, 3)
        if outcome != "completed":
            self.failed(stage, outcome, elapsed_ms)
        event(
            "browser.cleanup.completed",
            collection_id=self.collection_id,
            stage=stage,
            outcome=outcome,
            elapsed_ms=elapsed_ms,
        )
        return outcome == "completed"

    async def join(self) -> None:
        pending = {
            task for task in self.pending if not task.done() and task is not asyncio.current_task()
        }
        if pending:
            # asyncio.wait does not propagate join cancellation into the owned tasks.
            await asyncio.wait(pending)
