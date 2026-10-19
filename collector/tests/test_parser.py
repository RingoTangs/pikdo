from decimal import Decimal

import pytest
from conftest import ITEM_ID

from xhs.errors import ProductDataError
from xhs.parser import parse_count, parse_product


def test_fixture(payload):
    result = parse_product(payload, ITEM_ID)
    assert result.item_id == ITEM_ID
    assert result.title == payload["data"]["template_data"][0]["descriptionMain"]["name"]
    assert result.sku_title == payload["data"]["template_data"][0]["descriptionH5"]["name"]
    assert result.shop.name == "拾野de食光的店"
    assert result.shop.id == "6a3a4e1875fa89001599ba2d"
    assert result.shop.fans_count == 6210
    assert result.shop.sold_display == "已售 2.3万"
    assert result.price.model_dump() == {"current": "9.99", "display": "19.99", "currency": "CNY"}
    assert result.sales.model_dump() == {
        "display": "已售2.3万+",
        "estimated_count": 23000,
        "exact": False,
    }
    assert result.cover_url.startswith("https://")
    assert result.stock_status == 1
    assert result.deliverable is True
    assert result.fetched_at.utcoffset().total_seconds() == 8 * 3600
    assert str(result.fetched_at.tzinfo) == "Asia/Shanghai"


@pytest.mark.parametrize(
    "value,count,exact",
    [
        ("已售123", 123, True),
        ("已售 0", 0, True),
        ("2.3万", 23000, False),
        ("已售2.3万+", 23000, False),
        ("1.2w", 12000, False),
        ("1.2W", 12000, False),
        ("123+", 123, False),
        ("1.5", None, None),
        ("-1", None, None),
        (None, None, None),
        ("", None, None),
        ("未知", None, None),
        (False, None, None),
    ],
)
def test_count(value, count, exact):
    assert parse_count(value) == (count, exact)


@pytest.mark.parametrize(
    "deal,bottom,highlight,expected",
    [
        ("9.99", "8.99", "19.99", "9.99"),
        (None, "8.99", "19.99", "8.99"),
        (None, None, "19.99", "19.99"),
        (0, "8.99", "19.99", "0"),
        ("invalid", "8.99", "19.99", "8.99"),
        ("NaN", None, "19.99", "19.99"),
        (True, None, None, None),
        (-1, None, None, None),
        ("Infinity", None, None, None),
    ],
)
def test_price_priority(payload, deal, bottom, highlight, expected):
    data = payload["data"]["template_data"][0]
    data["priceH5"]["dealPrice"] = {"price": deal}
    data["bottomBarMainH5"]["dealPrice"] = {"price": bottom}
    data["priceH5"]["highlightPrice"] = highlight
    result = parse_product(payload, ITEM_ID).price.model_dump()
    assert result["current"] == expected
    assert result["display"] == (highlight if highlight is not None else expected)


def test_missing_fields_and_title_fallback(payload):
    data = payload["data"]["template_data"][0]
    fallback = data["descriptionH5"]["name"]
    payload["data"]["template_data"] = [{"descriptionH5": {"name": fallback}}]
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
    data = payload["data"]["template_data"][0]
    data["profitBarPopupH5"]["follow"]["fansNum"] = None
    data["bottomBarMainH5"]["deliveryInfo"]["ableToDelivery"] = False
    result = parse_product(payload, ITEM_ID)
    assert result.shop.fans_count == 6210
    assert result.deliverable is False


@pytest.mark.parametrize(
    "payload",
    [
        None,
        [],
        {"success": True},
        {"success": True, "data": {"template_data": []}},
        {"success": True, "data": {"template_data": [None]}},
        {"success": True, "data": {"template_data": [{}]}},
        {"success": True, "data": {"template_data": [{"sellerH5": {}}]}},
    ],
)
def test_invalid_structure(payload):
    with pytest.raises(ProductDataError) as exc:
        parse_product(payload, ITEM_ID)
    assert exc.value.code == "UPSTREAM_INVALID_DATA"


def test_stock_is_not_delisting(payload):
    payload["data"]["template_data"][0]["carouselH5"]["stockStatus"] = 0
    assert parse_product(payload, ITEM_ID).stock_status == 0


