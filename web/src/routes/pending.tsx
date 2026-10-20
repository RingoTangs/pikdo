import { createFileRoute, Link } from '@tanstack/react-router'

export const Route = createFileRoute('/pending')({
  head: () => ({ meta: [{ title: '全局 Pending 示例' }] }),
  // 离开后不缓存此示例，返回首页再进入时仍能观察加载过程。
  gcTime: 0,
  loader: async () => {
    // 等待关键数据后再返回，路由会使用 router.tsx 的默认 Pending 配置。
    // 与 streaming 示例不同，这里不会向组件返回未完成的 Promise。
    await new Promise<void>((resolve) => setTimeout(resolve, 2_000))
    return { message: '模拟任务已完成，页面数据已就绪。' }
  },
  component: PendingPage,
})

function PendingPage() {
  const { message } = Route.useLoaderData()

  return (
    <main className="bg-background text-foreground min-h-svh px-6 py-12">
      <div className="mx-auto max-w-2xl space-y-6">
        <h1 className="text-3xl font-bold tracking-tight">全局 Pending 示例</h1>
        <div className="bg-card text-card-foreground border-border rounded-xl border p-6">
          <h2 className="text-lg font-semibold">加载完成</h2>
          <p className="text-muted-foreground mt-2">{message}</p>
        </div>
        <div className="text-muted-foreground space-y-3 text-sm leading-relaxed">
          <p>
            从首页点击本示例时，loader 会等待约 2 秒。前约 150ms 保持原页面，
            随后由全局 RouterPending 显示 Loading...，数据就绪后展示此页。
          </p>
          <p>
            请返回首页后再次点击进入，重复观察效果。直接访问或刷新属于服务端渲染，
            不用于演示客户端导航时的 Pending。
          </p>
          <p>
            对比 Streaming SSR 示例：它先展示页面，再用 Await
            独立等待各区域数据； 此示例等待关键数据完成后才展示页面。
          </p>
        </div>
        <div className="flex flex-wrap gap-6 text-sm">
          <Link to="/" className="text-primary underline underline-offset-4">
            ← 返回首页
          </Link>
          <Link
            to="/streaming"
            preload={false}
            className="text-primary underline underline-offset-4"
          >
            对比 Streaming SSR 示例 →
          </Link>
        </div>
      </div>
    </main>
  )
}
