# Pikdo Collector v0.4.0a1

小红书单商品采集服务，使用 Python 3.13、uv、FastAPI、Playwright 和 Pydantic v2。
Phase 2C 已收敛为统一 POST 商品采集接口，采用 Playwright-only 架构，不保留 HTTPX 上游采集、网络 Resolver、批量 CLI 或文件归档系统。

```text
Input Parser
    ↓
BrowserManager
    ↓
BrowserProductCollector
    ↓
Product Decoder → Product Parser / 业务模型
    ↓
Product API
```

## 安装与启动

```bash
cd collector
uv sync
uv run playwright install chromium
uv run dev
```

监听 `127.0.0.1:8000`，文档为 <http://localhost:8000/docs>。
生产环境必须安装匹配版本的 Chromium 二进制。系统 Chrome 可通过 BrowserConfig(channel="chrome") 使用。
HTTPX 仅保留在开发依赖中供 FastAPI TestClient 使用；生产源码不导入 HTTPX 或 requests。

## 两个业务 API

| 方法 | 路径                           | 行为                                             |
| ---- | ------------------------------ | ------------------------------------------------ |
| GET  | `/health`                      | 本地存活检查，返回 `{"status":"ok"}`，不访问上游 |
| POST | `/api/v1/xhs/products/collect` | 从单条分享输入采集并返回 Product                 |

```bash
curl http://localhost:8000/health
curl -X POST http://localhost:8000/api/v1/xhs/products/collect \
  -H 'Content-Type: application/json' \
  -d '{"input":"https://xhslink.com/m/4tQFfvENODH"}'
```

POST 输入的纯商品 ID 必须为 24 位十六进制字符，接受大写并统一为小写。纯 ID 采集没有原始分享上下文，不能保证与分享入口的采集效果一致。
POST 的 CollectRequest 支持完整小红书分享文案、xhslink 短链、官方 goods-detail H5 URL 或纯商品 ID。
input 必须为 1–8192 字符字符串，拒绝额外字段。验证失败不会回显敏感文案。
四类请求均使用同一 POST 接口，例如：

```json
{"input":"6ac8da9a7e1073000114c7ae"}
{"input":"https://www.xiaohongshu.com/goods-detail/6ac8da9a7e1073000114c7ae"}
{"input":"https://xhslink.com/m/4tQFfvENODH"}
{"input":"【小红书】汤姆猫动漫闪卡 😆 53e39MN5Ck5 😆 https://xhslink.com/m/4tQFfvENODH 点击链接或者复制本条信息打开【小红书app】查看 CZ6281"}
```

以上每行是一个独立请求正文。Collector 无状态、实时执行采集，不提供商品数据库查询。
旧商品 GET 接口已移除且没有兼容路由；未来商品查询、缓存和任务管理由 Java SaaS 服务负责，本阶段不实现。

Input Parser 只做本地文本和 URL 校验，保留 HTML 实体、有限转义、分享文本、多目标拒绝及域名/端口/路径安全规则。
短链由浏览器直接打开；合法 H5 或短链的原始查询参数保留在内存导航上下文中，不先解析成无参数 URL。
API 只返回标准化 Product，不保存或返回原始响应、Cookie、签名或完整敏感跳转 URL。

旧 POST resolve API 和 collect-examples CLI 已移除，没有兼容入口。历史本地数据不会自动删除或恢复。

## BrowserManager 与安全边界

Lifespan 启动一次 Playwright 和一个 Chromium Browser。Browser 是浏览器进程，BrowserContext 是隔离的存储环境。
每次请求获得许可后新建 Context，Cookie 和 LocalStorage 不跨请求共享；成功、失败、超时和取消都会释放资源，然后释放许可。
BrowserProductCollector 的外部 Browser 模式与 browser-probe 独立模式共用同一页面采集核心。

导航前注册响应监听，CDP 在主框架每次 Document 请求发送前验证目标。
仅捕获页面正常加载自行发出的 GET 商品详情响应，严格匹配 hostname、完整路径及 item_id；不主动构造详情 API 请求。
原始正文直接来自 response.body()，JSON/Decimal 解码不会修改 bytes。SKU 可以不同，但不能代替明确的商品 ID 验证。