def test_v02_business_fields(payload):
    result = parse_product(payload, ITEM_ID)
    assert result.shop.grade == "4.0"
    assert result.shop.seller_score == "4.20"
    assert result.shop.seller_score_text == "卖家口碑"
    assert result.shop.sold_estimated_count == 23000
    assert result.shop.sold_exact is False
    assert result.seller_account.name == "拾野de食光"
    assert result.seller_account.fans_num == 6210
    assert result.seller_account.seller_user_id == "69f48ed20000000002001403"
    assert result.basic_info.product_item_id == ITEM_ID
    assert result.basic_info.sku_id == ITEM_ID
    assert result.metadata.sku_ids_consistent is True
    assert result.metadata.template_version == "75"
    assert len(result.media.images) == 6
    assert len(result.media.detail_images) == 9
    source = payload["data"]["template_data"][0]
    assert [i.url for i in result.media.images] == [
        "https:" + v["url"] for v in source["carouselH5"]["images"]
    ]
    assert [i.url for i in result.media.detail_images] == [
        v["url"] for v in source["graphicDetailsV4"]["images"]
    ]
    assert result.media.detail_description == ""
    assert result.shipping.location == "广东潮州"
    assert result.shipping.fee == "包邮"
    assert result.shipping.time.text == "48小时内发货"
    assert result.shipping.able_to_delivery is True
    assert result.variants.selected_text == "盲盒随机3件（不保证出盘）"
    assert result.variants.selected_sku_id == ITEM_ID
    assert result.variants.sku_type == "normal"
    assert result.variants.sku_num == 6
    assert result.variants.specifications == []
    assert next(v.value for v in result.variants.parameters if v.name == "材质") == "陶瓷"
    assert result.promotions.tags == ["限时立减10"]
    assert result.promotions.query_payment_marketing_info is False
    activity = result.promotions.activities[0]
    assert activity.name == "限时特价"
    assert activity.promotion_type == 4
    assert activity.contents[0].tag == "限时立减10"
    assert activity.validate_time == "有效期至2028.09.01 00:00"
    pricing = result.pricing_details.model_dump()
    assert pricing["bottom_price_raw"] == 999
    assert pricing["highlight_price"] == "19.99"
    assert pricing["deal_price"] == {
        "price": "9.99",
        "type": 3,
        "price_text": "到手价",
        "is_show_price_pop": True,
    }
    assert pricing["formula"]["result"] == {"name": "到手价", "price": "9.99"}
    assert pricing["formula"]["steps"] == [
        {"name": "原始售价", "price": "19.99"},
        {"name": "限时立减", "price": "10"},
    ]
    assert [v.title for v in result.services.items] == ["极速退款", "7天无理由退货", "晚发必赔"]
    assert len(result.services.popup_items) == len(result.services.summary_items) == 3
    assert result.services.items[0].description
    assert [v.type for v in result.purchase.buttons] == ["addCart", "instantBuy"]
    assert [v.text for v in result.purchase.buttons] == ["加入购物车", "立即购买"]
    assert result.purchase.show_customer_service is True
    assert result.purchase.is_single_variant is False
    assert result.purchase.customer_service_link.startswith("xhsdiscover://")
    assert len(result.additional_info.price_description.items) == 3
    assert result.additional_info.faq.items == []
    assert result.additional_info.is_followed is False


@pytest.mark.parametrize(
    "value",
    [
        None,
        "",
        "{bad",
        "[]",
        "null",
        "true",
        '{"statisticInfo":{"skuNum":true}}',
        '{"statisticInfo":{"skuNum":"6"}}',
        {"statisticInfo": {"skuNum": 6}},
    ],
)
def test_bad_common_data_is_optional(payload, value):
    payload["data"]["common_data"] = value
    result = parse_product(payload, ITEM_ID)
    assert result.variants.sku_num is None
    assert result.price.current == Decimal("9.99")


