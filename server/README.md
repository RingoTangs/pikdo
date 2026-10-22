# Pikdo Server

Pikdo 面向商家和电商运营的数据采集与分析 SaaS 平台的 Java 后端。当前采用模块化单体架构，提供 Web 基础设施、Identity 用户与账户管理，以及 Authentication 邮箱验证码注册／登录。

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

`src/main/resources/db/migration/` 保留原 V1、V2；V2 将邮箱绑定升级为 EMAIL/PHONE，V3 在原表上增加 USERNAME。新增 V4 管理 JDBC Session 表，V5 管理邮箱挑战及共享限流预算。空数据库依次执行 V1 → V2 → V3 → V4 → V5；已有 V1 的数据库通过 V2 保留 AccountId、UserId、原始邮箱、规范化键及时间。应用启动自动迁移，已成功的迁移不重复执行；无 `schema.sql`、DDL Auto、自动 baseline 或 destructive clean。V2 重命名列，与新程序配套发布，不支持旧程序同时读写新 Schema。本次改造不执行开发数据库迁移。

可用数据库工具只读检查 `flyway_schema_history`，确认 version=1、2、3 均 success=true；集成测试同时执行 Flyway validate 和重复 migrate。时间使用 TIMESTAMPTZ，默认 CURRENT_TIMESTAMP；updated_at 的后续维护由写入方负责，不使用触发器。

Account 表保存 identity_type、original_identifier 和采用 C 比较规则的 canonical_identifier；类型为 EMAIL、PHONE 或 USERNAME。唯一约束 `(user_id, identity_type)` 保证每个 User 最多一个有效用户名、一个有效邮箱和一个有效手机号，`(identity_type, canonical_identifier)` 保证规范化身份不能跨 User 重复。EMAIL 继续用 ASCII translate CHECK 保证派生一致，完整邮箱语法仍由 EmailAddress 校验。PHONE 要求原始值与规范值相等，完整匹配 `\+[1-9][0-9]{1,14}`；仅接受已规范化的国际号码，不清理分隔符、不猜测国家区号，不判断号码实际分配或控制权。USERNAME 要求 3–32 个 ASCII 字符、英文字母开头，其余仅允许字母、数字、下划线；完整匹配 `[A-Za-z][A-Za-z0-9_]{2,31}`，保留原始拼写，以 ASCII 小写作为身份键，不 trim。大小写不敏感地精确禁止 `admin`、`system`、`support`，不禁止 `admin123` 等其他合法名称。V3 只扩展类型与用户名 CHECK，保留原有唯一约束、外键及 EMAIL/PHONE 数据。表仅保存有效关联，不定义解绑、历史或释放重用政策。

用户名、邮箱和手机号绑定引用同一 UserId，共享以该 UserId 为主键的一条密码凭据；Account 不保存密码或验证码。密码凭据引用 UserId，authentication_version 默认 0 且非负。password_input_version 必填且非空，仅保留未来输入处理策略标识；本阶段不批准 NFC、密码策略或具体版本名称。算法参数保留在未来编码哈希中，不重复存储，不实现密码验证。

## 基础设施与源码

- `Application.java`：启动入口，使用根包默认组件扫描。
- `bootstrap/`：应用启动日志。
- `identity/domain/`、`identity/persistence/`：用户、登录身份及密码凭据的领域模型与持久化。
- `authentication/domain/`、`authentication/persistence/`：验证码挑战模型、Repository，以及验证码和限流 Mapper。
- `authentication/application/`：认证事务、限流与摘要计算；`authentication/web/`（含 `dto/`）：认证 API 与 Swagger。
- `authentication/security/`：Security、CSRF、Session 与历史序列化兼容；`authentication/mail/`：异步邮件投递。
- 模块依赖为 `Authentication → Identity`，Identity 不引用 Authentication，认证代码通过 Identity 领域类型和 Repository 使用用户及账户。
- `shared/id/`：共享业务 ID 生成。
- `shared/web/request/`：Request ID Filter 及 Servlet 分派注册。
- `shared/web/error/`：公共错误定义、业务异常、固定错误响应、国际化消息解析及未知异常兜底。
- `src/main/resources/`：应用配置及中英文错误消息。
- `src/test/`：基础设施回归测试和显式导入的 Controller Fixture；Fixture 不进入生产 JAR，也不会随 dev Profile 自动加载。