```python
from app import create_app
from xhs.browser import BrowserConfig

application = create_app(browser_config=BrowserConfig(channel="chrome"))
```

| 参数                       | 默认值                                                    |
| -------------------------- | --------------------------------------------------------- |
| headless                   | True                                                      |
| channel                    | None（Chromium），可设 chrome                             |
| max_concurrent_collections | 1                                                         |
| queue_timeout              | 5.0 秒                                                    |
| navigation_timeout         | 30.0 秒，也限制启动和页面初始化                           |
| response_timeout           | 15.0 秒                                                   |
| cleanup_timeout            | 5.0 秒，分别限制监听、Context、Browser 和 Playwright 清理 |

信号量限制运行并发，不代表队列容量；等待许可时不创建 Context，超时返回 BROWSER_BUSY。
浏览器启动失败会阻止应用启动。运行期断开或清理失败会停用管理器，不自动重启。
HTTP 401/403/429/461、登录、安全验证或明确访问限制会停止运行中、排队和后续采集，等待人工检查。
不重试、不注入 Cookie/签名、不伪造指纹、不使用代理池或验证绕过；没有 HTTPX 兜底或恢复接口。
管理器状态仅在进程内有效，当前按单服务工作进程运行。health 不表示浏览器或上游可用。

## 错误结构

商品采集错误直接返回 `code`、`message`，没有 `detail` 外层：

```json
{"code":"INVALID_PRODUCT_INPUT","message":"输入必须为 1–8192 字符的字符串"}
```

| HTTP 状态 | code                                                                                            | 含义                           |
| --------- | ----------------------------------------------------------------------------------------------- | ------------------------------ |
| 422       | `INVALID_PRODUCT_INPUT` 等本地输入错误                                                          | collect 输入或请求结构非法     |
| 404       | `BROWSER_PRODUCT_UNAVAILABLE`                                                                   | 仅上游明确不存在、下架或失效   |
| 502       | `BROWSER_NAVIGATION_FAILED` / `BROWSER_UNTRUSTED_REDIRECT`                                      | 导航失败或不受信任跳转         |
| 502       | `BROWSER_INVALID_RESPONSE` / `BROWSER_PRODUCT_ID_MISMATCH` / `BROWSER_PRODUCT_RESPONSE_MISSING` | 响应、业务或身份校验失败       |
| 503       | `BROWSER_LAUNCH_FAILED` / `BROWSER_UNAVAILABLE`                                                 | 浏览器初始化失败或运行期不可用 |
| 503       | `BROWSER_ACCESS_RESTRICTED` / `BROWSER_BUSY`                                                    | 已停止浏览器采集或等待许可超时 |
| 504       | `BROWSER_NAVIGATION_TIMEOUT` / `BROWSER_PRODUCT_RESPONSE_TIMEOUT`                               | 页面导航或商品响应超时         |

标准化业务 JSON 与 URL 继续过滤敏感信息，并补充过滤 x-s、x-t 和原始分享追踪参数；原始响应 bytes 不受影响。

POST collect 的所有采集错误只包含 `code`、`message`。
请求失败、上游 HTTP 404、字段缺失和响应超时均不会推断商品下架。错误使用固定公开消息，不回显浏览器异常、凭据或分享文案。

## 返回字段