@pytest.mark.parametrize("value", [None, [], "wrong", 1, False])
def test_optional_blocks_wrong_types(payload, value):
    data = payload["data"]["template_data"][0]
    for key in list(data):
        if key not in ("descriptionMain", "descriptionH5"):
            data[key] = value
    result = parse_product(payload, ITEM_ID)
    assert result.media.images == result.media.detail_images == []
    assert result.purchase.buttons == result.services.items == result.promotions.activities == []
    assert result.shop.sold_estimated_count is None
    assert result.shipping.able_to_delivery is None


def test_strict_optional_scalars(payload):
    data = payload["data"]["template_data"][0]
    data["sellerH5"].update(grade=4, sellerScore=False)
    data["profitBarPopupH5"]["follow"]["fansNum"] = True
    data["sellerH5"]["fansAmount"] = None
    data["bottomBarMainH5"].update(showCustomerService="true", isSingleVariant=1)
    data["goodsDistributeV4"]["time"]["mode"] = False
    data["carouselH5"]["images"][0].update(width=True, height="1024", type=False)
    result = parse_product(payload, ITEM_ID)
    assert result.shop.grade is None and result.shop.seller_score is None
    assert result.seller_account.fans_num is None and result.shop.fans_count is None
    assert (
        result.purchase.show_customer_service is None and result.purchase.is_single_variant is None
    )
    assert result.shipping.time.mode is None
    assert result.media.images[0].width is None and result.media.images[0].height is None
    assert result.media.images[0].type is None


def test_cover_skips_invalid_first_url_preserves_image_order(payload):
    images = payload["data"]["template_data"][0]["carouselH5"]["images"]
    images[0]["url"] = None
    result = parse_product(payload, ITEM_ID)
    assert len(result.media.images) == 6
    assert result.media.images[0].url is None
    assert result.cover_url == "https:" + images[1]["url"]


def test_identity_semantics(payload):
    data = payload["data"]["template_data"][0]
    data["descriptionMain"]["itemId"] = "a" * 24
    result = parse_product(payload, ITEM_ID)
    assert result.basic_info.product_item_id == "a" * 24
    assert result.basic_info.sku_id == ITEM_ID
    assert result.metadata.sku_ids_consistent is True
    data["selectedH5"]["skuId"] = "b" * 24
    result = parse_product(payload, ITEM_ID)
    assert result.metadata.sku_ids_consistent is False
    assert result.variants.selected_sku_id == "b" * 24
    assert result.basic_info.sku_id == ITEM_ID


def test_precision_and_zero_in_new_prices(payload):
    data = payload["data"]["template_data"][0]
    exact = Decimal("9.99000000000000000000000001")
    data["priceH5"]["dealPrice"]["price"] = exact
    data["bottomBarMainH5"]["price"] = Decimal("999.0000000000000000001")
    data["profitBarPopupH5"]["formula"]["step"][1]["price"] = 0
    result = parse_product(payload, ITEM_ID).model_dump()
    assert result["price"]["current"] == str(exact)
    assert result["pricing_details"]["deal_price"]["price"] == str(exact)
    assert result["pricing_details"]["bottom_price_raw"] == "999.0000000000000000001"
    assert result["pricing_details"]["formula"]["steps"][1]["price"] == "0"


def test_services_merge_without_losing_sources(payload):
    data = payload["data"]["template_data"][0]
    data["servicePopupV2"]["list"].append({"title": "极速退款"})
    data["serviceV5"]["list"].append({"name": "额外保障", "icon": "//example.com/icon"})
    result = parse_product(payload, ITEM_ID).services
    assert len(result.popup_items) == 4
    assert len(result.summary_items) == 4
    assert len(result.items) == 4
    assert result.items[0].description
    assert result.items[-1].title == "额外保障"


def test_empty_lists(payload):
    data = payload["data"]["template_data"][0]
    for block, field in [
        ("carouselH5", "images"),
        ("graphicDetailsV4", "images"),
        ("servicePopupV2", "list"),
        ("serviceV5", "list"),
        ("variantsParams", "list"),
        ("bottomBarMainH5", "buttons"),
    ]:
        data[block][field] = []
    result = parse_product(payload, ITEM_ID)
    assert result.media.images == result.media.detail_images == result.services.items == []
    assert result.purchase.buttons == result.variants.parameters == []
    assert result.cover_url is None