错误响应保持 `{"code":"...","message":"..."}`。默认语言为英语，沿用请求 Locale 解析及中文消息支持。未知异常返回固定的 500 错误响应。

Request ID 由服务端生成，通过 `X-Request-ID` 返回；客户端同名请求头不被信任。Filter 为请求、ERROR 和 ASYNC 分派绑定同一 ID，并恢复进入前的 MDC。该机制不会自动把 MDC 传播到任意后台任务线程。

正式应用提供邮箱认证 Controller 和 Service，没有演示接口。Swagger 测试中的额外示例操作仅来自测试 Fixture。

## Identity Persistence（I4-1B）

Server 按业务模块划分，模块内部按 DDD 分层；仅创建已有实现需要的层级。`identity/persistence/` 包含用户、账户和密码凭据的 Row、MyBatis Mapper，以及 UserRepositoryImpl、AccountRepositoryImpl、PasswordCredentialRepositoryImpl。对应的领域 Repository 接口位于 `identity/domain/`；验证码和限流持久化位于 `authentication/persistence/`，VerificationChallengeRepository 契约位于 `authentication/domain/`。两个模块的领域契约仅依赖领域类型与 JDK，不依赖 Spring、MyBatis 或持久化实现。Mapper 通过 `@Mapper` 自动发现，采用参数化 SQL 和显式构造映射；Row 使用 UUID、String、long、OffsetDateTime，不污染领域对象。

- UserMapper：创建、按 ID 查询、状态更新；UserRepositoryImpl 通过既有工厂和 disable 正确还原 ACTIVE/DISABLED。
- Account：`AccountId + UserId + LoginIdentifier`。sealed LoginIdentifier 包含 UsernameIdentifier、EmailIdentifier、PhoneIdentifier，分别包装 Username、EmailAddress、PhoneNumber；User 的现有语义不变。
- AccountMapper：创建、按 ID、按类型及规范化身份、按 UserId 查询；AccountRepository 提供 `insert`、`findById`、`findByIdentifier`、`findByUserId`，移除 `findByEmail`。User 查询返回 EMAIL、PHONE、USERNAME 顺序的列表，无绑定返回空列表。AccountRepositoryImpl 根据类型还原标识并核对派生键，未知类型或损坏记录拒绝还原，不替换原始邮箱拼写。
- PasswordCredential：独立不可变领域对象，包含 UserId、passwordHash、authenticationVersion、passwordInputVersion，不加入 User/Account。`create` 初始化认证版本为 0，`restore` 还原已有非负版本。字符串不可为 null 或纯空白，不修剪内容、不校验哈希算法；`toString()` 固定脱敏。该对象不证明输入已正确哈希，也不实现认证或 rehash。
- PasswordCredentialRepository：`insert(PasswordCredential)` 只接受版本 0；`findByUserId(UserId)` 返回 Optional；`updateIfVersionMatches(UserId, expectedVersion, passwordHash, passwordInputVersion)` 返回 boolean，true 表示成功，false 统一表示凭据不存在或版本不匹配，不额外查询、不自动重试。更新不改变原不可变对象，需重新查询新状态。
- PasswordCredentialRepositoryImpl 复用原 PasswordCredentialMapper 和 Row。Mapper 仍按 UserId 查询、以一条 SQL 比较预期版本、更新密码并递增版本；初始版本及审计时间由数据库提供。未修改 SQL、V1、V2 或事务边界。

