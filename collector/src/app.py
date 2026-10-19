import logging
import re
from collections.abc import Callable
from contextlib import asynccontextmanager
from importlib.metadata import version
from typing import Annotated

from fastapi import FastAPI, Path, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from xhs.browser import BrowserConfig, BrowserManager, ProductCollectionService
from xhs.browser_collector import BrowserCollectionError, navigation_input
from xhs.errors import BROWSER_HTTP_ERRORS, InputError
from xhs.schemas import (
    CollectErrorResponse,
    CollectRequest,
    ErrorResponse,
    Product,
)

COLLECT_PATH = "/api/v1/xhs/products/collect"


def create_app(
    *,
    browser_config: BrowserConfig | None = None,
    browser_manager_factory: Callable[[], ProductCollectionService] | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        manager = (
            browser_manager_factory() if browser_manager_factory else BrowserManager(browser_config)
        )
        application.state.browser_manager = manager
        try:
            await manager.start()
            logging.getLogger("uvicorn.error").info("接口文档：http://localhost:8000/docs")
            yield
        finally:
            await manager.stop()

    application = FastAPI(
        title="Pikdo Collector",
        version=version("collector"),
        description="通过 Playwright 正常导航商品页面并采集页面自身的详情响应。",
        openapi_tags=[
            {"name": "健康检查", "description": "检查服务是否正常运行，不访问上游。"},
            {
                "name": "小红书商品",
                "description": "解析商品 ID、商品链接和分享文案，以及采集商品详情。",
            },
        ],
        lifespan=lifespan,
    )

    @application.exception_handler(BrowserCollectionError)
    async def handle_browser_error(request: Request, exc: BrowserCollectionError) -> JSONResponse:
        code = exc.code if exc.code in BROWSER_HTTP_ERRORS else "BROWSER_NAVIGATION_FAILED"
        status, message = BROWSER_HTTP_ERRORS[code]
        content = {"code": code, "message": message}
        if "item_id" in request.path_params:
            content["item_id"] = request.path_params["item_id"].lower()
        return JSONResponse(status_code=status, content=content)

    @application.exception_handler(InputError)
    async def handle_input_error(request: Request, exc: InputError) -> JSONResponse:
        if "item_id" in request.path_params:
            error = ErrorResponse(
                code=exc.code, message=exc.message, item_id=request.path_params["item_id"].lower()
            )
        else:
            error = CollectErrorResponse(code=exc.code, message=exc.message)
        return JSONResponse(status_code=422, content=error.model_dump())

    @application.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        if request.url.path == COLLECT_PATH:
            # Never echo Pydantic's input values or invalid JSON bodies containing share tokens.
            return JSONResponse(
                status_code=422,
                content={
                    "code": "INVALID_PRODUCT_INPUT",
                    "message": "请求必须包含 1–8192 字符的 input 字符串",
                },
            )
        return await request_validation_exception_handler(request, exc)

    @application.get(
        "/health",
        tags=["健康检查"],
        summary="检查服务运行状态",
        description="返回当前服务运行状态，不请求小红书接口，不代表上游服务可用。",
        response_description="服务正常运行时返回 status=ok。",
    )
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.post(
        COLLECT_PATH,
        response_model=Product,
        tags=["小红书商品"],
        summary="通过分享文案或商品链接采集",
        description=(
            "支持 1–8192 字符分享文案、xhslink 短链、官方 H5 商品 URL 或商品 ID。"
            "使用共享 Playwright Browser 和每请求独立的 Context，直接导航原始分享入口，"
            "保留可信重定向上下文，捕获页面自身产生的商品详情响应。"
            "不通过 HTTPX 解析短链或调用商品详情，不自动重试或兜底。"
            "仅返回 Product，不保存或返回原始正文与分享凭据。"
            "默认并发 1、等待许可 5 秒；访问限制后阻断本管理器的后续浏览器采集。"
        ),
        responses={
            status: {"model": CollectErrorResponse, "description": description}
            for status, description in {
                422: "输入或请求结构非法。",
                404: "上游明确表示商品不存在或已下架。",
                502: "浏览器导航、响应、业务或商品身份验证失败。",
                503: "浏览器不可用、排队超时或访问受限。",
                504: "页面导航或商品响应超时。",
            }.items()
        },
    )
    async def collect_product(body: CollectRequest, request: Request) -> Product:
        navigation_input(body.input)
        return (await request.app.state.browser_manager.collect(body.input)).product

    @application.get(
        "/api/v1/xhs/products/{item_id}",
        response_model=Product,
        tags=["小红书商品"],
        summary="采集商品详情",
        description=(
            "通过商品 ID 使用 Playwright 正常打开规范 H5 页面，捕获页面自行产生的详情响应，返回标题、店铺、关联账号、价格、销量、"
            "图片、规格、促销、物流、售后服务和购买功能等标准化信息。\n\n"
            "商品 ID 必须为 24 位十六进制字符串，接受大小写并统一转为小写。"
            "金额以十进制字符串返回；缺失销量为 null，含万、w 或 + 的销量标为近似值。"
            "采集时间 fetched_at 使用 Asia/Shanghai 时区。\n\n"
            "纯 ID 查询没有原始分享上下文，不能保证与分享短链采集效果一致。"
            "不调用 HTTPX 详情 API，不自动兜底或重试。默认并发 1、等待许可 5 秒。"
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
                503: "浏览器不可用、排队超时或访问受限；受限后停止后续浏览器采集。",
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
            raise InputError("INVALID_ITEM_ID", "商品 ID 必须为 24 位十六进制字符串")
        return (await request.app.state.browser_manager.collect(item_id.lower())).product

    return application


app = create_app()


def main() -> None:
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
