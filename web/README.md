# Pikdo Web

基于现有 TanStack Start、Router、Query、React 和主题系统的 Web 应用。W0 提供基础设施，W1 提供邮箱验证码注册与登录。当前不实现退出、路由守卫或 Dashboard。

## 开发

使用 Node 22.23.2 和 pnpm 10.24.0：

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Web 为 `http://localhost:3000`，Java Server 应以 dev Profile 启动于 `http://localhost:8080`。复制 `.env.example` 到 `.env.local` 可通过 `API_PROXY_TARGET` 配置后端地址；该变量不使用 `VITE_` 前缀，不暴露给浏览器。目标必须为不带凭据、路径或查询参数的 HTTP(S) origin。

浏览器只访问同源 `/api/*`。Vite 和 Nitro 的开发代理共用同一目标，保留路径、Host、Origin、Cookie 和 Set-Cookie，不启用 `changeOrigin` 或自动转发头；默认两端都是 HTTP，满足 Java 原有同源检查。若调整协议，前后端协议必须一致。Java dev Cookie 为非 Secure 的 `PIKDOSESSION`；生产默认 Secure Cookie 不适合 HTTP 开发。不要通过改写 Origin、关闭 CSRF 或放宽后端安全规则绕过检查。

生产部署必须由入口反向代理将同源 `/api/*` 转发给 Java Server，并按后端部署配置保持正确的 Host、Origin 和协议。Nitro 开发入口会先于 Vite proxy 处理请求，因此同时配置 `/api/**` 的 `devProxy`，防止 API 落入页面 404；代理回归测试使用完整的实际开发配置。开发代理不是生产代理；`pnpm preview` 不作为生产部署验收。

## 结构与认证状态

```text
src/
├── routes/              # 首页、注册、登录与根文档
├── features/auth/       # DTO、Schema、API、Query、Mutation 与两步表单
├── lib/api-client.ts    # Axios、错误转换、实例内 CSRF 缓存
├── lib/api-proxy.ts     # 仅供 Vite 配置与代理测试使用
├── components/          # 通用 Pending、404、错误页面
├── shadcn/
└── styles/
```

Axios 已在依赖及 lockfile 中。统一客户端使用 `/api`、同源 Cookie 和 10 秒超时；返回标准 Axios response，204 的 `data` 为 `undefined`。`ApiError` 仅保留 kind、status、code 和安全消息，不保留原始请求配置或响应。不会记录 Cookie、验证码或 Token，也不会按所有 401 自动退出或跳转。

CSRF 使用同一实例请求 `/v1/auth/csrf`，Token 仅在内存缓存。POST/PUT/PATCH/DELETE 自动等待并附加返回的 Header；获取请求自身是 GET，不会递归。并发获取合并，`clearCsrfToken()` 和 `refreshCsrfToken()` 支持登录态变化或失效后的显式清理和刷新，清理期间完成的旧请求不会恢复旧 Token。写请求不会自动重放，403 不被直接解释为 CSRF 过期；注册和登录成功后会清理当前用户 Query，再刷新 CSRF 与用户数据。

当前用户通过 `['auth', 'me']` Query 管理。仅 `/me` 的 401 转为 `null`；网络、超时、503 等保留为错误。查询传递 AbortSignal、关闭自动重试，提供手动重试。页面区分加载、已登录、未登录和失败，不显示内部用户 ID。

SSR 只渲染占位及加载状态，水合后才加载当前用户。SSR 不创建浏览器客户端、不请求认证 API、不转发 Cookie、不预取或序列化用户数据。Router 每次创建独立 QueryClient；如未来引入 SSR 认证，需要另行实现请求范围的客户端及必要凭据转发，不能复用浏览器单例。

## 验证

```bash
pnpm check
pnpm build
```

路由文件由 TanStack 插件在开发或构建时自动生成，不手改 `routeTree.gen.ts`。修改路由后先启动一次开发服务完成生成，再运行类型检查。

Vitest 覆盖 HTTP Client、CSRF、当前用户分类、SSR 隔离、首页、两步认证、倒计时、并发提交、错误恢复及已移除路由；代理测试使用隔离的本地 HTTP 后端，不依赖开发数据库。真实 Java 联调需要本地 Server 已启动，并单独报告结果。

## 邮箱注册与登录（W1）

- `/register` 使用 `registration-codes`、`registrations`；`/login` 使用 `login-codes`、`login`，均位于 `/api/v1/auth/`。页面不直接调用 Axios，复用 W0 客户端与 CSRF 拦截器，所有写请求 Mutation 均不自动重试。
- 输入邮箱后使用 Zod 校验，去除首尾空白但保留大小写。202 只表示已受理，不承诺账号存在或邮件已送达。第二步只接受六位 ASCII 数字，保留前导零，支持粘贴与验证码自动填充。
- 重发截止时间由接收响应时刻加 `retryAfterSeconds` 得出，过期时间使用 `expiresAt`；页面按当前时间重算，避免标签页挂起导致倒计时累积误差。过期后禁用验证，重发成功替换挑战并清空验证码，修改邮箱会清除旧挑战。客户端时间仅用于交互，最终有效性由服务端判断。
- 发送、重发、验证及状态同步共用页面请求锁，防止重复点击或并发提交。验证码只保存在页面内存和临时请求中，不写入 localStorage、sessionStorage、URL 或 Mutation variables；Mutation 使用 `gcTime: 0` 并在流程结束、切页时 reset。
- 验证返回 204 后立刻清除验证码及挑战，清理 CSRF，取消并移除旧 `/me` 查询，再刷新 CSRF、强制获取 `/me`、更新 Router 并跳转首页。只有 `/me` 确认用户存在才显示已登录；同步失败进入独立恢复阶段，重试只执行状态同步，绝不重新提交验证码。
- HTTP 错误优先显示后端安全消息，按状态提供操作提示。403 可显式刷新请求校验信息后手动提交，网络或超时不代表服务端操作一定未发生；不会自动重发验证码或重放验证。409 可返回首页检查当前登录状态。
- 表单使用现有 shadcn Button、轻量 Input、Tailwind 主题变量，支持移动端、暗色模式及可访问错误提示。没有加入自动路由守卫；已登录用户直接访问认证页面时仍由后端约束并返回冲突。

真实注册与登录联调需要 Java Server、PostgreSQL 和可获取测试邮件的本地 SMTP 服务均可用。模拟 API 浏览器流程通过不能替代真实邮件与数据库联调。
