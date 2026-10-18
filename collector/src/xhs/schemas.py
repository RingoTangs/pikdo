from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, field_serializer


class Shop(BaseModel):
    id: str | None = None
    name: str | None = None
    fans_count: int | None = None
    sold_display: str | None = None


class Price(BaseModel):
    current: Decimal | None = None
    display: Decimal | None = None
    currency: str = "CNY"

    @field_serializer("current", "display")
    def serialize_amount(self, value: Decimal | None) -> str | None:
        return format(value, "f") if value is not None else None


class Sales(BaseModel):
    display: str | None = None
    estimated_count: int | None = None
    exact: bool | None = None


class Product(BaseModel):
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


class ErrorResponse(BaseModel):
    code: str
    message: str
    item_id: str