RepositoryImpl 实现对应领域接口并通过构造器接收 Mapper。UserRepositoryImpl、AccountRepositoryImpl 使用受认证开关控制的 `@Repository`，保留非 final 以支持 Spring 默认类代理；PasswordCredentialRepositoryImpl 仍为普通 final 类，暂不参与认证装配。查询缺失返回 Optional.empty；非法持久化状态、登录身份或密码凭据拒绝还原，约束异常继续传播，写入及 User 更新检查影响行数。User、Account、PasswordCredential 是三个独立领域对象，通过 UserId 关联；凭据领域对象及 Row 的字符串表示均不输出哈希。

调用方负责 Spring 事务边界，RepositoryImpl 不手工提交，也不把基础读写当作完整注册或密码重置。只有创建/更新 SQL 能修改数据库，改变内存 User 状态不会自动保存。V1 原样保留，Account 通过 LoginIdentifier 支持用户名、邮箱和手机号身份，认证应用层负责注册／登录事务，Repository 不自行提交。

## 测试与格式化

```bash
./mvnw test
./mvnw spotless:check
./mvnw spotless:apply
./mvnw clean verify
```

`clean verify` 执行环境检查、普通测试、JAR 打包和 Spotless 检查，**不需要 Docker 或数据库服务**。完整 SpringBootTest 通过测试专用 WithoutDatabase 注解排除数据库自动配置，并用 Mockito 替换认证 Mapper、事务管理器、邮件发送器和限流器，提供测试专用 HMAC 密钥；真实 Controller、协调服务、Session 服务和安全过滤链仍装配。保留原默认/dev/prod Profile 和 Web 断言；正式应用没有这些排除或替身。测试覆盖异常状态与 JSON、参数校验、中英文消息、Request ID/MDC、测试组件隔离、Swagger 环境隔离和健康检查。

### PostgreSQL 集成测试

```bash
./mvnw -Pdb-integration clean verify
```

该命令先执行普通测试，再通过 Failsafe 运行 `*IT`，实际启动 Testcontainers 的 PostgreSQL 18.6。需要可访问的 Docker daemon 和镜像下载网络；Docker 不可用会失败，不使用 H2/Mock，也不跳过后报告通过。不要配置指向开发或生产数据库的测试连接。

测试容器的连接由 Spring `@ServiceConnection` 提供，随机密码和隔离数据库仅用于测试，生命周期随测试 ApplicationContext；不连接现有 PG、不使用本地 Named Volume。测试覆盖自动迁移与历史、类型/约束、ASCII 邮箱键、严格 E.164 号码、ASCII 用户名及保留名称、时间、MyBatis 探针参数绑定、并发冲突及 Spring 事务回滚。AccountMigrationIT 使用独立的一次性容器验证有历史记录的 V1 → V2 → V3 无损升级和空库全量迁移。基础探针 Mapper 显式注册于 src/test，不进入生产 JAR；新的 IdentityPersistenceIT 使用独立容器上下文，验证正式 Mapper 自动发现、Row/领域还原、凭据条件更新与并发竞争、真实约束和跨 Mapper 回滚。密码 Repository 测试另行验证领域还原、唯一凭据、多身份共享、条件更新、并发胜者和调用方事务回滚，并保留原 Mapper 测试。测试中哈希只是占位数据，不代表实现了密码算法或真实注册。

常见问题：

- 普通测试失败：检查 Java 21、Maven 3.9.x、依赖下载和测试报告；不应要求启动 PG。
- 应用缺 URL、连接拒绝或认证失败：检查导出变量、主机端口、数据库名和原初始化密码；不打印密码诊断。
- Flyway 失败：核对目标 Schema、迁移历史与错误，不删除数据或修改已应用迁移。
- 集成测试找不到 Docker：启动 Docker Desktop 并检查当前 Docker context/权限；不能改为跳过测试。
- 镜像下载失败：检查 registry 网络；未执行的数据库测试应标为 BLOCKED/NOT RUN。

