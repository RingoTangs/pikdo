from decimal import Decimal

import pytest

from xhs.errors import CollectionError
from xhs.parser import parse_count, parse_product

from conftest import ITEM_ID


def test_fixture(payload):
    result = parse_product(payload, ITEM_ID)
    assert result.item_id == ITEM_ID
    assert result.title == payload['data']['template_data'][0]['descriptionMain']['name']
    assert result.sku_title == payload['data']['template_data'][0]['descriptionH5']['name']
    assert result.shop.name == '拾野de食光的店'
    assert result.shop.id == '6a3a4e1875fa89001599ba2d'
    assert result.shop.fans_count == 6210
    assert result.shop.sold_display == '已售 2.3万'
    assert result.price.model_dump() == {'current': '9.99', 'display': '19.99', 'currency': 'CNY'}
    assert result.sales.model_dump() == {'display': '已售2.3万+', 'estimated_count': 23000, 'exact': False}
    assert result.cover_url.startswith('https://')
    assert result.stock_status == 1
    assert result.deliverable is True
    assert result.fetched_at.utcoffset().total_seconds() == 8 * 3600
    assert str(result.fetched_at.tzinfo) == 'Asia/Shanghai'


@pytest.mark.parametrize('value,count,exact', [
    ('已售123', 123, True), ('已售 0', 0, True), ('2.3万', 23000, False),
    ('已售2.3万+', 23000, False), ('1.2w', 12000, False), ('1.2W', 12000, False),
    ('123+', 123, False), ('1.5', None, None), ('-1', None, None),
    (None, None, None), ('', None, None), ('未知', None, None), (False, None, None),
])
def test_count(value, count, exact):
    assert parse_count(value) == (count, exact)


@pytest.mark.parametrize('deal,bottom,highlight,expected', [
    ('9.99', '8.99', '19.99', '9.99'), (None, '8.99', '19.99', '8.99'),
    (None, None, '19.99', '19.99'), (0, '8.99', '19.99', '0'),
    ('invalid', '8.99', '19.99', '8.99'), ('NaN', None, '19.99', '19.99'),
    (True, None, None, None), (-1, None, None, None), ('Infinity', None, None, None),
])
def test_price_priority(payload, deal, bottom, highlight, expected):
    data = payload['data']['template_data'][0]
    data['priceH5']['dealPrice'] = {'price': deal}
    data['bottomBarMainH5']['dealPrice'] = {'price': bottom}
    data['priceH5']['highlightPrice'] = highlight
    result = parse_product(payload, ITEM_ID).price.model_dump()
    assert result['current'] == expected
    assert result['display'] == (highlight if highlight is not None else expected)


def test_missing_fields_and_title_fallback(payload):
    data = payload['data']['template_data'][0]
    fallback = data['descriptionH5']['name']
    payload['data']['template_data'] = [{'descriptionH5': {'name': fallback}}]
    result = parse_product(payload, ITEM_ID)
    assert result.title == fallback
    assert result.sales.estimated_count is None
    assert result.sales.exact is None
    assert result.price.current is None
    assert result.cover_url is None
    assert result.stock_status is None
    assert result.deliverable is None
    assert result.shop.fans_count is None


def test_fans_fallback_and_false_delivery(payload):
    data = payload['data']['template_data'][0]
    data['profitBarPopupH5']['follow']['fansNum'] = None
    data['bottomBarMainH5']['deliveryInfo']['ableToDelivery'] = False
    result = parse_product(payload, ITEM_ID)
    assert result.shop.fans_count == 6210
    assert result.deliverable is False


@pytest.mark.parametrize('payload', [None, [], {'success': True}, {'success': True, 'data': {'template_data': []}},
    {'success': True, 'data': {'template_data': [None]}}, {'success': True, 'data': {'template_data': [{}]}},
    {'success': True, 'data': {'template_data': [{'sellerH5': {}}]}}])
def test_invalid_structure(payload):
    with pytest.raises(CollectionError) as exc:
        parse_product(payload, ITEM_ID)
    assert exc.value.code == 'UPSTREAM_INVALID_DATA'


def test_stock_is_not_delisting(payload):
    payload['data']['template_data'][0]['carouselH5']['stockStatus'] = 0
    assert parse_product(payload, ITEM_ID).stock_status == 0
