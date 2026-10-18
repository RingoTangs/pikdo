import re
from datetime import datetime
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo

from xhs.errors import CollectionError
from xhs.schemas import Price, Product, Sales, Shop


SHANGHAI = ZoneInfo("Asia/Shanghai")
UNAVAILABLE = ("已下架，进店逛逛", "当前商品已下架", "商品已下架", "商品不存在", "商品已失效", "当前商品违规，无法展示", "item freeze")
RESTRICTED = ("安全验证", "安全校验", "访问受限", "访问频繁", "请求频繁", "验证码", "登录后", "请登录")


def obj(value: object) -> dict:
    return value if isinstance(value, dict) else {}


def text(value: object) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def amount(value: object) -> Decimal | None:
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        return None
    try:
        number = Decimal(str(value))
        return number if number.is_finite() and number >= 0 else None
    except InvalidOperation:
        return None


def parse_count(value: object, prefix: str = "已售") -> tuple[int | None, bool | None]:
    if not isinstance(value, str):
        return None, None
    match = re.fullmatch(rf"\s*(?:{re.escape(prefix)}\s*)?(\d+(?:\.\d+)?)\s*([万wW]?)\s*(\+?)\s*", value)
    if not match:
        return None, None
    number, unit, plus = match.groups()
    # Decimal avoids binary rounding; never silently truncate fractional counts.
    count = Decimal(number) * (10000 if unit else 1)
    if count != count.to_integral_value():
        return None, None
    return int(count), not bool(unit or plus)


def business_error(messages: list[str], buttons: list) -> None:
    if any(any(marker in message.lower() for marker in UNAVAILABLE) for message in messages) or any(
        obj(button).get("type") == "unBuyableGoShop" for button in buttons
    ):
        raise CollectionError("PRODUCT_UNAVAILABLE", "商品不存在、已下架或已失效", 404)
    if any(any(marker in message for marker in RESTRICTED) for message in messages):
        raise CollectionError("UPSTREAM_ACCESS_RESTRICTED", "上游访问受限", 503)


def parse_product(payload: object, item_id: str) -> Product:
    if not isinstance(payload, dict):
        raise CollectionError("UPSTREAM_INVALID_DATA", "上游响应不是有效对象")
    messages = [value for key in ("msg", "message") if (value := text(payload.get(key)))]
    business_error(messages, [])
    if payload.get("success") is not True:
        raise CollectionError("UPSTREAM_BUSINESS_ERROR", "上游未返回成功状态")
    templates = obj(payload.get("data")).get("template_data")
    if not isinstance(templates, list) or not templates or not isinstance(templates[0], dict) or not templates[0]:
        raise CollectionError("UPSTREAM_INVALID_DATA", "上游缺少商品详情对象")
    data = templates[0]
    bottom = obj(data.get("bottomBarMainH5"))
    buttons = bottom.get("buttons")
    buttons = buttons if isinstance(buttons, list) else []
    business_error(messages + [v for b in buttons for k in ("text", "subText") if (v := text(obj(b).get(k)))], buttons)
    description = obj(data.get("descriptionH5"))
    title = text(obj(data.get("descriptionMain")).get("name")) or text(description.get("name"))
    if title is None:
        raise CollectionError("UPSTREAM_INVALID_DATA", "上游缺少商品标题")
    seller = obj(data.get("sellerH5"))
    price = obj(data.get("priceH5"))
    candidates = [obj(price.get("dealPrice")).get("price"), obj(bottom.get("dealPrice")).get("price"), price.get("highlightPrice")]
    current = next((parsed for candidate in candidates if (parsed := amount(candidate)) is not None), None)
    display = amount(price.get("highlightPrice"))
    sales_display = text(price.get("itemAnalysisDataText"))
    count, exact = parse_count(sales_display)
    fans = obj(obj(data.get("profitBarPopupH5")).get("follow")).get("fansNum")
    if type(fans) is not int or fans < 0:
        fans, _ = parse_count(seller.get("fansAmount"), "粉丝数")
    carousel = obj(data.get("carouselH5"))
    images = carousel.get("images")
    cover = text(obj(images[0]).get("url")) if isinstance(images, list) and images else None
    if cover and cover.startswith("//"):
        cover = "https:" + cover
    stock = carousel.get("stockStatus")
    deliverable = obj(bottom.get("deliveryInfo")).get("ableToDelivery")
    return Product(
        item_id=item_id, title=title, sku_title=text(description.get("name")),
        shop=Shop(id=text(seller.get("id")), name=text(seller.get("name")), fans_count=fans, sold_display=text(seller.get("salesVolume"))),
        price=Price(current=current, display=display if display is not None else current),
        sales=Sales(display=sales_display, estimated_count=count, exact=exact),
        cover_url=cover, stock_status=stock if type(stock) is int else None,
        deliverable=deliverable if type(deliverable) is bool else None,
        fetched_at=datetime.now(SHANGHAI),
    )
