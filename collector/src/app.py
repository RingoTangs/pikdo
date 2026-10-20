import logging
from collections.abc import Callable
from contextlib import asynccontextmanager
from importlib.metadata import version

from fastapi import FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from xhs.browser import BrowserConfig, BrowserManager, ProductCollectionService, ReadinessSnapshot
from xhs.browser_collector import BrowserCollectionError, navigation_input
from xhs.errors import BROWSER_HTTP_ERRORS, InputError
from xhs.schemas import (
    CollectErrorResponse,
    CollectRequest,
    Product,
    ReadinessResponse,
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
        return JSONResponse(status_code=status, content=content)

    @application.exception_handler(InputError)
    async def handle_input_error(request: Request, exc: InputError) -> JSONResponse:
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

    @application.get(
        "/ready",
        response_model=ReadinessResponse,
        tags=["健康检查"],
        summary="检查实例是否就绪",
        description="只观察本地 BrowserManager 状态及 Browser 连接，不访问小红书，不执行采集或恢复受限状态。采集和排队期间仍可就绪，不保证上游请求成功。",
        responses={
            503: {"model": ReadinessResponse, "description": "实例尚未具备接收采集请求的条件。"}
        },
    )
    async def ready(request: Request) -> JSONResponse:
        manager = getattr(request.app.state, "browser_manager", None)
        try:
            snapshot = (
                manager.readiness_snapshot()
                if manager is not None
                else ReadinessSnapshot("new", False, False, False, "not_started")
            )
            expected_ready = (
                snapshot.state == "ready"
                and snapshot.resource_healthy is True
                and snapshot.browser_connected is True
            )
            if snapshot.ready is not expected_ready:
                raise ValueError("Inconsistent readiness snapshot")
            body = ReadinessResponse(
                status="ready" if snapshot.ready else "not_ready",
                manager_state=snapshot.state,
                reason=snapshot.reason,
            )
        except Exception:
            # Fail closed without logging arbitrary provider exceptions or snapshot values.
            body = ReadinessResponse(
                status="not_ready", manager_state="unavailable", reason="browser_unavailable"
            )
        return JSONResponse(
            status_code=200 if body.status == "ready" else 503,
            content=body.model_dump(),
            headers={"Cache-Control": "no-store"},
        )

    @application.post(
        COLLECT_PATH,
        response_model=Product,
        tags=["小红书商品"],
        summary="统一采集单个商品",
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

    return application


app = create_app()


def main() -> None:
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
