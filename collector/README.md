# Pikdo Collector v0.4.0a1

小红书商品采集 API，使用 Python 3.13、uv、FastAPI 和 Playwright。Phase 2A 将生产 GET/collect 接入共享 Browser；Resolver 和批量归档暂时保留 HTTPX。

## 启动

```bash
cd collector
uv sync
uv run playwright install chromium
uv run dev
```

监听 `127.0.0.1:8000`。交互文档：<http://localhost:8000/docs>。

```bash
curl http://localhost:8000/health
curl http://localhost:8000/api/v1/xhs/products/6ab10b35d528ea0015dd1b7e
```

`GET /health` 返回 `{"status":"ok"}`，不访问上游。
GET 商品接口接受 24 位十六进制 ID，统一转小写，通过 Chromium 打开规范 H5 页面。
POST collect 接受分享文案、可信短链、官方 H5 商品 URL 或商品 ID，正常导航原始入口后捕获页面自身的详情响应。
两者仅返回现有 Product，不通过 HTTPX 主动请求详情、不自动兜底，不持久化原始正文。

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

## 错误结构

GET 商品接口错误直接返回 `code`、`message`、`item_id`，没有 `detail` 外层：

```json
{"code":"INVALID_ITEM_ID","message":"商品 ID 必须为 24 位十六进制字符串","item_id":"invalid"}
```

| HTTP 状态 | code                                                                                            | 含义                           |
| --------- | ----------------------------------------------------------------------------------------------- | ------------------------------ |
| 422       | `INVALID_ITEM_ID`                                                                               | GET 商品 ID 非法               |
| 422       | `INVALID_PRODUCT_INPUT` 等本地输入错误                                                          | collect 输入或请求结构非法     |
| 404       | `BROWSER_PRODUCT_UNAVAILABLE`                                                                   | 仅上游明确不存在、下架或失效   |
| 502       | `BROWSER_NAVIGATION_FAILED` / `BROWSER_UNTRUSTED_REDIRECT`                                      | 导航失败或不受信任跳转         |
| 502       | `BROWSER_INVALID_RESPONSE` / `BROWSER_PRODUCT_ID_MISMATCH` / `BROWSER_PRODUCT_RESPONSE_MISSING` | 响应、业务或身份校验失败       |
| 503       | `BROWSER_LAUNCH_FAILED` / `BROWSER_UNAVAILABLE`                                                 | 浏览器初始化失败或运行期不可用 |
| 503       | `BROWSER_ACCESS_RESTRICTED` / `BROWSER_BUSY`                                                    | 已停止浏览器采集或等待许可超时 |
| 504       | `BROWSER_NAVIGATION_TIMEOUT` / `BROWSER_PRODUCT_RESPONSE_TIMEOUT`                               | 页面导航或商品响应超时         |

标准化业务 JSON 与 URL 继续过滤敏感信息，并补充过滤 x-s、x-t 和原始分享追踪参数；原始响应 bytes 不受影响。

GET 错误包含 `code`、`message`、`item_id`；POST collect 错误只包含 `code`、`message`。
请求失败、上游 HTTP 404、字段缺失和响应超时均不会推断商品下架。错误使用固定公开消息，不回显浏览器异常、凭据或分享文案。

## Phase 2A：生产浏览器采集

```bash
curl -X POST http://localhost:8000/api/v1/xhs/products/collect \
  -H 'Content-Type: application/json' \
  -d '{"input":"https://xhslink.com/m/4tQFfvENODH"}'
curl http://localhost:8000/api/v1/xhs/products/6ac8da9a7e1073000114c7ae
```

POST collect 直接打开原始短链，保留可信重定向带来的分享上下文；不先调用 Resolver 或 HTTPX。
GET 只有商品 ID，从无分享参数的规范 H5 页面导航，可能无法获得与分享入口相同的结果。
POST 成功不代表 GET 成功；没有有效响应时返回明确错误，不拼造 token 或回退到 HTTPX。