当前仅实现邮箱验证码注册／登录，不实现用户名设置、密码设置、手机号绑定、密码重置、Web 页面或 Collector 集成。


## 邮箱验证码认证

`VerificationChallenge` 管理用途／邮箱／浏览器绑定、有效期、错误次数及消费和失效状态；只保存摘要并使用常量时间比较。`VerificationCodes` 负责随机数与 HMAC，Application Service 负责身份规则和事务，Repository 负责领域对象与 Row 转换。校验失败的错误次数先提交再返回认证失败；注册创建 User、Account 与条件消费挑战保持同一事务，行锁和数据库条件更新共同保证一次性消费。

所有接口位于 `/api/v1/auth`，响应禁止缓存，错误沿用 `{code,message}`。先 GET `/csrf`，保存 Cookie，再将响应的 `headerName` 和 `token` 用作后续 POST 的请求头。认证成功后 CSRF Token 失效，应重新获取。不要在日志中记录请求体、验证码、邮件正文或 SMTP 凭据，也不要打开邮件或 SQL 参数调试日志。

| 方法与路径 | 请求／响应 |
| --- | --- |
| GET `/csrf` | `{headerName,token}` |
| POST `/registration-codes` | `{email}` → 202 `{challengeId,expiresAt,retryAfterSeconds}` |
| POST `/registrations` | `{challengeId,email,code}` → 204，自动登录 |
| POST `/login-codes` | `{email}` → 同样的 202 响应 |
| POST `/login` | `{challengeId,email,code}` → 204 |
| GET `/me` | `{userId,status}`；匿名为 401 |
| POST `/logout` | 204，销毁 Session；需要 CSRF |

注册只创建 User 和 EMAIL Account，不创建 PasswordCredential。邮箱规范化复用 EmailAddress；登录邮件始终投递到已有 Account 保存的原始地址。注册、登录不自动合并用户；已登录时再次进入注册／登录返回 409。用途、邮箱和浏览器 Session 共同绑定挑战；验证码为六位数字，五分钟有效，成功后只能消费一次，最多五次错误尝试。未知、已注册或禁用等不适用的邮箱仍返回同形 202，不发送邮件。邮件在挑战事务提交后异步调度；不符合发送条件的请求也进入同一队列，由后台跳过。SMTP 投递失败或队列满时保持相同的通用响应并使挑战失效，客户端可在冷却后重新请求。

注册数据及挑战消费在同一 PostgreSQL 事务中提交；随后轮换 Session ID、清理流程秘密并持久化认证上下文。Session 写入失败返回 503；已提交的用户不会回滚，此时应重新请求登录验证码。Session 通过 UserId 标识主体，空闲 30 分钟、绝对 12 小时过期；每次受保护请求检查用户启用状态。

默认和生产 Cookie 为 `__Host-PIKDOSESSION`，Secure、HttpOnly、SameSite=Lax、Path=/，要求 HTTPS；dev 使用本机 HTTP 的 `PIKDOSESSION`。仅支持同源请求，不开放跨域认证。默认按直接连接的来源 IP 限流；仅明确配置的可信代理可以提供 X-Forwarded-For，规则见下节。

启动前配置以下环境变量：

- `PIKDO_AUTH_HMAC_KEY`：至少 32 字节随机密钥的 Base64；所有实例共用，持久保存，禁止提交。更换后未完成挑战失效。
- `PIKDO_AUTH_SMTP_HOST`、`PIKDO_AUTH_SMTP_PORT`（默认 587）、`PIKDO_AUTH_MAIL_FROM`。
- `PIKDO_AUTH_SMTP_USERNAME`、`PIKDO_AUTH_SMTP_PASSWORD`：SMTP 认证需要时设置。

