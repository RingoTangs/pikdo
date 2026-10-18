"""Small domain parsers for the allowlisted product detail fields."""
import json
from decimal import Decimal

from xhs.schemas import (
    AdditionalInfo, BasicInfo, Button, DealPrice, Formula, FormulaEntry, Image,
    Media, Metadata, Parameter, PricingDetails, Promotion, PromotionContent,
    Promotions, Purchase, SellerAccount, Service, Services, ServiceSummary,
    Shipping, ShippingTime, TextSection, Variants,
)
from xhs.values import (
    amount, array, boolean, business_array, integer, obj, objects, safe_json,
    safe_url, scalar, string, text,
)


def parse_basic(data: dict) -> BasicInfo:
    main, sku = obj(data.get('descriptionMain')), obj(data.get('descriptionH5'))
    return BasicInfo(product_item_id=text(main.get('itemId')), sku_id=text(sku.get('skuId')),
                     product_tags=business_array(main.get('tags')), sku_tags=business_array(sku.get('tags')))


def parse_account(data: dict) -> SellerAccount:
    follow = obj(obj(data.get('profitBarPopupH5')).get('follow'))
    return SellerAccount(name=text(follow.get('name')), logo=safe_url(follow.get('logo')),
                         fans_num=integer(follow.get('fansNum')), seller_user_id=text(follow.get('sellerUserId')),
                         seller_id=text(follow.get('sellerId')))


def parse_deal(value: object) -> DealPrice:
    value = obj(value)
    return DealPrice(price=amount(value.get('price')), type=scalar(value.get('type')),
                     price_text=string(value.get('priceText')), is_show_price_pop=boolean(value.get('isShowPricePop')))


def formula_entry(value: dict) -> FormulaEntry:
    return FormulaEntry(name=string(value.get('name')), price=amount(value.get('price')))


def parse_pricing(data: dict) -> PricingDetails:
    price, bottom = obj(data.get('priceH5')), obj(data.get('bottomBarMainH5'))
    formula = obj(obj(data.get('profitBarPopupH5')).get('formula'))
    raw = bottom.get('price')
    return PricingDetails(
        highlight_price=amount(price.get('highlightPrice')),
        price_integer_part=string(price.get('priceIntegerPart')), price_decimal_part=string(price.get('priceDecimalPart')),
        deal_price=parse_deal(price.get('dealPrice')), multiple_discount=business_array(price.get('multipleDiscount')),
        tags=business_array(price.get('tags')), bottom_price_raw=raw if type(raw) is int else amount(raw),
        bottom_deal_price=parse_deal(bottom.get('dealPrice')),
        formula=Formula(result=formula_entry(obj(formula.get('result'))),
                        steps=[formula_entry(v) for v in objects(formula.get('step'))]),
    )


def parse_images(value: object) -> list[Image]:
    return [Image(url=safe_url(v.get('url')), thumbnail=safe_url(v.get('thumbnail')),
                  width=integer(v.get('width')), height=integer(v.get('height')),
                  cover=safe_json(v.get('cover')), video_id=string(v.get('videoId')),
                  media=safe_json(v.get('media')), type=scalar(v.get('type')))
            for v in objects(value)]


def parse_media(data: dict) -> Media:
    graphic = obj(data.get('graphicDetailsV4'))
    return Media(images=parse_images(obj(data.get('carouselH5')).get('images')),
                 detail_description=string(graphic.get('description')), detail_images=parse_images(graphic.get('images')))


def parse_parameters(value: object) -> list[Parameter]:
    return [Parameter(name=string(v.get('name')), value=string(v.get('value'))) for v in objects(value)]


def parse_variants(data: dict, common_data: object) -> Variants:
    selected, params = obj(data.get('selectedH5')), obj(data.get('variantsParams'))
    common = {}
    if isinstance(common_data, str):
        try:
            common = obj(json.loads(common_data, parse_float=Decimal))
        except (ValueError, RecursionError):
            pass
    return Variants(selected_sku_id=text(selected.get('skuId')), selected_text=string(selected.get('text')),
                    specifications=business_array(selected.get('specifications')), title=string(params.get('title')),
                    parameters=parse_parameters(params.get('list')), sku_type=string(obj(data.get('skuInfo')).get('type')),
                    sku_num=integer(obj(common.get('statisticInfo')).get('skuNum')))