BrowserManager 在 FastAPI Lifespan 中启动一个 Playwright Chromium Browser，并在关闭时释放任务、Browser 和 Playwright。
Browser 是浏览器进程；BrowserContext 是该进程中的独立浏览器存储环境。每次采集新建 Context，Cookie、LocalStorage 不跨请求共享。
启动失败会阻止应用启动；运行期 Browser 断开会停止当前及排队采集，并拒绝新采集，不自动重启。
`/health` 保持本地存活检查，不表示浏览器或上游可用。

简单配置对象可通过应用工厂传入：

```python
from app import create_app
from xhs.browser import BrowserConfig

application = create_app(browser_config=BrowserConfig(channel="chrome"))
```

| 参数                         | 默认值                                                          |
| ---------------------------- | --------------------------------------------------------------- |
| `headless`                   | `True`                                                          |
| `channel`                    | `None`（Playwright Chromium）；可设 `"chrome"` 使用系统 Chrome  |
| `max_concurrent_collections` | `1`                                                             |
| `queue_timeout`              | `5.0` 秒                                                        |
| `navigation_timeout`         | `30.0` 秒；也限制启动和 Context 创建                            |
| `response_timeout`           | `15.0` 秒                                                       |
| `cleanup_timeout`            | `5.0` 秒；分别限制页面监听、Context、Browser 和 Playwright 清理 |

信号量限制同时运行的采集，不代表队列容量限制；等待许可时不创建 Context。
取消请求会等待其资源释放后再释放许可；服务关闭会停止接单、终止等待和运行任务并完成清理。
清理异常时管理器不可用并尝试关闭 Browser，不继续复用可能泄漏的 Context。

HTTP 401/403/429/461、登录、安全验证或明确访问限制会锁存 `BROWSER_ACCESS_RESTRICTED`，停止同管理器的运行中、排队及后续采集。
没有自动恢复、重置接口或重试；应先人工检查。状态是进程本地的，部署应保持单服务工作进程；本阶段不新增多个浏览器工作进程。

`POST /api/v1/xhs/products/resolve` 仍使用原有 HTTPX 短链解析；`collect-examples` 仍用旧 HTTPX 详情客户端。
两者保留旧实现和独立测试，分别留待 Phase 2B、Phase 3 迁移。浏览器阻断与 HTTPX 阻断互不传播。
原始 JSON 解码现位于纯数据模块 `xhs.product_decoder`，客户端保留兼容导出；Decimal、业务判断和旧归档身份语义不变，浏览器额外要求明确商品 ID 一致。
测试可用 `create_app(browser_manager_factory=...)` 注入实现 start/stop/collect 的假服务，不需要启动浏览器或全局替换状态。

## 测试与构建

在 `collector` 目录执行：

```bash
uv run test
uv build
```

`uv run test` 默认发现并运行全部测试，也支持传递 pytest 参数，
例如 `uv run test -v` 或 `uv run test tests/test_parser.py -q`。
原有 `uv run pytest` 仍然可用。

产品 API 测试显式注入假采集服务；HTTPX MockTransport 仅用于旧客户端、Resolver 和归档测试，不再驱动生产 GET。
常规测试不依赖真实浏览器或真实网络；本地 Chromium 与真实小红书 API 验证单独执行和报告。
真实请求需另行启动服务，通过上述 curl 命令检查结果。

## 目录

- `src/app.py`：应用生命周期、路由和启动入口。
- `src/xhs/`：上游客户端、解析器、Pydantic 模型及业务异常。
- `tests/`：API 与解析测试。
- [examples/README.md](examples/README.md)：分享原文、接口地址和完整 JSON 样例。

新增含 `__init__.py` 的包自动发现；新增独立顶层模块需加入 `py-modules`。

## v0.2 扩展结构

旧字段、请求路径和错误结构继续保留。新增对象均包含固定字段；可选标量为 `null`，列表为 `[]`。
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