| 字段                        | 来源或处理                                                                                            |
| --------------------------- | ----------------------------------------------------------------------------------------------------- |
| `item_id`                   | 规范化后的请求 ID                                                                                     |
| `title`                     | 优先 `descriptionMain.name`，回退 `descriptionH5.name`                                                |
| `sku_title`                 | `descriptionH5.name`                                                                                  |
| `shop.id/name/sold_display` | `sellerH5.id/name/salesVolume`                                                                        |
| `shop.fans_count`           | 优先 `profitBarPopupH5.follow.fansNum`，回退解析 `sellerH5.fansAmount`                                |
| `price.current`             | 依次选择有效的 `priceH5.dealPrice.price`、`bottomBarMainH5.dealPrice.price`、`priceH5.highlightPrice` |
| `price.display`             | `priceH5.highlightPrice`，无效或缺失时回退实际价格                                                    |
| `price.currency`            | 默认 `CNY`                                                                                            |
| `sales.display`             | 原始 `priceH5.itemAnalysisDataText`                                                                   |
| `sales.estimated_count`     | 累计销量解析值                                                                                        |
| `sales.exact`               | 普通整数为 `true`；含 `万`、`w`、`W`、`+` 为 `false`；无法解析为 `null`                               |
| `cover_url`                 | `carouselH5.images` 中首张有效图片 URL，`//` 开头补 `https:`                                          |
| `stock_status`              | 原始 `carouselH5.stockStatus` 整数                                                                    |
| `deliverable`               | `bottomBarMainH5.deliveryInfo.ableToDelivery` 布尔值                                                  |
| `fetched_at`                | Asia/Shanghai 时区，ISO 8601 格式，含 `+08:00`                                                        |

金额使用 Decimal 处理并输出字符串，不转换单位不明的价格字段。
仓库样例价格为 `"9.99"`，展示价格为 `"19.99"`；
`"已售2.3万+"` 对应 `estimated_count=23000`、`exact=false`。
这些是已保存样例的结果，不代表当前真实接口已验证成功。

可选字段缺失返回 `null`，缺失销量不会变成 0；明确“已售0”才返回零。
上游必须严格 `success=true`，且存在有效的 `data.template_data[0]` 和商品标题。
没有超过 10000 销量的查询限制，不因库存为零推断下架。

## Product 业务模型与字段映射

Product 对象包含固定业务字段；可选标量为 `null`，列表为 `[]`。
以下为结构摘要（字段内容请以 `/docs` 中的模型为准）：

```text
Product
├── item_id, title, sku_title, cover_url, stock_status, deliverable, fetched_at
├── price {current, display, currency}               # 保持 v0.1 结构
├── sales {display, estimated_count, exact}         # 保持 v0.1 结构
├── basic_info {product_item_id, sku_id, product_tags[], sku_tags[]}
├── shop {id, name, fans_count, sold_display, logo, link, grade,
│         seller_score, seller_score_text, fans_display, next_title,
│         sold_estimated_count, sold_exact}
├── seller_account {name, logo, fans_num, seller_user_id, seller_id}
├── pricing_details {highlight_price, price_integer_part, price_decimal_part,
│   deal_price {price, type, price_text, is_show_price_pop},
│   bottom_price_raw, bottom_deal_price, multiple_discount[], tags[],
│   formula {result {name, price}, steps[{name, price}]}}
├── media {images[], detail_description, detail_images[]}
├── variants {selected_sku_id, selected_text, specifications[], title,
│             parameters[{name, value}], sku_type, sku_num}
├── promotions {name, tags[], profit_type, promotion_type, promotion_id,
│   query_payment_marketing_info, title, activities[{promotion_id,
│   promotion_type, promotion_name, name, title, validate_time,
│   contents[{tag, description, validate_time, gift_goods[]}]}]}
├── shipping {location, fee, time {text, mode, tag}, able_to_delivery,
│             unable_to_delivery_text, button_text}
├── services {popup_items[], summary_items[], items[]}
├── purchase {buttons[], show_customer_service, customer_service_name,
│             customer_service_link, is_single_variant, coupon_infos[]}
├── additional_info {price_description {title, items[{name, value}]},
│   faq {title, items[{name, value}]}, has_comments, has_followed, is_followed}
└── metadata {template_version, sku_ids_consistent}
```

图片条目保留 `url/thumbnail/width/height/cover/video_id/media/type`，保留数组顺序；无 URL 的图片对象仍保留，封面选首张有效主图。
购买按钮保留 `type/text/sub_text/action_type/style_type`。
服务详情保留 `title/description/icon/type`，服务摘要保留 `name/icon/type`；
`services.items` 按去除首尾空格的标题合并，详情优先、摘要补充，保留两份来源列表，样例最终展示三项服务。

