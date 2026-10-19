import json
from decimal import Decimal
from pathlib import Path

import pytest

ITEM_ID = "6ab10b35d528ea0015dd1b7e"


@pytest.fixture
def payload():
    return json.loads(
        (Path(__file__).parents[1] / "examples/response.json").read_text(), parse_float=Decimal
    )


class FakeCollectionService:
    """Explicit API dependency: parsing is real, browser/network execution is fake."""

    def __init__(self, *, payload=None, body: bytes | None = None):
        self.payload = payload
        self.body = body
        self.error = None
        self.calls = []
        self.started = False
        self.stopped = False

    async def start(self):
        self.started = True

    async def stop(self):
        self.stopped = True

    async def collect(self, raw):
        from xhs.browser_collector import BrowserProductResult, navigation_input, parse_browser_body

        self.calls.append(raw)
        if self.error:
            raise self.error
        _, item_id = navigation_input(raw)
        item_id = item_id or ITEM_ID
        body = self.body
        if body is None:
            body = (
                json.dumps(self.payload, default=str).encode()
                if self.payload is not None
                else (Path(__file__).parents[1] / "examples/response.json").read_bytes()
            )
        return BrowserProductResult(item_id, body, parse_browser_body(body, item_id), 200)


def offline_app(*, service=None):
    from app import create_app

    service = service if service is not None else FakeCollectionService()
    return create_app(browser_manager_factory=lambda: service)