`examples/` 保存原始数据，允许 lint 检查和 format 排版，不得脱敏、删减或改变业务内容。
解析器在内存中提取业务字段，脱敏仅作用于 API 输出，不修改输入或原始文件。
测试读取原始样本；需要构造异常或敏感字段时，仅修改内存副本，并使用虚构值。

### 后续持久化建议

本次不增加存储。后续可分别保存规范化商品快照、采集时间、接口模板版本及解析器版本；
保留字符串金额和销量精确性标记，不用累计值替代日销量。
如确需原始归档，应放在内部存储层，按租户隔离、限制访问、加密和脱敏，并设置保留期限与清理机制，
不要通过公开商品 API 直接返回原始响应。

## 代码检查与格式化

在 `collector` 目录执行：

```bash
uv sync
uv run lint
uv run format
```

- `lint`：只读检查 Python 代码、import 顺序以及 Python、Markdown、JSON 格式；有问题时返回非零状态。
- `format`：执行 Ruff 安全修复和格式化、Markdown 与 JSON 格式化；无法安全修复的问题仍需手动处理。不会启用 unsafe fixes。

两条命令扫描 Collector（包含 `examples/`），强制跳过隐藏目录（包含虚拟环境和缓存）、
`build/`、`dist/`、`node_modules/`、`__pycache__/`、`venv/`、`env/`，以及符号链接。
根目录文档和 `docs/` 不在范围内。某类文件不存在时跳过。

Python 使用 Ruff，规则为 `E4/E7/E9/F/I`，目标 Python 3.13、100 列、四空格缩进和双引号。
Markdown 使用 mdformat 与 GFM 插件，支持表格，保留段落换行，不格式化代码块。
JSON 使用 simplejson 与 Decimal，四空格缩进、保留中文、键顺序和数组顺序、末尾换行；
保留数字类型和精度，不排序键。无效 JSON、重复键或非有限数值会报错，不覆盖该文件。
`examples/` 中的 Markdown 和 JSON 使用相同格式规则；只调整排版，不运行脱敏或字段过滤。

推荐提交前依次执行 `uv run format`、`uv run lint`、`uv run test -v` 和 `uv build`。

## v0.3.0 商品输入解析

新增 `POST /api/v1/xhs/products/resolve`，将输入解析为商品 ID 和无追踪参数的规范链接。
本接口不自动采集商品详情，也不根据解析失败判断商品下架。

| 输入类型     | 示例                                                                | source_type  | 网络请求         |
| ------------ | ------------------------------------------------------------------- | ------------ | ---------------- |
| 纯商品 ID    | `6ab10b35d528ea0015dd1b7e`                                          | `direct_id`  | 无               |
| H5 商品链接  | `https://www.xiaohongshu.com/goods-detail/6ab10b35d528ea0015dd1b7e` | `h5_url`     | 无               |
| 短链接       | `https://xhslink.com/m/9izjN4oPZd4`                                 | `short_url`  | 只请求受信任短链 |
| 完整分享文案 | 下方真实样例                                                        | `share_text` | 含短链时需要     |

```text
【小红书】【前3000单必出盘】 😆 3ukhXCGSwNE 😆 https://xhslink.com/m/9izjN4oPZd4 点击链接或者复制本条信息打开【小红书app】查看 CZ6125
```

支持反斜杠转义的实际文本（注意 JSON 请求中每个反斜杠还需要转义一次）：

```text
【小红书】【前3000单必出盘】 😆 3ukhXCGSwNE 😆 https\://xhslink.com/m/9izjN4oPZd4 点击链接或者复制本条信息打开【小红书app】查看 CZ6125&#x20;
```

支持 `https://`、`https\://`、`https:\/\/` 和 URL 斜杠转义，
首尾空格、多空格、换行、中文标点、emoji、Markdown 链接和尖括号/括号包装。
HTML 实体如 `&#x20;`、`&amp;` 只解码一次；不反复解码或解码百分号编码内容。
Markdown 或括号包装的 URL 归类为 `share_text`。

```bash
curl -X POST http://localhost:8000/api/v1/xhs/products/resolve \
  -H 'Content-Type: application/json' \
  -d '{"input":"https://xhslink.com/m/9izjN4oPZd4"}'
```