生产 SMTP 强制 STARTTLS，dev 只允许 localhost／回环地址，默认端口 1025，须自行准备安全的本地邮件接收器。测试使用仅存在于 src/test 的内存发送器，不连接外部邮件服务。密钥无默认值；配置缺失时启动失败，不以明文或日志验证码降级。

`pikdo.auth` 可配置 resend-interval（60s）、email-hourly-limit（5）、max-attempts（5）、send-ip-limit（10/分钟）、verify-ip-limit（30/分钟）、csrf-ip-limit（60/分钟）、absolute-session-lifetime（12h）。注册与登录共用邮箱发送预算，预算保存在 PostgreSQL 并发原子更新。错误验证提交失败次数；不会因抛出业务失败而回滚预算。每小时清理已过期预算及过期超过 24 小时的挑战；JDBC Session 使用 Spring Session 清理任务。

`EmailAuthenticationIT` 使用 PostgreSQL Testcontainers 验证真实 Mapper、事务、并发冲突、Session Cookie、CSRF 和退出登录。不会访问开发数据库；V1–V3 保持原样。认证始终启用；无数据库 Web 测试仅替换基础设施和限流组件，真实业务、限流与事务由 PostgreSQL 集成测试验证。


### 异步邮件与有界队列

`pikdo.auth.mail-workers` 默认 2，`pikdo.auth.mail-queue-capacity` 默认 100，均须为正数。邮件只在数据库事务提交后排队；HTTP 不等待 SMTP，队列满也不会在请求线程发送。后台发送前再次检查挑战的有效状态；有效期仍从创建时计算，排队不会延长五分钟期限。投递失败、队列拒绝和失效写入失败均只记录固定告警，不附带邮箱、验证码或异常堆栈。

队列只在本进程内存保存，不持久化、不自动重试。关闭时停止接收并取消排队任务，最多等待工作线程退出 10 秒；进程退出可能丢失任务。失效写入失败时仍由原有效期和一次性消费约束兜底，未发送的随机验证码不对外公开。SMTP 耗时已移出 HTTP 路径，但这不保证严格恒时，也不消除所有数据库或负载侧信道。

### 可信代理和真实客户端 IP

配置示例（只填实际直接或中间代理的地址，不要直接复制宽泛网段）：

```yaml
pikdo:
  auth:
    trusted-proxies:
      - "127.0.0.1/32"
      - "::1/128"
```

默认为空列表，此时所有 XFF 都被忽略。只有直接连接的来源可信时才解析 XFF；从右向左跳过连续的可信代理，取第一个不可信地址为客户端。若整条链都可信则取最左地址。支持 IPv4、IPv6、CIDR 及 IPv4 映射 IPv6 的统一计数；只接受 IP 字面量，不执行 DNS 查询。非法可信配置会阻止启动。

XFF 支持重复头按顺序合并；最多 4096 字符、32 个地址。缺失、空项、非法地址、主机名、端口或 IPv6 zone ID 均整体回退直接连接地址。忽略 Forwarded、X-Real-IP，不按它们补充或覆盖结果。保持 `server.forward-headers-strategy=none`，不要另外注册 ForwardedHeaderFilter 或 Tomcat RemoteIpValve 改写直接连接来源，否则可信边界会丢失。

公网入口 Nginx 必须覆盖客户端自带的 XFF：

```nginx
location / {
    proxy_pass https://127.0.0.1:8443;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Forwarded "";
    proxy_set_header X-Real-IP "";
    proxy_set_header X-Forwarded-Proto "";
    proxy_ssl_server_name on;
    proxy_ssl_name api.pikdo.example;
    proxy_ssl_verify on;
    proxy_ssl_trusted_certificate /etc/nginx/backend-ca.pem;
}
```

