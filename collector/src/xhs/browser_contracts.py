"""Shared browser values and service contract, independent of resource owners."""

from dataclasses import dataclass, field
from typing import Literal, Protocol

from xhs.schemas import Product
from xhs.upstream_diagnostics import UpstreamDiagnostic


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


@dataclass(frozen=True, slots=True)
class ReadinessSnapshot:
    state: str
    ready: bool
    resource_healthy: bool
    browser_connected: bool
    reason: str | None


class ProductCollectionService(Protocol):
    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def collect(self, raw: str) -> BrowserProductResult: ...
    def readiness_snapshot(self) -> ReadinessSnapshot: ...