成功结构（以上样例在短链跳转到该商品时）：

```json
{
    "source_type": "short_url",
    "item_id": "6ab10b35d528ea0015dd1b7e",
    "canonical_url": "https://www.xiaohongshu.com/goods-detail/6ab10b35d528ea0015dd1b7e"
}
```

纯 ID、H5 和完整分享文案对应同一商品时，返回相同的 `item_id` 和 `canonical_url`，
仅 `source_type` 不同。需要完整商品信息时，再使用返回的 ID 调用已有接口：

```bash
curl http://localhost:8000/api/v1/xhs/products/6ab10b35d528ea0015dd1b7e
```

### 校验和重定向边界

- `input` 必须是 1–8192 字符的字符串，不接受空白、其他 JSON 类型或额外请求字段。
- ID 只在整个输入完整匹配时识别，统一小写；不在文案中搜索任意 24 位代码。
- H5 只接受 HTTPS、精确域名 `xiaohongshu.com` / `www.xiaohongshu.com` 和商品路径。
  允许末尾斜杠；只从 path 取 ID，绝不从 query、fragment、instation_link 或 token 取 ID。
- 短链仅请求 `xhslink.com` / `www.xhslink.com`。路径支持一至两段字母、数字、下划线或连字符，
  包括 `/m/短码`、`/a/短码` 和单段旧式短码；不接受空路径、点段或百分号编码路径。
- 所有 URL 拒绝凭证、IP 字面量、未知域名和非 HTTPS；只允许缺省端口或 443。
  请求按校验后的 hostname 和 path 重新构造，不发送分享 query 或 fragment。
- 短链 GET 使用共享 HTTPX 客户端，不开启自动跳转、不自动重试。
  手动处理 301/302/303/307/308，最多三次重定向响应；每一跳重新校验。
  第三跳指向合法 H5 可成功，仍指向短链则失败，不发第四次请求。
- 合法相对 Location 基于当前短链解析并重新校验；不可信目标不会被访问。
  一旦 Location 是合法 H5 商品页，立即返回，不请求该 H5 页面或商品详情接口。
- HTTPX 响应 hook 在库预构造下一跳请求之前校验 Location 并移除参数，避免构造不安全或带 token 的下一跳请求。
- 完全相同的目标可去重；不同 ID、不同短链或 H5 与短链混合而不能本地证明一致时，
  返回歧义错误，不随机选取，也不联网逐个消歧。输入中的未知域名 URL 会使解析失败。
- 不记录原始输入、Location、分享 token 或异常原文；成功、业务错误和请求验证错误均不回显原始输入。

### 解析错误

解析错误仅包含 `code` 和 `message`，没有 `item_id`；原商品详情错误结构不变。

| HTTP 状态 | code                          | 含义                                           |
| --------- | ----------------------------- | ---------------------------------------------- |
| 422       | `INVALID_PRODUCT_INPUT`       | 请求结构、长度、ID、协议、域名、凭证或端口非法 |
| 422       | `PRODUCT_LINK_NOT_FOUND`      | 分享文字中未找到可识别链接                     |
| 422       | `NOT_PRODUCT_LINK`            | 官方链接或可信最终目标不是受支持的商品路径     |
| 422       | `AMBIGUOUS_PRODUCT_INPUT`     | 多个无法在本地确认一致的链接目标               |
| 504       | `SHORT_URL_TIMEOUT`           | 短链请求超时                                   |
| 502       | `SHORT_URL_CONNECTION_FAILED` | 短链连接、DNS 等传输失败                       |
| 503       | `SHORT_URL_ACCESS_RESTRICTED` | HTTP 401/403/429/461                           |
| 502       | `SHORT_URL_MISSING_LOCATION`  | 重定向没有有效 Location                        |
| 502       | `UNTRUSTED_REDIRECT_TARGET`   | 不可信或非法跳转地址                           |
| 502       | `SHORT_URL_REDIRECT_LIMIT`    | 重定向循环或超过预算                           |
| 502       | `SHORT_URL_HTML_UNSUPPORTED`  | HTTP 200，没有可直接解析的 HTTP 重定向         |
| 502       | `SHORT_URL_HTTP_ERROR`        | 其他上游 HTTP 状态                             |