该生产示例要求外部 Nginx 和 Spring Boot 后端均启用 HTTPS，后端证书须匹配配置的 `proxy_ssl_name`，按实际域名和 CA 文件替换示例值。这样保留现有同源 Origin 校验，无需信任客户端 X-Forwarded-Proto。纯 HTTP 的 dev 环境可以将 upstream 改为 `http://127.0.0.1:8080`；不要直接将生产 TLS 终止后转 HTTP 的部署套用此示例，否则现有同源检查可能拒绝请求。

多级代理时，公网第一跳仍覆盖 XFF；后续受控代理使用 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` 逐跳追加，并保留 Host 和一致的 HTTPS Scheme。将实际链路中的代理 IP/CIDR 加入可信列表，以防火墙或监听地址限制后端只能由这些代理访问；不要信任任意公网来源、整个客户端网段或默认所有私网。若 Nginx 自身启用了 real_ip 模块，必须保证它同样只信任明确的上游代理，不能让公网输入直接改写 `$remote_addr`。


### 认证代码职责

| 层与组件 | 职责 |
| --- | --- |
| EmailAuthenticationController | 路由、`@Valid` 参数校验、状态码和 Web Service 委托 |
| authentication/web/dto | 独立请求／响应 record，验证请求保留脱敏 toString |
| EmailAuthenticationWebService | 匿名检查、流程协调、邮箱转换、可信客户端 IP 获取和响应映射 |
| AuthenticationSessionService | Session 流程秘密、CSRF Token、主体读取、Session ID 轮换与 SecurityContext 保存／失败清理 |
| EmailAuthenticationService | 注册／登录核心事务，不依赖 Servlet 或 Web DTO |
| AuthenticationRateLimiter、AsyncEmailDelivery | 现有共享限流与事务提交后异步邮件投递 |

普通 Service／组件与 User、Account Repository 通过构造器注入和组件扫描装配，AuthenticationConfiguration 仅保留 Cookie、可替换 SMTP 实现及邮件线程池工厂。认证组件始终注册，不使用功能开关或额外 Profile 禁用生产认证。

Web 协调和 Session 服务不添加外层事务。核心业务与限流保留原 TransactionTemplate 传播语义，异步挑战读取与失败失效保留 REQUIRES_NEW；错误尝试计数提交、业务回滚及提交后调度不受注解装配影响。Session 流程属性键保持原字符串，重构前已发送的挑战仍可在同一浏览器中完成验证。 `JdbcSessionConfiguration` 的专用 Spring Session 转换器只将历史 `identity.security.UserPrincipal`（版本 0，字段 userId/authenticatedAt）的序列化描述符映射到 `authentication.security.UserPrincipal`，保留用户、认证时间及权限；其他对象使用标准 Java 序列化，损坏或不兼容数据不静默降级。Cookie、Spring Security 属性键及数据库结构均不变。升级采用整批切换，新版本可读取旧 Session；新写入使用新类名，旧版本不能保证读取这些数据，因此不支持新旧版本混合运行或无条件回滚登录态。退出仍由原 Security Filter 处理，API 与公开响应不变。


### Swagger 认证文档

仅 dev 开放 Swagger UI 和 `/v3/api-docs`；默认及 prod 继续关闭。邮箱认证分组描述获取 CSRF Token、发送／验证注册与登录验证码、查询当前用户，并明确成功状态、统一错误响应及 POST 所需的 `X-CSRF-TOKEN`。

先通过 CSRF 接口获取 Token 并保留浏览器 Cookie；注册或登录成功、退出后重新获取 Token。当前用户接口使用 HttpOnly Cookie Session，不使用 Bearer/JWT。文档不提供验证码或 Session Cookie 的敏感示例。发送请求返回 202 不代表账号存在或邮件投递成功。

退出登录通过 OpenAPI 配置展示真实的 `POST /api/v1/auth/logout`，仍由 Security LogoutFilter 执行，不创建 MVC Controller 路由；需要有效 CSRF，无请求体，成功返回 204。无数据库 Swagger 测试用于文档和安全过滤链校验，实际注册登录须使用正常数据库及 SMTP 配置。