### 新增字段来源

所有数据块均位于 `data.template_data[0]`，以下路径省略此前缀：

| 输出                                                                                        | 来源                                                                                                              |
| ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `basic_info.product_item_id/sku_id`                                                         | `descriptionMain.itemId` / `descriptionH5.skuId`                                                                  |
| `basic_info.product_tags/sku_tags`                                                          | `descriptionMain.tags` / `descriptionH5.tags`                                                                     |
| `shop.logo/link/grade/seller_score/seller_score_text`                                       | `sellerH5.logo/link/grade/sellerScore/sellerScoreText`                                                            |
| `shop.fans_display/next_title`                                                              | `sellerH5.fansAmount/nextTitle`                                                                                   |
| `shop.sold_estimated_count/sold_exact`                                                      | 解析 `sellerH5.salesVolume`，含万、w 或 + 均为近似值                                                              |
| `seller_account`                                                                            | `profitBarPopupH5.follow.name/logo/fansNum/sellerUserId/sellerId`                                                 |
| `pricing_details.highlight_price/price_integer_part/price_decimal_part`                     | `priceH5.highlightPrice/priceIntegerPart/priceDecimalPart`                                                        |
| `pricing_details.deal_price`                                                                | `priceH5.dealPrice.price/type/priceText/isShowPricePop`                                                           |
| `pricing_details.multiple_discount/tags`                                                    | `priceH5.multipleDiscount/tags`                                                                                   |
| `pricing_details.bottom_price_raw/bottom_deal_price`                                        | `bottomBarMainH5.price/dealPrice`                                                                                 |
| `pricing_details.formula.result/steps`                                                      | `profitBarPopupH5.formula.result/step`，保留每项 name、price                                                      |
| `media.images`                                                                              | 完整 `carouselH5.images`                                                                                          |
| `media.detail_description/detail_images`                                                    | `graphicDetailsV4.description/images`                                                                             |
| `variants.selected_sku_id/selected_text/specifications`                                     | `selectedH5.skuId/text/specifications`                                                                            |
| `variants.title/parameters/sku_type`                                                        | `variantsParams.title/list`、`skuInfo.type`                                                                       |
| `variants.sku_num`                                                                          | **信封层** `data.common_data` JSON 字符串中的 `statisticInfo.skuNum`                                              |
| `promotions.name/tags/profit_type/promotion_type/promotion_id/query_payment_marketing_info` | `profitBarV1` 对应字段                                                                                            |
| `promotions.title/activities`                                                               | `profitBarPopupH5.promotionV1.title/list`                                                                         |
| `promotions.activities[].contents`                                                          | 活动中的 `contents`，保留 tag、description、validateTime、giftGoods                                               |
| `shipping.location/fee/time`                                                                | `goodsDistributeV4.location/fee/time.text/mode/tag`                                                               |
| `shipping.able_to_delivery/unable_to_delivery_text/button_text`                             | `bottomBarMainH5.deliveryInfo` 对应字段                                                                           |
| `services.popup_items/summary_items`                                                        | `servicePopupV2.list` / `serviceV5.list`                                                                          |
| `purchase`                                                                                  | `bottomBarMainH5.buttons/showCustomerService/customerServiceName/customerServiceLink/isSingleVariant/couponInfos` |
| `additional_info.price_description/faq`                                                     | `priceDescription.title/list`、`normalFaq.title/list`                                                             |
| `additional_info.has_comments/has_followed/is_followed`                                     | `headerBarMainV2.hasComments/hasFollowed/isFollowed`                                                              |
| `metadata.template_version`                                                                 | **信封层** `data.template_version`                                                                                |
| `metadata.sku_ids_consistent`                                                               | 比较 `descriptionH5/selectedH5/carouselH5/bottomBarMainH5.skuId`，不比较商品 itemId                               |

