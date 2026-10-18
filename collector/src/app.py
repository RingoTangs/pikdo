import logging
import re
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from xhs.client import XhsClient
from xhs.errors import CollectionError
from xhs.schemas import ErrorResponse, Product


def create_app(transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        timeout = httpx.Timeout(connect=5, read=10, write=10, pool=5)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, transport=transport) as http:
            application.state.xhs = XhsClient(http)
            logging.getLogger("uvicorn.error").info("接口文档：http://localhost:8000/docs")
            yield

    application = FastAPI(title="Pikdo Collector", lifespan=lifespan)

    @application.exception_handler(CollectionError)
    async def handle_collection_error(request: Request, exc: CollectionError):
        item_id = request.path_params.get("item_id", "")
        error = ErrorResponse(code=exc.code, message=exc.message, item_id=item_id.lower())
        return JSONResponse(status_code=exc.status_code, content=error.model_dump())

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.get(
        "/api/v1/xhs/products/{item_id}", response_model=Product,
        responses={status: {"model": ErrorResponse} for status in (404, 422, 502, 503, 504)},
    )
    async def product(item_id: str, request: Request) -> Product:
        if re.fullmatch(r"[0-9a-fA-F]{24}", item_id) is None:
            raise CollectionError("INVALID_ITEM_ID", "商品 ID 必须为 24 位十六进制字符串", 422)
        return await request.app.state.xhs.fetch_product(item_id.lower())

    return application


app = create_app()


def main() -> None:
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
