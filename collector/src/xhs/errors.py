"""Transport-independent input/data errors and safe browser HTTP mappings."""


class ProductDataError(Exception):
    """Invalid or rejected product data; carries no HTTP-client state."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class InputError(Exception):
    """Safe local input or URL validation failure, independent of transport."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


# Fixed public messages: never echo exception text from Playwright or injected services.
BROWSER_HTTP_ERRORS: dict[str, tuple[int, str]] = {
    "BROWSER_PRODUCT_UNAVAILABLE": (404, "商品明确不存在、已下架或已失效"),
    "BROWSER_NAVIGATION_FAILED": (502, "浏览器页面导航失败"),
    "BROWSER_UNTRUSTED_REDIRECT": (502, "已阻止不受信任的页面跳转"),
    "BROWSER_INVALID_RESPONSE": (502, "商品响应未通过业务验证"),
    "BROWSER_PRODUCT_ID_MISMATCH": (502, "商品响应身份不一致"),
    "BROWSER_PRODUCT_RESPONSE_MISSING": (502, "未获得完整商品响应"),
    "BROWSER_LAUNCH_FAILED": (503, "浏览器初始化失败"),
    "BROWSER_ACCESS_RESTRICTED": (503, "上游访问受限，已停止浏览器采集，请人工检查"),
    "BROWSER_UNAVAILABLE": (503, "浏览器采集服务不可用"),
    "BROWSER_BUSY": (503, "等待浏览器采集许可超时"),
    "BROWSER_NAVIGATION_TIMEOUT": (504, "浏览器页面导航超时"),
    "BROWSER_PRODUCT_RESPONSE_TIMEOUT": (504, "等待商品详情响应超时"),
}
