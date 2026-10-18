from copy import deepcopy
from pathlib import Path

import pytest

from xhs.parser import parse_product
from xhs.values import safe_json, safe_url
from conftest import ITEM_ID


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



def test_parser_preserves_original_fixture(payload):
    path = Path(__file__).parents[1] / 'examples/response.json'
    original_bytes = path.read_bytes()
    original_payload = deepcopy(payload)
    parse_product(payload, ITEM_ID)
    assert payload == original_payload
    assert path.read_bytes() == original_bytes
