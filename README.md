# Pikdo

Pikdo 是面向商家和电商运营的多平台数据采集与分析 SaaS 平台，采用 Monorepo 管理两个独立服务及项目文档。

| 目录 | 职责 | 当前技术栈 |
| --- | --- | --- |
| [collector/](collector/README.md) | 单商品采集、输入校验、商品解析和安全输出 | Python 3.13、FastAPI、Playwright、Pydantic v2、uv |
| [server/](server/README.md) | SaaS Java 后端的模块化单体基础工程，提供可靠 Web 基础设施 | Java 21、Spring Boot 4.1.1、Maven 3.9.x |
| docs/ | 项目设计及参考文档 | 文档可能包含尚未实现的后续能力 |

Collector 默认监听 `127.0.0.1:8000`。Server 默认使用端口 `8080`。两个服务独立构建和运行；当前尚未实现 Java 与 Collector 的通信。

## 本地开发

Collector：

```bash
cd collector
uv sync
uv run playwright install chromium
uv run dev
```

Server（需要 JDK 21）：

```bash
cd server
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Server 的 Swagger 仅在 dev Profile 开启；标准健康检查为 `/actuator/health`。完整启动、配置和测试说明见各服务 README。

## 验证

```bash
# 在 collector/ 下执行
uv run lint
uv run test -v
uv build

# 在 server/ 下执行
./mvnw clean verify
```

Collector 的原始样例及标记保护的 Probe/历史数据保持只读。自动化采集回归使用模拟对象或 loopback 页面，不访问真实上游。

Server 当前不包含用户、租户、持久化、消息队列或采集任务业务；后续按实际需求扩展，不提前拆分微服务。
