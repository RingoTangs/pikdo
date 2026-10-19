import logging
import re
from contextlib import asynccontextmanager
from typing import Annotated

import httpx
from fastapi import Body, FastAPI, Path, Request
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

    application = FastAPI(
        title="Pikdo Collector",
        version="0.3.0",
        description="小红书商品输入解析与商品详情采集服务。先解析分享链接获取商品 ID，再按需查询商品详情。",
        openapi_tags=[
            {"name": "健康检查", "description": "检查服务是否正常运行，不访问上游。"},
            {
                "name": "小红书商品",
                "description": "解析商品 ID、商品链接和分享文案，以及采集商品详情。",
            },
        ],
        lifespan=lifespan,
    )

    @application.exception_handler(CollectionError)
    async def handle_collection_error(request: Request, exc: CollectionError):
        item_id = request.path_params.get("item_id", "")
        error = ErrorResponse(code=exc.code, message=exc.message, item_id=item_id.lower())
        return JSONResponse(
            status_code=exc.status_code, content=error.model_dump(), headers=exc.headers
        )

    @application.exception_handler(ResolveError)
    async def handle_resolve_error(request: Request, exc: ResolveError) -> JSONResponse:
        error = ResolveErrorResponse(code=exc.code, message=exc.message)
        return JSONResponse(
            status_code=exc.status_code, content=error.model_dump(), headers=exc.headers
        )

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
        tags=["小红书商品"],
        summary="解析商品输入",
        description=(
            "支持纯商品 ID、H5 商品链接、xhslink 短链接和完整移动端分享文案。"
            "输入为 1–8192 字符，支持转义冒号、斜杠、HTML 实体和 Markdown 链接。\n\n"
            "纯 ID 和 H5 URL 在本地解析；短链复用连接请求上游，最多处理三次重定向。"
            "返回规范化的小写 item_id 和不含分享参数的 canonical_url。"
            "source_type 为 direct_id、h5_url、short_url 或 share_text。\n\n"
            "本接口只负责解析，不自动采集商品详情；可将返回的 ID 用于商品详情 GET 接口。"
            "错误只包含 code 和 message，不回显原始分享内容。"
        ),
        response_description="解析来源、商品 ID 和不含追踪参数的规范商品链接。",
        responses={
            status: {"model": ResolveErrorResponse, "description": description}
            for status, description in {
                422: "输入非法、未识别链接、不是商品链接或包含多个不同目标。",
                502: "短链连接失败、异常 HTTP 状态、不支持的页面或重定向异常。",
                503: "短链访问受限或本进程已阻断请求（UPSTREAM_REQUEST_BLOCKED）。诊断头 X-Collector-Upstream-Status、X-Collector-Blocked 返回触发状态。",
                504: "短链请求超时。",
            }.items()
        },
    )
    async def resolve_product(
        body: Annotated[
            ResolveRequest,
            Body(
                description="提供一种商品输入；无需同时填写多种形式。",
                openapi_examples={
                    "direct_id": {
                        "summary": "纯商品 ID（无需网络）",
                        "value": {"input": "6ab10b35d528ea0015dd1b7e"},
                    },
                    "h5_url": {
                        "summary": "H5 商品链接（无需网络）",
                        "value": {
                            "input": "https://www.xiaohongshu.com/goods-detail/6ab10b35d528ea0015dd1b7e"
                        },
                    },
                    "short_url": {
                        "summary": "短链接（需要访问短链服务）",
                        "value": {"input": "https://xhslink.com/m/9izjN4oPZd4"},
                    },
                    "share_text": {
                        "summary": "完整移动端分享文案",
                        "value": {
                            "input": "【小红书】【前3000单必出盘】 😆 3ukhXCGSwNE 😆 https://xhslink.com/m/9izjN4oPZd4 点击链接或者复制本条信息打开【小红书app】查看 CZ6125"
                        },
                    },
                },
            ),
        ],
        request: Request,
    ) -> ResolveResponse:
        return await request.app.state.resolver.resolve(body.input)

    @application.get(
        "/health",
        tags=["健康检查"],
        summary="检查服务运行状态",
        description="返回当前服务运行状态，不请求小红书接口，不代表上游服务可用。",
        response_description="服务正常运行时返回 status=ok。",
    )
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.get(
        "/api/v1/xhs/products/{item_id}",
        response_model=Product,
        tags=["小红书商品"],
        summary="采集商品详情",
        description=(
            "通过商品 ID 异步请求小红书商品详情接口，返回标题、店铺、关联账号、价格、销量、"
            "图片、规格、促销、物流、售后服务和购买功能等标准化信息。\n\n"
            "商品 ID 必须为 24 位十六进制字符串，接受大小写并统一转为小写。"
            "金额以十进制字符串返回；缺失销量为 null，含万、w 或 + 的销量标为近似值。"
            "采集时间 fetched_at 使用 Asia/Shanghai 时区。\n\n"
            "只有明确业务信号才判断商品不存在或下架，不根据请求失败、字段缺失或库存值推断。"
            "错误包含 code、message 和 item_id。"
        ),
        response_description="标准化商品详情；可选信息缺失时为 null 或空数组。",
        responses={
            status: {"model": ErrorResponse, "description": description}
            for status, description in {
                404: "上游明确商品不存在、已下架或已失效。",
                422: "商品 ID 不是 24 位十六进制字符串。",
                502: "上游 HTTP、连接、JSON、业务状态或商品数据结构异常。",
                503: "上游访问受限或本进程已阻断请求（UPSTREAM_REQUEST_BLOCKED）。诊断头 X-Collector-Upstream-Status、X-Collector-Blocked 返回触发状态。",
                504: "上游商品详情请求超时。",
            }.items()
        },
    )
    async def product(
        item_id: Annotated[
            str,
            Path(
                description="24 位十六进制商品 ID，接受大写字母并统一转为小写；不是分享链接。",
                examples=["6ab10b35d528ea0015dd1b7e"],
            ),
        ],
        request: Request,
    ) -> Product:
        if re.fullmatch(r"[0-9a-fA-F]{24}", item_id) is None:
            raise CollectionError("INVALID_ITEM_ID", "商品 ID 必须为 24 位十六进制字符串", 422)
        return await request.app.state.xhs.fetch_product(item_id.lower())

    return application


app = create_app()


def main() -> None:
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
