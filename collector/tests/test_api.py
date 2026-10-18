import json
from decimal import Decimal

import httpx
import pytest
from fastapi.testclient import TestClient

from app import create_app
from xhs.client import UPSTREAM_URL
from conftest import ITEM_ID


PATH = f'/api/v1/xhs/products/{ITEM_ID}'


def response_for(payload):
    return httpx.Response(200, content=json.dumps(payload, default=lambda value: str(value) if isinstance(value, Decimal) else value))


def test_success_and_health(payload):
    requests = []
    def handler(request):
        requests.append(request)
        assert str(request.url).split('?')[0] == UPSTREAM_URL
        assert dict(request.url.params) == {'version': '0.0.5', 'item_id': ITEM_ID}
        return response_for(payload)
    with TestClient(create_app(httpx.MockTransport(handler))) as client:
        assert client.get('/health').json() == {'status': 'ok'}
        assert requests == []
        assert client.get('/docs').status_code == 200
        response = client.get(f'/api/v1/xhs/products/{ITEM_ID.upper()}')
        assert response.status_code == 200
        result = response.json()
        assert result['item_id'] == ITEM_ID
        assert result['price']['current'] == '9.99'
        assert result['sales']['estimated_count'] == 23000
        assert result['sales']['exact'] is False
        assert result['fetched_at'].endswith('+08:00')
        assert client.get(PATH).status_code == 200
        assert len(requests) == 2


@pytest.mark.parametrize('item_id', ['bad', 'g' * 24, 'a' * 23, 'a' * 25])
def test_invalid_id(item_id):
    def handler(request):
        pytest.fail('Invalid IDs must not reach upstream')
    with TestClient(create_app(httpx.MockTransport(handler))) as client:
        response = client.get(f'/api/v1/xhs/products/{item_id}')
    assert response.status_code == 422
    assert response.json()['code'] == 'INVALID_ITEM_ID'
    assert set(response.json()) == {'code', 'message', 'item_id'}


@pytest.mark.parametrize('status,expected,code', [
    (401, 503, 'UPSTREAM_ACCESS_RESTRICTED'), (403, 503, 'UPSTREAM_ACCESS_RESTRICTED'),
    (429, 503, 'UPSTREAM_ACCESS_RESTRICTED'), (461, 503, 'UPSTREAM_ACCESS_RESTRICTED'),
    (500, 502, 'UPSTREAM_HTTP_ERROR'), (404, 502, 'UPSTREAM_HTTP_ERROR'),
    (302, 502, 'UPSTREAM_HTTP_ERROR'),
])
def test_http_errors(status, expected, code):
    with TestClient(create_app(httpx.MockTransport(lambda request: httpx.Response(status)))) as client:
        response = client.get(PATH)
    assert response.status_code == expected
    assert response.json()['code'] == code
    assert response.json()['item_id'] == ITEM_ID


@pytest.mark.parametrize('exception,status,code', [
    (httpx.ReadTimeout, 504, 'UPSTREAM_TIMEOUT'), (httpx.ConnectTimeout, 504, 'UPSTREAM_TIMEOUT'),
    (httpx.ConnectError, 502, 'UPSTREAM_REQUEST_FAILED'),
])
def test_transport_errors(exception, status, code):
    def handler(request):
        raise exception('simulated', request=request)
    with TestClient(create_app(httpx.MockTransport(handler))) as client:
        response = client.get(PATH)
    assert response.status_code == status
    assert response.json()['code'] == code


@pytest.mark.parametrize('payload,status,code', [
    ({'success': False, 'msg': 'unknown'}, 502, 'UPSTREAM_BUSINESS_ERROR'),
    ({'success': False, 'msg': '请完成安全验证'}, 503, 'UPSTREAM_ACCESS_RESTRICTED'),
    ({'success': False, 'msg': '商品不存在'}, 404, 'PRODUCT_UNAVAILABLE'),
    ({'success': False, 'msg': '当前商品已下架'}, 404, 'PRODUCT_UNAVAILABLE'),
    ({'success': True, 'data': {'template_data': []}}, 502, 'UPSTREAM_INVALID_DATA'),
    ({'success': True, 'data': {'template_data': [None]}}, 502, 'UPSTREAM_INVALID_DATA'),
])
def test_business_errors(payload, status, code):
    with TestClient(create_app(httpx.MockTransport(lambda request: response_for(payload)))) as client:
        response = client.get(PATH)
    assert response.status_code == status
    assert response.json()['code'] == code


def test_invalid_json():
    with TestClient(create_app(httpx.MockTransport(lambda request: httpx.Response(200, text='<html>error</html>')))) as client:
        response = client.get(PATH)
    assert response.status_code == 502
    assert response.json()['code'] == 'UPSTREAM_INVALID_JSON'


def test_delisted_button(payload):
    payload['data']['template_data'][0]['bottomBarMainH5']['buttons'] = [{'type': 'unBuyableGoShop'}]
    with TestClient(create_app(httpx.MockTransport(lambda request: response_for(payload)))) as client:
        response = client.get(PATH)
    assert response.status_code == 404
    assert response.json()['code'] == 'PRODUCT_UNAVAILABLE'


def test_missing_sales_remains_null(payload):
    del payload['data']['template_data'][0]['priceH5']['itemAnalysisDataText']
    with TestClient(create_app(httpx.MockTransport(lambda request: response_for(payload)))) as client:
        result = client.get(PATH).json()
    assert result['sales'] == {'display': None, 'estimated_count': None, 'exact': None}
