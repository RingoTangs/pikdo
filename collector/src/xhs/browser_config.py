"""Validated configuration for the shared browser manager."""

import math
from dataclasses import dataclass

from xhs.upstream_diagnostics import ERROR_CAPTURE_TIMEOUT, MAX_ERROR_BODY_BYTES


@dataclass(frozen=True)
class BrowserConfig:
    """Limits for a process-local browser and its isolated collections."""

    headless: bool = True
    channel: str | None = None
    # Permit capacity and maximum wait before starting a collection.
    max_concurrent_collections: int = 1
    queue_timeout: float = 5.0
    # Navigation also bounds browser startup and Context/Page acquisition.
    navigation_timeout: float = 30.0
    response_timeout: float = 15.0
    # Applied to each cleanup operation, rather than to the whole shutdown.
    cleanup_timeout: float = 5.0
    # Error evidence only; successful product bodies retain their existing read policy.
    error_capture_timeout: float = ERROR_CAPTURE_TIMEOUT
    max_error_body_bytes: int = MAX_ERROR_BODY_BYTES

    def __post_init__(self) -> None:
        if type(self.headless) is not bool or self.channel not in (None, "chrome"):
            raise ValueError("Only headless bool and the optional chrome channel are supported")
        if type(self.max_concurrent_collections) is not int or self.max_concurrent_collections < 1:
            raise ValueError("max_concurrent_collections must be a positive integer")
        if type(self.max_error_body_bytes) is not int or self.max_error_body_bytes < 1:
            raise ValueError("max_error_body_bytes must be a positive integer")
        for value in (
            self.queue_timeout,
            self.navigation_timeout,
            self.response_timeout,
            self.cleanup_timeout,
            self.error_capture_timeout,
        ):
            if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                raise ValueError("timeouts must be finite positive seconds")
