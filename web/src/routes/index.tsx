import { createFileRoute, Link } from '@tanstack/react-router'
import { useState } from 'react'
import { Button } from '@/components/Button'

export const Route = createFileRoute('/')({ component: Home })

function Home() {
  const [count, setCount] = useState(0)

  return (
    <main className="bg-background text-foreground flex min-h-svh items-center justify-center px-6 py-12">
      <div className="w-full max-w-xl space-y-8 text-center">
        <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">
          Welcome to TanStack Start
        </h1>

        <section
          aria-labelledby="counter-heading"
          className="bg-card text-card-foreground border-border rounded-2xl border p-8 shadow-sm"
        >
          <h2
            id="counter-heading"
            className="text-muted-foreground text-sm font-medium"
          >
            计数器
          </h2>
          <p
            aria-live="polite"
            aria-atomic="true"
            className="my-8 text-7xl font-semibold tabular-nums"
          >
            <span className="sr-only">当前计数：</span>
            {count}
          </p>
          <div className="flex flex-wrap justify-center gap-3">
            <Button
              variant="outline"
              size="lg"
              onClick={() => setCount((value) => value - 1)}
            >
              减一
            </Button>
            <Button variant="secondary" size="lg" onClick={() => setCount(0)}>
              重置
            </Button>
            <Button size="lg" onClick={() => setCount((value) => value + 1)}>
              加一
            </Button>
          </div>
        </section>

        <nav
          aria-label="页面示例"
          className="flex flex-col items-center gap-4 text-sm"
        >
          <Link
            to="/streaming"
            preload={false}
            className="text-primary underline underline-offset-4"
          >
            查看 Streaming SSR 示例 →
          </Link>
          <Link
            to="/pending"
            preload={false}
            className="text-primary underline underline-offset-4"
          >
            查看全局 Pending 示例 →
          </Link>
        </nav>
      </div>
    </main>
  )
}