评分保持原始字符串 `"4.0"`、`"4.20"`；金额使用 Decimal 并输出字符串，公式保留上游步骤名称。
`highlight_price` 不解释为原价。`bottom_price_raw=999` 的单位未知，仅保留整数，不参与价格计算；
该原始字段若是非整数数值，使用十进制字符串输出。零金额有效，非有限或负金额视为无效可选值。

商品 ID 和 SKU ID 不要求相同、不会相互覆盖；已知 SKU 来源不一致时标记 `sku_ids_consistent=false`，不拒绝查询，
不足两个 SKU 来源时为 `null`。关联账号身份与店铺身份独立保留，不强行合并。
`common_data` 解析失败或 skuNum 类型错误时 `sku_num=null`，不影响整个商品；不据此生成 SKU 列表。
有效期只保留原始文案，不臆测时区和精确时间。

### 数据块覆盖统计

真实样例共有 **29** 个顶层块，**18** 个非空，**11** 个为空占位（false 等明确布尔值视为有信息）。

| 数据块                 | 样例状态 | 处理                                               |
| ---------------------- | -------- | -------------------------------------------------- |
| sellerH5               | 非空     | 店铺身份、评分、粉丝和销量                         |
| descriptionH5          | 非空     | SKU 标题、ID、标签                                 |
| bottomBarMainH5        | 非空     | 价格、配送、按钮及客服；跳过埋点和空占位           |
| priceH5                | 非空     | 完整价格、销量、优惠与标签                         |
| servicePopupV2         | 非空     | 服务保障详情                                       |
| carouselH5             | 非空     | 六张主图和库存；空视频及营销图片暂不建模           |
| selectedH5             | 非空     | 已选规格、SKU ID、specifications                   |
| profitBarV1            | 非空     | 促销摘要和标签                                     |
| graphicDetailsV4       | 非空     | 九张详情图片及说明；不重复保存店铺 ID              |
| headerBarMainPopup     | 非空     | 不提取，主要为分享、导航和追踪链接                 |
| descriptionMain        | 非空     | 商品标题、商品 ID、标签                            |
| priceDescription       | 非空     | 价格说明                                           |
| profitBarPopupH5       | 非空     | 公式、促销活动、关联账号                           |
| serviceV5              | 非空     | 服务摘要，并参与合并展示                           |
| goodsDistributeV4      | 非空     | 发货地、费用和时效                                 |
| headerBarMainV2        | 非空     | 评论与关注状态；跳过搜索、占位、更多按钮等 UI 设置 |
| variantsParams         | 非空     | 规格参数                                           |
| skuInfo                | 非空     | SKU 类型                                           |
| installmentPopupV1     | 空占位   | 暂不建模分期组件                                   |
| descriptionExtraInfoV1 | 空占位   | 暂不建模                                           |
| depositSteps           | 空占位   | 暂不建模预售步骤                                   |
| priceWarmUp            | 空占位   | 暂不建模预热价格                                   |
| fissionCoupon          | 空占位   | 暂不建模裂变优惠券                                 |
| campaignBanner         | 空占位   | 暂不建模活动横幅                                   |
| consumeWarning         | 空占位   | 暂不建模消费提示                                   |
| installmentV1          | 空占位   | 暂不建模分期摘要                                   |
| displayO2OShop         | 空占位   | 暂不建模线下店铺                                   |
| normalFaq              | 空占位   | 已定义 FAQ 模型，为后续返回保留空数组              |
| goodsDistributePopupV4 | 空占位   | 暂不建模物流弹窗                                   |

### 类型与安全

可选数字、布尔值、字符串严格检查，不把 `true` 当整数或 `"false"` 当布尔值；错误类型降为 `null` 或空数组。
列表中非对象项在要求对象的列表内跳过，其余对象保留顺序与部分缺失字段。
模型采用白名单，不公开原始响应或内部模板。

样例尚无条目结构的业务数组（tags、specifications、multipleDiscount、couponInfos、giftGoods）及图片 media/cover
使用经递归过滤的 JSON 值，不编造不存在的 SKU、优惠券和媒体结构；其中 Decimal 数值使用字符串。
JSON 字符串若可解析则过滤为 JSON 值；不透传敏感字段、追踪和布局配置。
URL 支持 HTTP(S)、图片的 `//` 前缀，以及客服字段的 `xhsdiscover` 链接；移除令牌、签名、追踪参数及 fragment，
拒绝带用户名密码的 URL；保留 CDN 图片处理参数。
移除签名可能导致需要签名的图片不可访问，API 不保证这些资源的可访问性。