第一版不解析 JavaScript、HTML 跳转或验证码，不使用浏览器和代理池。
自动化测试通过 MockTransport 覆盖跳转与异常，不依赖实时网络；真实短链是否仍有效需要单独验证。

## 本地批量采集与原始响应归档

无需启动 FastAPI。在 `collector` 目录执行：

```bash
uv run collect-examples --help
uv run collect-examples --dry-run
uv run collect-examples --limit 2
uv run collect-examples
uv run collect-examples --refresh
```

除 `--help` 和 `--dry-run` 外，这些命令会访问真实上游。建议先 dry-run，确认来源后再手动采集。
当前 `examples/share_text.md` 有 6 个 `text` 代码块、5 个不同来源，条目 #3、#4 重复。
普通说明段落被忽略；每个 text 代码块关联最近的数字标题，保留原始多行文案，仅在内存中做解析。
空块、缺少编号、未闭合代码块和非法输入会报告错误，输入文件不修改。

默认输入为 `collector/examples/share_text.md`，默认输出为 `collector/examples/`。
路径基于 Collector 项目目录计算，不依赖运行命令时的工作目录；相对参数路径也以 Collector 为基准。

```bash
uv run collect-examples --input examples/share_text.md --output /absolute/path/to/private-archives --interval 2
```

自定义输出目录必须为空或具有本工具创建的归档标记。建议将自定义归档放在仓库外；
默认 examples 生成路径已由 `.gitignore` 排除，自定义仓库内路径需要自行设置 Git 忽略规则。

| 参数                 | 说明                                                                          |
| -------------------- | ----------------------------------------------------------------------------- |
| `--dry-run`          | 不联网、不创建目录或文件、不恢复提交日志；统计来源、缓存和未知短链目标        |
| `--limit N`          | 按首次出现顺序选择前 N 个不同有效来源，不按原始条目数计数；无效条目单独报错   |
| `--refresh`          | 重新请求本批次选中商品的详情，已登记的短链来源映射继续复用                    |
| `--interval SECONDS` | 请求开始之间的最小间隔，默认 2 秒，可设为有限非负数；覆盖每一跳短链及详情请求 |
| `--input PATH`       | 指定含数字标题及 text fenced code block 的 UTF-8 Markdown                     |
| `--output PATH`      | 指定归档根目录                                                                |

### 输出结构与敏感数据

```text
examples/
├── README.md                     # 已有参考文件，不修改
├── share_text.md                 # 已有输入文件，不修改
├── response.json                 # 已有参考响应，不修改
├── manifest.json                 # 本工具维护的安全来源及快照索引
├── .pikdo-example-archive        # 输出目录归属标记
├── .collect-examples.lock        # 互斥运行锁
└── {24位小写商品ID}/
    ├── response.json             # 上游原始响应正文字节
    └── index.md                  # 商品摘要、来源和 SHA-256
```

新建商品目录使用 0700，生成文件使用 0600 权限（受操作系统支持限制）。
原始响应可能包含签名、分享追踪参数及其他敏感数据；只用于本地归档，不通过公开 API 暴露。
默认生成的商品目录、manifest 和内部状态文件被 Git 忽略，三个原始参考文件继续跟踪。
不要将原始响应直接复制到公开问题、日志或 PR 描述中。

### 字节级保全

客户端新增内部 `fetch_product_result()`，一次详情请求同时返回原始 bytes 和解析后的 Product。
`fetch_product()` 继续返回 Product，现有 API 行为和响应字段不变。

