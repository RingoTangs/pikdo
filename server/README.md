# Pikdo Server

Pikdo 面向商家和电商运营的数据采集与分析 SaaS 平台的 Java 后端。当前是模块化单体基础工程，提供 Web 基础设施，尚未实现 SaaS 业务。

## 技术栈与环境

- JDK 21，构建检查要求版本范围为 `[21,22)`。
- Spring Boot 4.1.1、Spring MVC、Jakarta Validation、Actuator。
- springdoc OpenAPI / Swagger UI，JSpecify 包级空值约定。
- Maven 3.9.x；推荐使用仓库内的 Maven Wrapper，当前固定为 3.9.15。
- JUnit、MockMvc 和 Spotless（Google Java Format AOSP 风格）。

Maven 坐标为 `io.github.ringotangs.pikdo:pikdo-server:0.0.1-SNAPSHOT`，启动类为 `io.github.ringotangs.pikdo.server.Application`。

## 开发与运行

在 `server/` 下执行，确保 `JAVA_HOME` 或 PATH 指向 JDK 21：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

默认端口为 `8080`。Windows 使用 `mvnw.cmd` 执行对应命令。

| Profile | OpenAPI / Swagger UI | 日志 |
| --- | --- | --- |
| 未指定 | 关闭 | 含 Request ID 的文本日志 |
| dev | 开启 | 含 Request ID 的文本日志 |
| prod | 关闭 | Logstash 格式结构化控制台日志 |

开发环境访问：

- Swagger UI：<http://localhost:8080/swagger-ui/index.html>，也可由 `/swagger-ui.html` 跳转。
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>。
- 健康检查：<http://localhost:8080/actuator/health>。

默认、dev、prod 均提供健康检查；Web 仅暴露 `health` 管理端点，组件和内部详情不公开。健康分组沿用 Boot 4.1.1 默认的 liveness/readiness。它们反映本应用状态，不检查 Collector 或任何上游平台。

构建后可直接运行生产 Profile：

```bash
./mvnw clean verify
java -jar target/pikdo-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## 基础设施与源码

- `Application.java`：启动入口，使用根包默认组件扫描。
- `config/`：Request ID Filter 及 Servlet 分派注册、未知异常兜底和启动日志。
- `exception/`：公共错误定义、业务异常、固定错误响应及国际化消息解析。
- `src/main/resources/`：应用配置及中英文错误消息。
- `src/test/`：基础设施回归测试和显式导入的 Controller Fixture；Fixture 不进入生产 JAR，也不会随 dev Profile 自动加载。

错误响应保持 `{"code":"...","message":"..."}`。默认语言为英语，沿用请求 Locale 解析及中文消息支持。未知异常返回固定的 500 错误响应。

Request ID 由服务端生成，通过 `X-Request-ID` 返回；客户端同名请求头不被信任。Filter 为请求、ERROR 和 ASYNC 分派绑定同一 ID，并恢复进入前的 MDC。该机制不会自动把 MDC 传播到任意后台任务线程。

正式应用没有演示接口，也没有业务 Controller、Service 或 Repository。Swagger 测试中的示例操作仅来自测试 Fixture。

## 测试与格式化

```bash
./mvnw test
./mvnw spotless:check
./mvnw spotless:apply
./mvnw clean verify
```

`clean verify` 执行环境检查、全部测试、JAR 打包和 Spotless 检查。测试覆盖异常状态与 JSON、参数校验、中英文消息、Request ID/MDC、测试组件隔离、Swagger 环境隔离和健康检查。

本阶段不提供登录认证、多租户上下文、数据库或持久化、消息队列、任务调度、Collector HTTP Client、商品采集业务接口及监控平台。这些能力留待具体业务阶段设计。
