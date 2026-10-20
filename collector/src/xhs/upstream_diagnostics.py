"""Private upstream evidence; never serialize the whole object into public output."""

import hashlib
import json
import re
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Literal, get_args

MAX_ERROR_BODY_BYTES = 65536
ERROR_CAPTURE_TIMEOUT = 2.0
CaptureStatus = Literal[
    "not_attempted",
    "captured",
    "empty",
    "timeout",
    "read_error",
    "too_large",
    "skipped_unknown_length",
    "unavailable",
]
Source = Literal["product_response", "main_response"]
CAPTURE_STATUSES = frozenset(get_args(CaptureStatus))
SOURCES = frozenset(get_args(Source))


@dataclass(frozen=True, slots=True)
class UpstreamDiagnostic:
    collection_id: str | None = None
    source: Source | None = None
    http_status: int | None = None
    business_code: str | int | None = field(default=None, repr=False)
    business_message: str | None = field(default=None, repr=False)
    raw_body: bytes | None = field(default=None, repr=False, compare=False)
    capture_status: CaptureStatus = "not_attempted"
    body_size: int | None = None
    body_sha256: str | None = None
    content_type: str | None = field(default=None, repr=False)

    def safe_metadata(self) -> dict[str, str | int | None]:
        """Explicit allowlist: arbitrary business fields and headers are never exported."""
        return {
            "collection_id": self.collection_id
            if isinstance(self.collection_id, str)
            and re.fullmatch(r"[0-9a-f]{32}", self.collection_id)
            else None,
            "source": self.source if self.source in SOURCES else None,
            "http_status": self.http_status
            if type(self.http_status) is int and 100 <= self.http_status <= 599
            else None,
            "capture_status": self.capture_status
            if self.capture_status in CAPTURE_STATUSES
            else "unavailable",
            "body_size": self.body_size
            if type(self.body_size) is int and self.body_size >= 0
            else None,
            "body_sha256": self.body_sha256
            if isinstance(self.body_sha256, str) and re.fullmatch(r"[0-9a-f]{64}", self.body_sha256)
            else None,
        }


def _reject_constant(value: str) -> None:
    raise ValueError("Nonfinite JSON constant")


def extract_upstream_error(
    raw_body: bytes | None,
    http_status: int | None,
    *,
    collection_id: str | None = None,
    source: Source | None = None,
    capture_status: CaptureStatus = "not_attempted",
    content_type: str | None = None,
    max_body_bytes: int = MAX_ERROR_BODY_BYTES,
) -> UpstreamDiagnostic:
    """Extract only actual top-level values; classification remains the parser's job."""
    code = message = None
    size = digest = None
    if raw_body is not None:
        if len(raw_body) > max_body_bytes:
            raw_body = None
            capture_status = "too_large"
        else:
            size = len(raw_body)
            digest = hashlib.sha256(raw_body).hexdigest()
            capture_status = "captured" if raw_body else "empty"
            try:
                payload = json.loads(raw_body, parse_float=Decimal, parse_constant=_reject_constant)
            except (ValueError, UnicodeError, RecursionError):
                payload = None
            if isinstance(payload, dict):
                code = next(
                    (
                        payload[key]
                        for key in ("code", "error_code", "err_code")
                        if type(payload.get(key)) in (str, int)
                    ),
                    None,
                )
                message = next(
                    (
                        payload[key]
                        for key in ("msg", "message")
                        if isinstance(payload.get(key), str)
                    ),
                    None,
                )
    return UpstreamDiagnostic(
        collection_id=collection_id,
        source=source,
        http_status=http_status,
        business_code=code,
        business_message=message,
        raw_body=raw_body,
        capture_status=capture_status,
        body_size=size,
        body_sha256=digest,
        content_type=content_type,
    )