归档使用 **HTTPX `response.content`**：这是解压 Content-Encoding 后的响应正文，不包含 Headers 或 gzip 传输字节。
`response.json` 不经过 `json.dumps`、Pydantic 序列化、格式化、字段筛选或脱敏，不改变空白、换行、键顺序及数字文本。
数据直接写入同目录临时文件，flush/fsync、SHA-256 校验后用 `os.replace` 原子替换，再核验目标文件摘要。
只有清单等工具元数据使用 JSON 序列化。

HTTP 成功后仍需验证 JSON、业务 success、详情结构、标题及商品身份。
归档验证区分商品 itemId 与 SKU ID：两者可以不同；请求 ID 必须匹配明确的商品 ID 或一致的 SKU 身份。
缺失可验证身份、身份格式错误或 SKU 来源冲突均不归档；不能把另一个商品的响应存到当前目录。

`index.md` 从 Product 生成，包含店铺、到手价、展示价、销量原文、规范 H5 URL、全部成功来源编号、采集时间和原始响应摘要。
可选字段缺失显示“未提供”，不补造数值；上游文字经过 Markdown/HTML 转义和敏感信息过滤。
索引可重新生成，原始 response.json 永不从 Product 重建。

### 两级去重与缓存

1. 来源去重：复用 ProductResolver 的本地解析，按规范 URL 的 UTF-8 SHA-256 标识来源。
   短链保留 hostname 和区分大小写的 path，移除 query、fragment；纯 ID 和 H5 使用规范商品链接。
   同一来源只解析一次，保留所有编号及输入文件来源。
1. 商品去重：不同来源解析到同一 ID 后，每批只请求一次详情；失败的详情请求也不会在同批被其他来源重复触发。

`manifest.json` 使用 `schema_version=1`，包含：

- `sources`：以 source_hash 为键，保存 source_hash、source_url、item_id、source_type、source_types、source_file、entry_numbers、entry_sources。
- `products`：以商品 ID 为键，保存 fetched_at 和 response_sha256。

来源 URL 不含 token、签名或追踪参数，不保存原始分享文案或 HTTP 凭据。
编号按历史成功来源合并；`entry_sources` 分别保留不同输入文件的编号，不自动删除历史来源。

重复运行时已登记来源不再解析；响应摘要、JSON、业务及身份检查均通过后，跳过详情请求。
同一商品的新来源只更新来源索引和 index；新增商品才请求详情。
缓存的采集时间保持不变；缺失 index 可以从缓存 Product 重新生成。
HTTP 200 但业务失败、无效 JSON 或身份不匹配不会创建商品目录或成功清单记录。

缓存缺失或损坏时默认报 `CACHE_MISSING` / `CACHE_CORRUPT`，不会自动覆盖，需显式使用 `--refresh`。
manifest 损坏、重复键、未知版本或引用错误会在联网及写入前停止，绝不静默重建；请先备份并检查清单。
未知商品目录即使使用 refresh 也不自动接管，避免覆盖不相关文件。

### 刷新、恢复与失败处理

刷新时先完整下载并验证新数据，然后才替换旧响应；超时、HTTP、JSON、业务或身份失败都保留旧响应、索引和清单。
原始数据文件先提交，再更新成功 manifest。隐藏提交日志记录预期摘要和安全元数据，不记录响应正文或凭据。

出现进程中断或磁盘错误后，再次运行会自动检查 `.collect-examples.pending.json`：
数据已提交则补完 index 与 manifest；尚未完成 staging 且原文件未变化则放弃未完成提交，允许重新采集。
摘要或清单被外部修改时报告 `RECOVERY_CONFLICT`，不会猜测或覆盖。dry-run 仅报告 `RECOVERY_PENDING`，不执行恢复。
运行锁由操作系统在进程退出后释放；不要在另一个采集进程运行时删除锁文件。

一般条目失败后继续处理；401/403/429/461 或明确访问受限的业务错误会停止后续网络请求，不重试、不轮换身份。
输出只包含条目编号、计数和固定错误码，不打印原始文案、token、Location 或异常正文。
全部成功退出 0；存在条目失败或访问受限退出 1；参数不合法退出 2。

### 格式化与测试

