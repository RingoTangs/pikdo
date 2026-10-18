"""Strict upstream values and safe business JSON; never pass whole blocks through."""

import json
import math
import re
from decimal import Decimal, InvalidOperation
from urllib.parse import unquote_plus, urlsplit, urlunsplit


def obj(value: object) -> dict:
    return value if isinstance(value, dict) else {}


def array(value: object) -> list:
    return value if isinstance(value, list) else []


def objects(value: object) -> list[dict]:
    return [v for v in array(value) if isinstance(v, dict)]


def text(value: object) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def string(value: object) -> str | None:
    return value if isinstance(value, str) else None


def integer(value: object) -> int | None:
    return value if type(value) is int and value >= 0 else None


def boolean(value: object) -> bool | None:
    return value if type(value) is bool else None


def scalar(value: object) -> int | str | None:
    return value if type(value) in (int, str) else None


def amount(value: object) -> Decimal | None:
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        return None
    try:
        number = Decimal(str(value))
        return number if number.is_finite() and number >= 0 else None
    except InvalidOperation:
        return None


def sensitive(key: str) -> bool:
    compact = re.sub(r"[^a-z0-9]", "", key.lower())
    return any(
        part in compact
        for part in (
            "token",
            "cookie",
            "authorization",
            "password",
            "secret",
            "signature",
            "tracker",
            "tracking",
            "instation",
            "xsec",
            "trace",
        )
    ) or compact in {"sign", "aegis", "t", "xssource", "xsecsource"}


def safe_url(value: object, *, customer_service: bool = False) -> str | None:
    value = text(value)
    if value is None:
        return None
    if value.startswith("//"):
        value = "https:" + value
    try:
        parts = urlsplit(value)
        allowed = {"http", "https"} | ({"xhsdiscover"} if customer_service else set())
        if parts.scheme.lower() not in allowed or not parts.hostname:
            return None
        # Reject embedded credentials rather than reconstructing an ambiguous authority.
        if parts.username is not None or parts.password is not None:
            return None
        # Preserve CDN transformation syntax byte-for-byte (often a key without '=').
        kept = []
        for segment in parts.query.split("&"):
            key, _, value = segment.partition("=")
            key = unquote_plus(key)
            decoded = unquote_plus(value).lower()
            if (
                not sensitive(key)
                and not key.lower().startswith("utm_")
                and not any(marker in decoded for marker in ("xsec_token", "instation_link"))
            ):
                kept.append(segment)
        query = "&".join(kept)
        return urlunsplit((parts.scheme, parts.netloc, parts.path, query, ""))
    except ValueError:
        return None


def safe_json(value: object, depth: int = 0):
    """Only used for explicitly selected business fields with unknown item schemas."""
    if depth > 20:
        return None
    if isinstance(value, dict):
        return {
            k: safe_json(v, depth + 1)
            for k, v in value.items()
            if isinstance(k, str)
            and not sensitive(k)
            and k not in {"style", "styles", "trackersComponent", "dataTrack", "layout"}
        }
    if isinstance(value, list):
        return [safe_json(v, depth + 1) for v in value]
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.startswith(("{", "[")):
            try:
                return safe_json(json.loads(stripped, parse_float=Decimal), depth + 1)
            except (ValueError, RecursionError):
                pass
        if stripped.startswith("//") or "://" in stripped:
            return safe_url(stripped, customer_service=True)
        if re.search(
            r"(?:token|cookie|authorization|signature|sign|instation_link)\s*[=:]", value, re.I
        ):
            return None
        return value
    if isinstance(value, Decimal):
        return format(value, "f") if value.is_finite() else None
    if type(value) is float:
        return format(Decimal(str(value)), "f") if math.isfinite(value) else None
    return value if value is None or type(value) in (int, bool) else None


def business_array(value: object) -> list:
    return safe_json(array(value))
