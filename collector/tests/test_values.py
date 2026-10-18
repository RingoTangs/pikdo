import json
from pathlib import Path

import pytest

from xhs.values import safe_json, safe_url, sensitive


@pytest.mark.parametrize('url,expected', [
    ('//cdn.example.com/a?imageView2/2/w/1080&ap=3&sc=STR_DTL', 'https://cdn.example.com/a?imageView2/2/w/1080&ap=3&sc=STR_DTL'),
    ('https://example.com/a?xsec_token=secret&sign=secret&t=123&keep=1', 'https://example.com/a?keep=1'),
    ('https://example.com/a?instation_link=secret&xsec_source=secret&utm_campaign=secret', 'https://example.com/a'),
    ('javascript:alert(1)', None), ('https://user:pass@example.com/a', None),
    ('http://[invalid', None), (False, None), (None, None),
])
def test_safe_url(url, expected):
    assert safe_url(url) == expected


def test_safe_json_nested_and_nonfinite():
    assert safe_json({'token': 'secret', 'business': [float('nan'), '{"cookie":"secret","name":"ok"}']}) == {'business': [None, {'name': 'ok'}]}
    assert safe_json('xsec_token=secret') is None
    assert safe_json('javascript://bad') is None


def test_fixture_is_sanitized():
    payload = json.loads((Path(__file__).parents[1] / 'examples/response.json').read_text())
    def check(value):
        if isinstance(value, dict):
            assert all(not sensitive(k) for k in value)
            for v in value.values():
                check(v)
        elif isinstance(value, list):
            for v in value:
                check(v)
        elif isinstance(value, str):
            assert 'xsec_token' not in value.lower()
            if value.startswith(('http://', 'https://', '//', 'xhsdiscover://')):
                assert safe_url(value, customer_service=True) == ('https:' + value if value.startswith('//') else value)
            elif value.startswith(('{', '[')):
                check(json.loads(value))
    check(payload)
    assert len(payload['data']['template_data'][0]) == 29
