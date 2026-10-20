"""Synthetic error evidence only: these payloads are not observed platform errors."""

import hashlib
import json
from dataclasses import FrozenInstanceError

import pytest

from xhs.upstream_diagnostics import UpstreamDiagnostic, extract_upstream_error

RAW = b' { "success" : false, "code":123, "msg":"error" }\n'


@pytest.mark.parametrize(
    "body,code,message",
    [
        (RAW, 123, "error"),
        (b'{"error_code":"E","message":"message"}', "E", "message"),
        (b'{"err_code":-3,"msg":""}', -3, ""),
        (b'{"code":true,"error_code":7,"msg":{},"message":"fallback"}', 7, "fallback"),
        (b'{"code":1.25,"msg":["hidden"]}', None, None),
        (b'{"data":{"code":3,"msg":"nested"}}', None, None),
        (b"[]", None, None),
        (b"not JSON\xff", None, None),
        (b'{"code":NaN,"msg":"ignored"}', None, None),
        (b'{"code":null}', None, None),
    ],
)
def test_top_level_extraction_preserves_bytes_and_types(body, code, message):
    diagnostic = extract_upstream_error(body, 461)
    assert diagnostic.business_code == code and diagnostic.business_message == message
    assert diagnostic.http_status == 461
    assert diagnostic.raw_body is body
    assert diagnostic.body_size == len(body)
    assert diagnostic.body_sha256 == hashlib.sha256(body).hexdigest()
    assert diagnostic.capture_status == "captured"


def test_optional_empty_oversize_and_safe_metadata():
    empty = extract_upstream_error(b"", 403)
    assert empty.capture_status == "empty" and empty.raw_body == b""
    assert empty.body_sha256 == hashlib.sha256(b"").hexdigest()
    large = extract_upstream_error(RAW, 200, max_body_bytes=2)
    assert large.capture_status == "too_large" and large.raw_body is None
    assert large.body_size is large.body_sha256 is None
    assert UpstreamDiagnostic().raw_body is None
    diagnostic = extract_upstream_error(
        b'{"code":"Cookie=SECRET","msg":"Authorization=SECRET"}',
        461,
        collection_id="a" * 32,
        source="product_response",
        content_type="SECRET",
    )
    assert diagnostic.business_code == "Cookie=SECRET"
    assert "SECRET" not in repr(diagnostic)
    assert "SECRET" not in json.dumps(diagnostic.safe_metadata())
    assert "raw_body" not in diagnostic.safe_metadata()
    with pytest.raises(TypeError):
        json.dumps(diagnostic)
    with pytest.raises(FrozenInstanceError):
        diagnostic.http_status = 503
