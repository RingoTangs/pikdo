# Pikdo Collector v0.3.0

小红书商品详情采集 API，使用 Python 3.13、uv、FastAPI 和 HTTPX。

## 启动

```bash
cd collector
uv sync
uv run dev
```

监听 `127.0.0.1:8000`。交互文档：<http://localhost:8000/docs>。

```bash
curl http://localhost:8000/health
curl http://localhost:8000/api/v1/xhs/products/6ab10b35d528ea0015dd1b7e
```

`GET /health` 返回 `{"status":"ok"}`，不访问上游。
商品接口仅接受 24 位十六进制 ID，统一转小写，只访问固定上游接口，不接受任意 URL。

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

商品接口错误直接返回 `code`、`message`、`item_id`，没有 `detail` 外层：

```json
{"code":"INVALID_ITEM_ID","message":"商品 ID 必须为 24 位十六进制字符串","item_id":"invalid"}
```

| HTTP 状态 | code                         | 含义                                           |
| --------- | ---------------------------- | ---------------------------------------------- |
| 422       | `INVALID_ITEM_ID`            | 无效 ID，不请求上游                            |
| 504       | `UPSTREAM_TIMEOUT`           | 上游请求超时                                   |
| 503       | `UPSTREAM_ACCESS_RESTRICTED` | HTTP 401/403/429/461 或明确安全验证、登录消息  |
| 502       | `UPSTREAM_HTTP_ERROR`        | 其他非成功 HTTP 状态                           |
| 502       | `UPSTREAM_REQUEST_FAILED`    | DNS、连接等传输失败                            |
| 502       | `UPSTREAM_INVALID_JSON`      | 返回无效 JSON                                  |
| 502       | `UPSTREAM_INVALID_DATA`      | 商品结构或标题缺失                             |
| 502       | `UPSTREAM_BUSINESS_ERROR`    | 未知业务失败                                   |
| 404       | `PRODUCT_UNAVAILABLE`        | 明确不存在、下架、失效、违规状态或不可购买按钮 |

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