`uv run lint` 和 `uv run format` 跳过默认 examples 中生成的商品目录、manifest 和内部状态文件；
带归档标记的自定义输出目录也使用相同保护。`format_json()` 对归档文件另设防御性跳过。
普通参考示例继续支持格式检查，但本次功能不改动三个原始参考文件。

```bash
uv run test tests/test_example_collector.py -v
```

归档测试只使用 tmp_path 和 MockTransport，不访问真实网络，包括原始字节一致性、gzip 解码、去重、增量、刷新、
错误与访问受限、中断恢复、路径保护及格式化后字节不变。开发验收不执行真实批量采集。

## 保留的 HTTPX 客户端访问受限保护

以下说明针对保留的 XhsClient（Resolver 与 collect-examples），不适用于已经迁移的 GET/collect。
同一旧客户端的商品详情和短链请求共用保护状态。上游 HTTP 401、403、429、461，
或商品响应中明确的安全验证、登录等业务拒绝，会立即阻断此客户端后续网络请求。
首次错误保留 `UPSTREAM_ACCESS_RESTRICTED` 或 `SHORT_URL_ACCESS_RESTRICTED`；
后续请求返回 HTTP 503、`UPSTREAM_REQUEST_BLOCKED`，详情错误继续包含 `item_id`。
CLI 遇到这些错误也会停止批次，不重试或探测恢复。

首次受限及后续阻断错误均携带诊断响应头：

- `X-Collector-Upstream-Status`：触发阻断的上游 HTTP 状态；业务受限可能是 `200`。
- `X-Collector-Blocked: true`：已启用本地阻断。

警告日志只记录接口种类、允许的固定域名、合法商品 ID、HTTP 状态及固定原因分类，
不记录原始响应、分享文案、追踪参数或凭据。请结合状态区分认证要求、拒绝访问、
限流、环境受限及业务验证，不能仅凭统一错误码确定具体风控原因。

阻断后 `/health`、`/docs`、纯 ID 和 H5 URL 的本地解析仍可使用。
先停止真实采集并人工检查上游访问条件；本功能不自动恢复，也没有重置接口。
重启服务（重新创建客户端）只清除本地阻断，不代表上游已经恢复。
多进程部署的状态彼此独立，其他进程不会共享本地阻断。

## 独立 browser-probe（沿用 Phase 1）

新增独立 `BrowserProductCollector` 与 `browser-probe`，监听页面正常导航发出的商品详情响应，不主动调用详情接口。
CLI 使用自己管理的临时 Browser，不借用生产管理器，不写正式商品归档；其单次采集核心与生产路径相同。

```bash
uv sync
uv run playwright install chromium
uv run browser-probe --help
uv run browser-probe --input "https://xhslink.com/m/4tQFfvENODH"
uv run browser-probe --input "https://xhslink.com/m/4tQFfvENODH" \
  --headed --channel chrome --output .browser-probe
```

支持分享文案、短链、官方 H5 商品链接和纯商品 ID；纯 ID 没有原始分享上下文。
默认导航 30 秒、商品响应 15 秒，`--timeout` 可分别覆盖两个阶段的上限。
只有成功通过 JSON、业务状态和商品身份验证后，可选输出目录才写入来自 `response.body()` 的原始 `response.json` 字节。
`.browser-probe/` 已被 Git 忽略；其他仓库内输出目录必须先加入忽略规则。禁止写入 examples 或正式归档。
CLI 输出安全状态和结果码，不打印分享 URL 或凭据。遇到登录、验证码和访问限制停止，不自动重试。

```bash
uv run test -v
# 可选：真实 Chromium，仅访问本机模拟站点
PIKDO_LOCAL_BROWSER_TESTS=1 uv run test tests/test_browser_local.py -v
```

离线模拟测试、本机浏览器验证与真实小红书采集是三种独立结果；不能互相替代。
安装、错误码、原始字节边界和真实手动验证方法见 [Playwright 命令与调试说明](../docs/2.playwright-commands.md)。
