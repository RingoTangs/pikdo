# Pikdo Server

Pikdo 面向商家和电商运营的数据采集与分析 SaaS 平台的 Java 后端。当前是模块化单体基础工程，提供 Web 基础设施，尚未实现 SaaS 业务。

## 技术栈与环境

- JDK 21，构建检查要求版本范围为 `[21,22)`。
- Spring Boot 4.1.1、Spring MVC、Jakarta Validation、Actuator。
- springdoc OpenAPI / Swagger UI，JSpecify 包级空值约定。
- Maven 3.9.x；推荐使用仓库内的 Maven Wrapper，当前固定为 3.9.15。
- 原生 MyBatis Starter 4.1.0、Spring JDBC/DataSource/声明式事务、HikariCP。
- PostgreSQL 18、Flyway；JDBC Driver 采用项目安全版本覆盖，Flyway 与 Testcontainers 版本由 Boot BOM 管理。
- JUnit、MockMvc、Testcontainers PostgreSQL 和 Spotless（Google Java Format AOSP 风格）。

Maven 坐标为 `io.github.ringotangs.pikdo:pikdo-server:0.0.1-SNAPSHOT`，启动类为 `io.github.ringotangs.pikdo.server.Application`。

## 开发与运行

在 `server/` 下执行，确保 `JAVA_HOME` 或 PATH 指向 JDK 21，并按下节准备本地私有数据库配置（也可使用环境变量）：

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

默认、dev、prod 均提供健康检查；Web 仅暴露 `health` 管理端点，组件和内部详情不公开。健康分组沿用 Boot 4.1.1 默认的 liveness/readiness。它们沿用 Boot 的健康分组策略；总健康状态包含数据库检查，仍不检查 Collector 或任何上游平台。连接或迁移失败会阻止真实应用启动，不回退到无数据库模式。

构建后可直接运行生产 Profile：

```bash
./mvnw clean verify
java -jar target/pikdo-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## PostgreSQL 开发环境

### 优先复用已有 PostgreSQL

已有本地 PG 无需启动 Compose，更不要重建或删除已有容器和 Volume。在启动应用前确认目标数据库，检查 `identity_*` 表和 `flyway_schema_history` 是否已经存在。V1 只适用于全新、空的目标 Schema；已有对象冲突需人工确认迁移方案，不能 clean、自动 baseline 或改历史来掩盖。

dev Profile 默认连接 `jdbc:postgresql://127.0.0.1:5432/pikdo`，用户名为 `pikdo`。密码保存在 `server/config/application-dev-local.yml`，该文件被 Git 忽略，不进入 JAR。仅 dev 通过相对于工作目录的路径加载它，因此启动时工作目录须为 `server/`。本机文件已按开发数据库配置创建；新环境需自行创建：

```yaml
spring:
  datasource:
    password: "填写本机开发数据库密码"
```

准备后即可运行上方 dev 启动命令，无需每次导出密码。首次启动会自动执行既有 Flyway 迁移，请先核对目标数据库；不要将开发配置用于生产。默认和 prod 不加载该私有文件。

