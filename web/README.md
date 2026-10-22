# Pikdo Web

基于现有 TanStack Start、Router、Query、React 和主题系统的 Web 应用。W0 仅提供占位首页、CSRF 和当前用户查询；不实现注册、登录、退出或 Dashboard。

## 开发

使用 Node 22.23.2 和 pnpm 10.24.0：

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Web 为 `http://localhost:3000`，Java Server 应以 dev Profile 启动于 `http://localhost:8080`。复制 `.env.example` 到 `.env.local` 可通过 `API_PROXY_TARGET` 配置后端地址；该变量不使用 `VITE_` 前缀，不暴露给浏览器。目标必须为不带凭据、路径或查询参数的 HTTP(S) origin。

浏览器只访问同源 `/api/*`。Vite 保留路径、Host、Origin、Cookie 和 Set-Cookie，不启用 `changeOrigin` 或自动转发头；默认两端都是 HTTP，满足 Java 原有同源检查。若调整协议，前后端协议必须一致。Java dev Cookie 为非 Secure 的 `PIKDOSESSION`；生产默认 Secure Cookie 不适合 HTTP 开发。不要通过改写 Origin、关闭 CSRF 或放宽后端安全规则绕过检查。

生产部署必须由入口反向代理将同源 `/api/*` 转发给 Java Server，并按后端部署配置保持正确的 Host、Origin 和协议。Vite 开发代理不是生产代理；`pnpm preview` 不作为生产部署验收。

## 结构与认证状态

```text
src/
├── routes/              # Pikdo 首页与根文档
├── features/auth/       # DTO、认证 API、当前用户 Query
├── lib/api-client.ts    # Axios、错误转换、实例内 CSRF 缓存
├── lib/api-proxy.ts     # 仅供 Vite 配置与代理测试使用
├── components/          # 通用 Pending、404、错误页面
├── shadcn/
└── styles/
```

Axios 已在依赖及 lockfile 中。统一客户端使用 `/api`、同源 Cookie 和 10 秒超时；返回标准 Axios response，204 的 `data` 为 `undefined`。`ApiError` 仅保留 kind、status、code 和安全消息，不保留原始请求配置或响应。不会记录 Cookie、验证码或 Token，也不会按所有 401 自动退出或跳转。

CSRF 使用同一实例请求 `/v1/auth/csrf`，Token 仅在内存缓存。POST/PUT/PATCH/DELETE 自动等待并附加返回的 Header；获取请求自身是 GET，不会递归。并发获取合并，`clearCsrfToken()` 和 `refreshCsrfToken()` 支持登录态变化或失效后的显式清理和刷新，清理期间完成的旧请求不会恢复旧 Token。写请求不会自动重放，403 不被直接解释为 CSRF 过期；未来登录态变更还需同步清理当前用户 Query。

当前用户通过 `['auth', 'me']` Query 管理。仅 `/me` 的 401 转为 `null`；网络、超时、503 等保留为错误。查询传递 AbortSignal、关闭自动重试，提供手动重试。页面区分加载、已登录、未登录和失败，不显示内部用户 ID。

SSR 只渲染占位及加载状态，水合后才加载当前用户。SSR 不创建浏览器客户端、不请求认证 API、不转发 Cookie、不预取或序列化用户数据。Router 每次创建独立 QueryClient；如未来引入 SSR 认证，需要另行实现请求范围的客户端及必要凭据转发，不能复用浏览器单例。

## 验证

```bash
pnpm check
pnpm build
```

路由文件由 TanStack 插件在开发或构建时自动生成，不手改 `routeTree.gen.ts`。修改路由后先启动一次开发服务完成生成，再运行类型检查。

Vitest 覆盖 HTTP Client、CSRF、当前用户分类、SSR 隔离、首页状态及已移除路由；代理测试使用隔离的本地 HTTP 后端，不依赖开发数据库。真实 Java 联调需要本地 Server 已启动，并单独报告结果。
