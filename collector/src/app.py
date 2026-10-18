import logging
import re
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from xhs.client import XhsClient
from xhs.errors import CollectionError, ResolveError
from xhs.resolver import RESOLVE_PATH, ProductResolver, check_short_redirect_response
from xhs.schemas import (
    ErrorResponse,
    Product,
    ResolveErrorResponse,
    ResolveRequest,
    ResolveResponse,
)


def create_app(transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        timeout = httpx.Timeout(connect=5, read=10, write=10, pool=5)
        async with httpx.AsyncClient(
            timeout=timeout,
            follow_redirects=False,
            transport=transport,
            event_hooks={"response": [check_short_redirect_response]},
        ) as http:
            application.state.xhs = XhsClient(http)
            application.state.resolver = ProductResolver(application.state.xhs)
            logging.getLogger("uvicorn.error").info("接口文档：http://localhost:8000/docs")
            yield

    application = FastAPI(title="Pikdo Collector", version="0.3.0", lifespan=lifespan)

    @application.exception_handler(CollectionError)
    async def handle_collection_error(request: Request, exc: CollectionError):
        item_id = request.path_params.get("item_id", "")
        error = ErrorResponse(code=exc.code, message=exc.message, item_id=item_id.lower())
        return JSONResponse(status_code=exc.status_code, content=error.model_dump())

    @application.exception_handler(ResolveError)
    async def handle_resolve_error(request: Request, exc: ResolveError) -> JSONResponse:
        error = ResolveErrorResponse(code=exc.code, message=exc.message)
        return JSONResponse(status_code=exc.status_code, content=error.model_dump())

    @application.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        if request.url.path == RESOLVE_PATH:
            # Never echo Pydantic's input values or invalid JSON bodies containing share tokens.
            return JSONResponse(
                status_code=422,
                content={
                    "code": "INVALID_PRODUCT_INPUT",
                    "message": "请求必须包含 1–8192 字符的 input 字符串",
                },
            )
        return await request_validation_exception_handler(request, exc)

    @application.post(
        RESOLVE_PATH,
        response_model=ResolveResponse,
        responses={status: {"model": ResolveErrorResponse} for status in (422, 502, 503, 504)},
    )
    async def resolve_product(body: ResolveRequest, request: Request) -> ResolveResponse:
        return await request.app.state.resolver.resolve(body.input)

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.get(
        "/api/v1/xhs/products/{item_id}",
        response_model=Product,
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