`examples/` 保存只读原始数据，lint 和 format 完全跳过，不得脱敏、重排、删减或改变原始字节。
解析器在内存中提取业务字段，脱敏仅作用于 API 输出，不修改输入或原始文件。
测试读取原始样本；需要构造异常或敏感字段时，仅修改内存副本，并使用虚构值。

## browser-probe 独立调试

```bash
uv run browser-probe --help
uv run browser-probe --input "https://xhslink.com/m/4tQFfvENODH"
uv run browser-probe --input "https://xhslink.com/m/4tQFfvENODH" \
  --headed --channel chrome --output .browser-probe
```

CLI 使用临时 Browser，支持分享文案、短链、H5 链接和 ID。默认不落盘；指定输出时，只有采集成功后才原子写入 response.json。
正文不重编码、格式化或脱敏；日志仅输出安全状态、商品摘要、字节数、SHA-256 和耗时。
符号链接、原始 examples 和历史归档目录均禁止作为输出目标；仓库内输出必须已被 Git 忽略。
`.browser-probe/` 已被忽略，目录标记用于保护原始正文；不要删除标记后格式化这些数据。

## 只读样例与历史数据

`examples/response.json`、`share_text.md`、`README.md` 保留原始字节，作为 Parser 和 Input Parser 的参考数据。
不生成、迁移或自动清理样例，也不删除旧工具产生的商品目录、标记或锁文件。
历史忽略规则保留，带 `.pikdo-example-archive` 标记的目录只被识别为保护边界，不再执行索引、缓存或恢复业务。

format/lint 跳过整个 examples、带 probe 或历史标记的目录及其后代，也跳过隐藏/临时文件、符号链接、虚拟环境、缓存和构建产物。
直接调用 JSON 格式化入口也遵守这些保护。普通源码和文档仍使用 Ruff、mdformat；普通 JSON 用 simplejson/Decimal 保持精度和顺序。

## 测试与质量检查

```bash
uv sync
uv run format
uv run lint
uv run test -v
uv build
# 单独运行真实 Chromium，仅访问 loopback 模拟页面
PIKDO_LOCAL_BROWSER_TESTS=1 uv run test tests/test_browser_local.py -v
```

常规测试显式注入假采集服务或浏览器对象，不依赖真实上游。测试修改原始 fixture 的内存副本。
本地 Chromium 测试验证重定向拦截、页面自身响应捕获、共享 Browser 与 Context 存储隔离。
真实小红书验证必须独立执行；模拟或本地测试通过不能代表真实采集成功。遇到限制立即停止，不自动重试。
`create_app(browser_manager_factory=...)` 保留服务注入，生产工厂不再接受 HTTPX transport。
仓库根目录提交前执行 `git diff --check`。安装、调试及历史真实验证记录见 [Playwright 文档](../docs/2.playwright-commands.md)。

## 模块与后续范围

- `app.py`：两个业务 API、OpenAPI 和 Lifespan。
- `xhs/input_parser.py`：纯文本解析与可信 URL 校验。
- `xhs/browser.py`、`browser_collector.py`：浏览器生命周期和单次页面采集。
- `xhs/product_decoder.py`、`parser.py`、`domains.py`、`schemas.py`、`values.py`：解码、业务映射、模型和输出过滤。
- `xhs/browser_probe.py`、`probe_paths.py`：独立调试与只读路径保护。
- `tooling.py`：格式化和只读检查。

InputError 表示本地输入错误，ProductDataError 表示数据或业务拒绝，BrowserCollectionError 保留浏览器错误语义；数据异常不携带 HTTPX 状态或响应头。
后续可单独设计正式任务系统和持久化；本阶段不新增批量采集、文件归档、数据库、对象存储或队列。
