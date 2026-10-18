from datetime import datetime
from decimal import Decimal
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, JsonValue, PlainSerializer

Money = Annotated[Decimal, PlainSerializer(lambda v: format(v, "f"), return_type=str)]


class Model(BaseModel):
    model_config = ConfigDict(strict=True)


class Shop(Model):
    id: str | None = None
    name: str | None = None
    fans_count: int | None = None
    sold_display: str | None = None
    logo: str | None = None
    link: str | None = None
    grade: str | None = None
    seller_score: str | None = None
    seller_score_text: str | None = None
    fans_display: str | None = None
    next_title: str | None = None
    sold_estimated_count: int | None = None
    sold_exact: bool | None = None


class Price(Model):
    current: Money | None = None
    display: Money | None = None
    currency: str = "CNY"


class Sales(Model):
    display: str | None = None
    estimated_count: int | None = None
    exact: bool | None = None


class BasicInfo(Model):
    product_item_id: str | None = None
    sku_id: str | None = None
    product_tags: list[JsonValue] = Field(default_factory=list)
    sku_tags: list[JsonValue] = Field(default_factory=list)


class SellerAccount(Model):
    name: str | None = None
    logo: str | None = None
    fans_num: int | None = None
    seller_user_id: str | None = None
    seller_id: str | None = None


class DealPrice(Model):
    price: Money | None = None
    type: int | str | None = None
    price_text: str | None = None
    is_show_price_pop: bool | None = None


class FormulaEntry(Model):
    name: str | None = None
    price: Money | None = None


class Formula(Model):
    result: FormulaEntry = Field(default_factory=FormulaEntry)
    steps: list[FormulaEntry] = Field(default_factory=list)


class PricingDetails(Model):
    highlight_price: Money | None = None
    price_integer_part: str | None = None
    price_decimal_part: str | None = None
    deal_price: DealPrice = Field(default_factory=DealPrice)
    multiple_discount: list[JsonValue] = Field(default_factory=list)
    tags: list[JsonValue] = Field(default_factory=list)
    bottom_price_raw: int | Money | None = None
    bottom_deal_price: DealPrice = Field(default_factory=DealPrice)
    formula: Formula = Field(default_factory=Formula)


class Image(Model):
    url: str | None = None
    thumbnail: str | None = None
    width: int | None = None
    height: int | None = None
    cover: JsonValue = None
    video_id: str | None = None
    media: JsonValue = None
    type: int | str | None = None


class Media(Model):
    images: list[Image] = Field(default_factory=list)
    detail_description: str | None = None
    detail_images: list[Image] = Field(default_factory=list)


class Parameter(Model):
    name: str | None = None
    value: str | None = None


class Variants(Model):
    selected_sku_id: str | None = None
    selected_text: str | None = None
    specifications: list[JsonValue] = Field(default_factory=list)
    title: str | None = None
    parameters: list[Parameter] = Field(default_factory=list)
    sku_type: str | None = None
    sku_num: int | None = None


class PromotionContent(Model):
    tag: str | None = None
    description: str | None = None
    validate_time: str | None = None
    gift_goods: list[JsonValue] = Field(default_factory=list)


class Promotion(Model):
    promotion_id: str | None = None
    promotion_type: int | str | None = None
    promotion_name: str | None = None
    name: str | None = None
    title: str | None = None
    validate_time: str | None = None
    contents: list[PromotionContent] = Field(default_factory=list)


class Promotions(Model):
    name: str | None = None
    tags: list[JsonValue] = Field(default_factory=list)
    profit_type: str | None = None
    promotion_type: int | str | None = None
    promotion_id: str | None = None
    query_payment_marketing_info: bool | None = None
    title: str | None = None
    activities: list[Promotion] = Field(default_factory=list)


class ShippingTime(Model):
    text: str | None = None
    mode: int | str | None = None
    tag: str | None = None


class Shipping(Model):
    location: str | None = None
    fee: str | None = None
    time: ShippingTime = Field(default_factory=ShippingTime)
    able_to_delivery: bool | None = None
    unable_to_delivery_text: str | None = None
    button_text: str | None = None


class Service(Model):
    title: str | None = None
    description: str | None = None
    icon: str | None = None
    type: int | str | None = None


class ServiceSummary(Model):
    name: str | None = None
    icon: str | None = None
    type: int | str | None = None


class Services(Model):
    popup_items: list[Service] = Field(default_factory=list)
    summary_items: list[ServiceSummary] = Field(default_factory=list)
    items: list[Service] = Field(default_factory=list)


class Button(Model):
    type: str | None = None
    text: str | None = None
    sub_text: str | None = None
    action_type: str | None = None
    style_type: str | None = None


class Purchase(Model):
    buttons: list[Button] = Field(default_factory=list)
    show_customer_service: bool | None = None
    customer_service_name: str | None = None
    customer_service_link: str | None = None
    is_single_variant: bool | None = None
    coupon_infos: list[JsonValue] = Field(default_factory=list)


class TextSection(Model):
    title: str | None = None
    items: list[Parameter] = Field(default_factory=list)


class AdditionalInfo(Model):
    price_description: TextSection = Field(default_factory=TextSection)
    faq: TextSection = Field(default_factory=TextSection)
    has_comments: bool | None = None
    has_followed: bool | None = None
    is_followed: bool | None = None


class Metadata(Model):
    template_version: str | None = None
    sku_ids_consistent: bool | None = None


class Product(Model):
    item_id: str
    title: str
    sku_title: str | None = None
    shop: Shop
    price: Price
    sales: Sales
    cover_url: str | None = None
    stock_status: int | None = None
    deliverable: bool | None = None
    fetched_at: datetime
    basic_info: BasicInfo = Field(default_factory=BasicInfo)
    seller_account: SellerAccount = Field(default_factory=SellerAccount)
    pricing_details: PricingDetails = Field(default_factory=PricingDetails)
    media: Media = Field(default_factory=Media)
    variants: Variants = Field(default_factory=Variants)
    promotions: Promotions = Field(default_factory=Promotions)
    shipping: Shipping = Field(default_factory=Shipping)
    services: Services = Field(default_factory=Services)
    purchase: Purchase = Field(default_factory=Purchase)
    additional_info: AdditionalInfo = Field(default_factory=AdditionalInfo)
    metadata: Metadata = Field(default_factory=Metadata)


class ErrorResponse(Model):
    code: str
    message: str
    item_id: str