标准环境变量优先于文件配置；连接其他数据库或在生产部署时，在启动 Java 的同一环境设置：

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/pikdo'
export SPRING_DATASOURCE_USERNAME='pikdo'
read -rs SPRING_DATASOURCE_PASSWORD
export SPRING_DATASOURCE_PASSWORD
```

`read` 时输入实际密码并回车，不把密码写入命令历史。URL 不含用户名或密码。生产通过部署环境或秘密管理注入这三个标准变量，不使用开发密码；跨网络连接按部署要求启用 TLS。连接池保留 Boot/Hikari 默认值，后续按负载实测调整；没有额外的大连接池设置。

应用不自动加载根目录 `.env.database`，也不设置可用于生产的默认数据库密码。默认/prod 未配置 URL、dev 缺少有效凭据、连接失败或 Flyway 校验/迁移失败时启动失败。不要开启记录凭据、完整认证请求或 SQL 参数的调试日志。

### 可选：从零启动本地 PostgreSQL

仓库根目录的 Compose 只用于没有现成 PG 的环境，不接管现有 `pikdo-postgres` 或 `pikdo_pgdata`。固定镜像 `postgres:18.6`，仅映射 `127.0.0.1`；项目范围 Named Volume 挂载 `/var/lib/postgresql`，保留 PostgreSQL 18 的版本化 PGDATA。

在仓库根目录执行：

```bash
cp .env.database.example .env.database
```

编辑本地 `.env.database`，为 `POSTGRES_PASSWORD` 填入自行生成的密码，例如使用 `openssl rand -hex 32`；实际文件已被 Git 忽略，示例没有真实密码。5432 已占用时修改 `POSTGRES_PORT`（例如 5433），不停止已有服务。然后执行：

```bash
docker compose --env-file .env.database config --quiet
docker compose --env-file .env.database up -d --wait
docker compose --env-file .env.database ps
docker compose --env-file .env.database exec postgres pg_isready -U pikdo -d pikdo
```

Java 使用相同数据库名、用户名、密码及主机端口；通过 dev 配置及本地私有文件提供，或在 Java 终端导出上述 `SPRING_DATASOURCE_*` 变量。修改 Compose 环境变量不会更新已初始化 Volume 中的数据库账号或密码，遇到不一致时先检查原初始化配置，不能通过删除 Volume 修复。

安全停止并保留数据：

```bash
docker compose --env-file .env.database stop
```

也可 `docker compose --env-file .env.database down` 移除该项目容器及网络，但保留 Named Volume；不要添加 `-v`，不要运行 volume prune。下一次启动复用同一项目 Volume。开发数据库账号不能作为生产管理员账号使用。

### Flyway 与表结构

`src/main/resources/db/migration/V1__create_identity_tables.sql` 管理三张表：User、有效邮箱 Account、密码凭据。应用启动自动迁移，已成功的 V1 不重复执行。后续修改使用新的版本化迁移，禁止改写已部署脚本；无 `schema.sql`、DDL Auto、自动 baseline 或 destructive clean。

可用数据库工具只读检查 `flyway_schema_history`，确认 version=1 且 success=true；集成测试同时执行 Flyway validate 和重复 migrate。时间使用 TIMESTAMPTZ，默认 CURRENT_TIMESTAMP；updated_at 的后续维护由写入方负责，不使用触发器。

邮箱表保存确切 original_address 和 C 比较规则的 canonical_address，用 ASCII translate CHECK 保证派生一致，限制非空可打印 ASCII，并分别唯一约束 user_id、canonical_address。完整邮箱语法仍由 EmailAddress 校验；数据库约束不证明邮箱控制权或注册成功。首版表仅保存有效关联，不定义解绑、历史或释放重用政策。

密码凭据引用 UserId，authentication_version 默认 0 且非负。password_input_version 必填且非空，仅保留未来输入处理策略标识；本阶段不批准 NFC、密码策略或具体版本名称。算法参数保留在未来编码哈希中，不重复存储，不实现密码验证。

## 基础设施与源码

- `Application.java`：启动入口，使用根包默认组件扫描。
- `bootstrap/`：应用启动日志。
- `identity/domain/`：身份领域对象；`identity/infrastructure/persistence/`：身份持久化实现。
- `shared/id/`：共享业务 ID 生成。
- `shared/web/request/`：Request ID Filter 及 Servlet 分派注册。
- `shared/web/error/`：公共错误定义、业务异常、固定错误响应、国际化消息解析及未知异常兜底。
- `src/main/resources/`：应用配置及中英文错误消息。
- `src/test/`：基础设施回归测试和显式导入的 Controller Fixture；Fixture 不进入生产 JAR，也不会随 dev Profile 自动加载。

错误响应保持 `{"code":"...","message":"..."}`。默认语言为英语，沿用请求 Locale 解析及中文消息支持。未知异常返回固定的 500 错误响应。

Request ID 由服务端生成，通过 `X-Request-ID` 返回；客户端同名请求头不被信任。Filter 为请求、ERROR 和 ASYNC 分派绑定同一 ID，并恢复进入前的 MDC。该机制不会自动把 MDC 传播到任意后台任务线程。

正式应用没有演示接口，也没有业务 Controller 或 Service。Swagger 测试中的示例操作仅来自测试 Fixture。

## Identity Persistence（I4-1B）

Server 按业务模块划分，模块内部按 DDD 分层；仅创建已有实现需要的层级。`identity/infrastructure/persistence/` 包含三个数据库 Row Record、三个原生 MyBatis Mapper，以及 User/Account 两个具体 Repository。Mapper 通过 `@Mapper` 自动发现，采用参数化 SQL 和显式构造映射；Row 使用 UUID、String、long、OffsetDateTime，不污染领域对象。

- UserMapper：创建、按 ID 查询、状态更新；UserRepository 通过既有工厂和 disable 正确还原 ACTIVE/DISABLED。
- AccountMapper：创建、按 ID/规范化邮箱查询；AccountRepository 从原始地址重建 EmailAddress 并核对派生键，不替换已保存的原始拼写。
- PasswordCredentialMapper：创建、按 UserId 查询、按预期认证版本更新；成功时 SQL 原子递增版本，返回 0 表示缺失或版本不匹配，不自动重试。初始版本及审计时间由数据库提供。

Repository 是普通 final 类，通过构造器接收 Mapper，本阶段不注册 Spring Bean，不建立无人使用的 Repository 接口。查询缺失返回 Optional.empty；非法持久化状态/邮箱拒绝还原，约束异常继续传播，写入及 User 更新检查影响行数。凭据仍为独立持久化记录，不加入 User/Account；其字符串表示不输出哈希。

调用方负责 Spring 事务边界，Repository 不手工提交，也不把基础读写当作完整注册或密码重置。只有创建/更新 SQL 能修改数据库，改变内存 User 状态不会自动保存。V1 和领域契约保持不变，后续应用层再按实际需要注册 Repository。

## 测试与格式化

```bash
./mvnw test
./mvnw spotless:check
./mvnw spotless:apply
./mvnw clean verify
```

`clean verify` 执行环境检查、普通测试、JAR 打包和 Spotless 检查，**不需要 Docker 或数据库服务**。完整 SpringBootTest 通过测试专用 WithoutDatabase 注解排除数据库自动配置，保留原默认/dev/prod Profile 和 Web 断言；正式应用没有该排除。测试覆盖异常状态与 JSON、参数校验、中英文消息、Request ID/MDC、测试组件隔离、Swagger 环境隔离和健康检查。

### PostgreSQL 集成测试

```bash
./mvnw -Pdb-integration clean verify
```

该命令先执行普通测试，再通过 Failsafe 运行 `*IT`，实际启动 Testcontainers 的 PostgreSQL 18.6。需要可访问的 Docker daemon 和镜像下载网络；Docker 不可用会失败，不使用 H2/Mock，也不跳过后报告通过。不要配置指向开发或生产数据库的测试连接。

测试容器的连接由 Spring `@ServiceConnection` 提供，随机密码和隔离数据库仅用于测试，生命周期随测试 ApplicationContext；不连接现有 PG、不使用本地 Named Volume。测试覆盖自动迁移与历史、类型/约束、ASCII 键、时间、MyBatis 探针参数绑定、并发冲突及 Spring 事务回滚。基础探针 Mapper 显式注册于 src/test，不进入生产 JAR；新的 IdentityPersistenceIT 使用独立容器上下文，验证正式 Mapper 自动发现、Row/领域还原、凭据条件更新与并发竞争、真实约束和跨 Mapper 回滚。测试中哈希只是占位数据，不代表实现了密码算法或真实注册。

常见问题：

- 普通测试失败：检查 Java 21、Maven 3.9.x、依赖下载和测试报告；不应要求启动 PG。
- 应用缺 URL、连接拒绝或认证失败：检查导出变量、主机端口、数据库名和原初始化密码；不打印密码诊断。
- Flyway 失败：核对目标 Schema、迁移历史与错误，不删除数据或修改已应用迁移。
- 集成测试找不到 Docker：启动 Docker Desktop 并检查当前 Docker context/权限；不能改为跳过测试。
- 镜像下载失败：检查 registry 网络；未执行的数据库测试应标为 BLOCKED/NOT RUN。

本阶段完成数据库基础及 Identity 基本持久化，不提供注册登录、邮件验证、密码重置、Spring Session JDBC、多租户、消息队列、任务调度、Collector HTTP Client 或商品业务接口。完成 I4-1B 后停止，不自动进入 I4-1C。
