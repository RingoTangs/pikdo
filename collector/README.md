# Pikdo Collector

小红书商品详情采集 API，使用 Python 3.13、uv、FastAPI 和 HTTPX。

## 启动

```bash
cd collector
uv sync
uv run dev
```

监听 `127.0.0.1:8000`。服务入口 <http://localhost:8000/> 自动跳转至文档。交互文档：<http://localhost:8000/docs>。

```bash
curl http://localhost:8000/health
curl http://localhost:8000/api/v1/xhs/products/6ab10b35d528ea0015dd1b7e
```

`GET /health` 返回 `{"status":"ok"}`，不访问上游。
商品接口仅接受 24 位十六进制 ID，统一转小写，只访问固定上游接口，不接受任意 URL。

## 返回字段

| 字段 | 来源或处理 |
| --- | --- |
| `item_id` | 规范化后的请求 ID |
| `title` | 优先 `descriptionMain.name`，回退 `descriptionH5.name` |
| `sku_title` | `descriptionH5.name` |
| `shop.id/name/sold_display` | `sellerH5.id/name/salesVolume` |
| `shop.fans_count` | 优先 `profitBarPopupH5.follow.fansNum`，回退解析 `sellerH5.fansAmount` |
| `price.current` | 依次选择有效的 `priceH5.dealPrice.price`、`bottomBarMainH5.dealPrice.price`、`priceH5.highlightPrice` |
| `price.display` | `priceH5.highlightPrice`，无效或缺失时回退实际价格 |
| `price.currency` | 默认 `CNY` |
| `sales.display` | 原始 `priceH5.itemAnalysisDataText` |
| `sales.estimated_count` | 累计销量解析值 |
| `sales.exact` | 普通整数为 `true`；含 `万`、`w`、`W`、`+` 为 `false`；无法解析为 `null` |
| `cover_url` | `carouselH5.images[0].url`，`//` 开头补 `https:` |
| `stock_status` | 原始 `carouselH5.stockStatus` 整数 |
| `deliverable` | `bottomBarMainH5.deliveryInfo.ableToDelivery` 布尔值 |
| `fetched_at` | Asia/Shanghai 时区，ISO 8601 格式，含 `+08:00` |

金额使用 Decimal 处理并输出字符串，不转换单位不明的价格字段。
仓库样例价格为 `"9.99"`，展示价格为 `"19.99"`；
`"已售2.3万+"` 对应 `estimated_count=23000`、`exact=false`。
这些是已保存样例的结果，不代表当前真实接口已验证成功。

可选字段缺失返回 `null`，缺失销量不会变成 0；明确“已售0”才返回零。
上游必须严格 `success=true`，且存在有效的 `data.template_data[0]` 和商品标题。
没有超过 10000 销量的查询限制，不因库存为零推断下架。

## 错误结构

商品接口错误直接返回 `code`、`message`、`item_id`，没有 `detail` 外层：

```json
{"code":"INVALID_ITEM_ID","message":"商品 ID 必须为 24 位十六进制字符串","item_id":"invalid"}
```

| HTTP 状态 | code | 含义 |
| --- | --- | --- |
| 422 | `INVALID_ITEM_ID` | 无效 ID，不请求上游 |
| 504 | `UPSTREAM_TIMEOUT` | 上游请求超时 |
| 503 | `UPSTREAM_ACCESS_RESTRICTED` | HTTP 401/403/429/461 或明确安全验证、登录消息 |
| 502 | `UPSTREAM_HTTP_ERROR` | 其他非成功 HTTP 状态 |
| 502 | `UPSTREAM_REQUEST_FAILED` | DNS、连接等传输失败 |
| 502 | `UPSTREAM_INVALID_JSON` | 返回无效 JSON |
| 502 | `UPSTREAM_INVALID_DATA` | 商品结构或标题缺失 |
| 502 | `UPSTREAM_BUSINESS_ERROR` | 未知业务失败 |
| 404 | `PRODUCT_UNAVAILABLE` | 明确不存在、下架、失效、违规状态或不可购买按钮 |

上游 HTTP 404、请求失败和字段缺失不会被误判为下架。
HTTPX 客户端在应用生命周期中复用并关闭；连接和连接池等待超时 5 秒，读写超时 10 秒。
不跟随重定向、不自动重试，不实现数据库、批量采集、代理池或浏览器兜底。

## 测试与构建

在 `collector` 目录执行：

```bash
uv run test
uv build
```

`uv run test` 默认发现并运行全部测试，也支持传递 pytest 参数，
例如 `uv run test -v` 或 `uv run test tests/test_parser.py -q`。
原有 `uv run pytest` 仍然可用。

测试使用完整 fixture 和 HTTPX MockTransport，不依赖真实网络。
真实请求需另行启动服务，通过上述 curl 命令检查结果。

## 目录

- `src/app.py`：应用生命周期、路由和启动入口。
- `src/xhs/`：上游客户端、解析器、Pydantic 模型及业务异常。
- `tests/`：API 与解析测试。
- [examples/README.md](examples/README.md)：分享原文、接口地址和完整 JSON 样例。

新增含 `__init__.py` 的包自动发现；新增独立顶层模块需加入 `py-modules`。
