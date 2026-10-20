import { Await, createFileRoute, Link } from '@tanstack/react-router'

function resolveAfter<T>(ms: number, data: T): Promise<T> {
  return new Promise((resolve) => setTimeout(resolve, ms, data))
}

export const Route = createFileRoute('/streaming')({
  head: () => ({ meta: [{ title: 'Streaming SSR 示例' }] }),
  loader: () => {
    // 同时启动两个任务，保留未完成的 Promise，让页面外壳先返回。
    // 不要在这里 await 它们或使用 Promise.all，否则会阻塞整页。
    const overview = resolveAfter(1_200, [
      { label: '今日访问', value: '1,284' },
      { label: '新增用户', value: '36' },
      { label: '完成订单', value: '92' },
    ])
    const activity = resolveAfter(3_000, [
      {
        id: 'order',
        title: '订单 #1024 已完成',
        detail: '来自上海的用户完成了一笔订单。',
      },
      {
        id: 'member',
        title: '新成员加入工作区',
        detail: '团队迎来了今天的第 36 位新成员。',
      },
      {
        id: 'report',
        title: '日报已生成',
        detail: '今日访问和订单数据已完成汇总。',
      },
    ])

    return { overview, activity }
  },
  component: StreamingPage,
})

function LoadingSection({ label }: { label: string }) {
  return (
    <div role="status" className="min-h-48 space-y-4">
      <p className="text-muted-foreground text-sm">{label}</p>
      <div aria-hidden="true" className="space-y-4 motion-safe:animate-pulse">
        <div className="bg-muted h-6 w-3/4 rounded" />
        <div className="bg-muted h-6 w-full rounded" />
        <div className="bg-muted h-6 w-1/2 rounded" />
      </div>
    </div>
  )
}

function StreamingPage() {
  const { overview, activity } = Route.useLoaderData()

  return (
    <main className="bg-background text-foreground min-h-svh px-6 py-12">
      <div className="mx-auto max-w-5xl space-y-8">
        <header className="space-y-4">
          <Link
            to="/"
            className="text-muted-foreground text-sm underline underline-offset-4"
          >
            ← 返回首页
          </Link>
          <h1 className="text-3xl font-bold tracking-tight">
            Streaming SSR 示例
          </h1>
          <p className="text-muted-foreground max-w-3xl leading-relaxed">
            页面标题和占位先显示，统计概览约 1.2 秒后出现，最近动态约 3
            秒后出现。 两个任务同时开始，各自完成时独立显示内容。
          </p>
          <a
            href="/streaming"
            className="text-primary inline-block text-sm underline underline-offset-4"
          >
            完整刷新，重新观察流式响应
          </a>
        </header>

        <div className="grid items-start gap-6 md:grid-cols-2">
          <section
            aria-labelledby="overview-heading"
            className="bg-card text-card-foreground border-border rounded-xl border p-6"
          >
            <div className="mb-6 flex items-center justify-between gap-4">
              <h2 id="overview-heading" className="text-lg font-semibold">
                统计概览
              </h2>
              <span className="bg-muted text-muted-foreground rounded-full px-3 py-1 text-xs">
                约 1.2 秒
              </span>
            </div>
            <Await
              promise={overview}
              fallback={<LoadingSection label="正在加载统计概览…" />}
            >
              {(stats) => (
                <dl className="min-h-48 space-y-5">
                  {stats.map((stat) => (
                    <div
                      key={stat.label}
                      className="flex items-baseline justify-between gap-4"
                    >
                      <dt className="text-muted-foreground text-sm">
                        {stat.label}
                      </dt>
                      <dd className="text-2xl font-semibold tabular-nums">
                        {stat.value}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
            </Await>
          </section>

          <section
            aria-labelledby="activity-heading"
            className="bg-card text-card-foreground border-border rounded-xl border p-6"
          >
            <div className="mb-6 flex items-center justify-between gap-4">
              <h2 id="activity-heading" className="text-lg font-semibold">
                最近动态
              </h2>
              <span className="bg-muted text-muted-foreground rounded-full px-3 py-1 text-xs">
                约 3 秒
              </span>
            </div>
            <Await
              promise={activity}
              fallback={<LoadingSection label="正在加载最近动态…" />}
            >
              {(events) => (
                <ul className="min-h-48 space-y-5">
                  {events.map((event) => (
                    <li key={event.id} className="space-y-1">
                      <h3 className="text-sm font-medium">{event.title}</h3>
                      <p className="text-muted-foreground text-sm">
                        {event.detail}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </Await>
          </section>
        </div>

        <aside className="bg-muted text-muted-foreground rounded-xl p-6 text-sm leading-relaxed">
          <h2 className="text-foreground mb-2 font-semibold">如何观察</h2>
          <p>
            直接访问或完整刷新此页，可观察服务端 HTML 分块返回；从首页链接进入，
            则是客户端导航中的渐进加载。这里的延迟和数据均为本地模拟。
          </p>
          <p className="mt-2">
            局部占位由 Await 的 Suspense 边界控制，不需要等待全局 RouterPending
            显示。 在开发者工具的 Network 中查看本次文档请求，可观察响应持续约 3
            秒。
          </p>
        </aside>
      </div>
    </main>
  )
}