def parse_promotions(data: dict) -> Promotions:
    bar = obj(data.get('profitBarV1'))
    popup = obj(obj(data.get('profitBarPopupH5')).get('promotionV1'))
    activities = []
    for v in objects(popup.get('list')):
        contents = [PromotionContent(tag=string(c.get('tag')), description=string(c.get('description')),
                                     validate_time=string(c.get('validateTime')), gift_goods=business_array(c.get('giftGoods')))
                    for c in objects(v.get('contents'))]
        activities.append(Promotion(promotion_id=string(v.get('promotionId')), promotion_type=scalar(v.get('promotionType')),
                                    promotion_name=string(v.get('promotionName')), name=string(v.get('name')),
                                    title=string(v.get('title')), validate_time=string(v.get('validateTime')), contents=contents))
    return Promotions(name=string(bar.get('name')), tags=business_array(bar.get('tags')),
                      profit_type=string(bar.get('profitType')), promotion_type=scalar(bar.get('promotionType')),
                      promotion_id=string(bar.get('promotionId')), query_payment_marketing_info=boolean(bar.get('queryPaymentMarketingInfo')),
                      title=string(popup.get('title')), activities=activities)


def parse_shipping(data: dict) -> Shipping:
    distribute = obj(data.get('goodsDistributeV4'))
    time = obj(distribute.get('time'))
    delivery = obj(obj(data.get('bottomBarMainH5')).get('deliveryInfo'))
    return Shipping(location=string(distribute.get('location')), fee=string(distribute.get('fee')),
                    time=ShippingTime(text=string(time.get('text')), mode=scalar(time.get('mode')), tag=string(time.get('tag'))),
                    able_to_delivery=boolean(delivery.get('ableToDelivery')),
                    unable_to_delivery_text=string(delivery.get('unableToDeliveryText')), button_text=string(delivery.get('buttonText')))


def parse_services(data: dict) -> Services:
    popup = [Service(title=string(v.get('title')), description=string(v.get('description')),
                     icon=safe_url(v.get('icon')), type=scalar(v.get('type')))
             for v in objects(obj(data.get('servicePopupV2')).get('list'))]
    summary = [ServiceSummary(name=string(v.get('name')), icon=safe_url(v.get('icon')), type=scalar(v.get('type')))
               for v in objects(obj(data.get('serviceV5')).get('list'))]
    merged: list[Service] = []
    by_title: dict[str, int] = {}
    for item in popup + [Service(title=v.name, icon=v.icon, type=v.type) for v in summary]:
        title = text(item.title)
        if title is None:
            continue
        key = title.strip()
        if key not in by_title:
            by_title[key] = len(merged)
            merged.append(item.model_copy())
        else:
            existing = merged[by_title[key]]
            for field in ('description', 'icon', 'type'):
                if getattr(existing, field) is None:
                    setattr(existing, field, getattr(item, field))
    return Services(popup_items=popup, summary_items=summary, items=merged)


def parse_purchase(data: dict) -> Purchase:
    bottom = obj(data.get('bottomBarMainH5'))
    return Purchase(
        buttons=[Button(type=string(v.get('type')), text=string(v.get('text')), sub_text=string(v.get('subText')),
                        action_type=string(v.get('actionType')), style_type=string(v.get('styleType')))
                 for v in objects(bottom.get('buttons'))],
        show_customer_service=boolean(bottom.get('showCustomerService')),
        customer_service_name=string(bottom.get('customerServiceName')),
        customer_service_link=safe_url(bottom.get('customerServiceLink'), customer_service=True),
        is_single_variant=boolean(bottom.get('isSingleVariant')), coupon_infos=business_array(bottom.get('couponInfos')),
    )


def parse_additional(data: dict) -> AdditionalInfo:
    def section(key):
        v = obj(data.get(key))
        return TextSection(title=string(v.get('title')), items=parse_parameters(v.get('list')))
    header = obj(data.get('headerBarMainV2'))
    return AdditionalInfo(price_description=section('priceDescription'), faq=section('normalFaq'),
                          has_comments=boolean(header.get('hasComments')), has_followed=boolean(header.get('hasFollowed')),
                          is_followed=boolean(header.get('isFollowed')))


def parse_metadata(data: dict, envelope: dict) -> Metadata:
    ids = []
    for block in ('descriptionH5', 'selectedH5', 'carouselH5', 'bottomBarMainH5'):
        value = text(obj(data.get(block)).get('skuId'))
        if value is not None:
            ids.append(value.lower())
    return Metadata(template_version=string(envelope.get('template_version')),
                    sku_ids_consistent=len(set(ids)) == 1 if len(ids) >= 2 else None)
