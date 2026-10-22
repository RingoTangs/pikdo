import { createFileRoute } from '@tanstack/react-router'
import { useCurrentUser } from '@/features/auth/queries'
import { Button } from '@/shadcn/components/ui/button'

export const Route = createFileRoute('/')({ component: Home })

function Home() {
  const currentUser = useCurrentUser()

  return (
    <main className="bg-background text-foreground flex min-h-svh items-center justify-center px-6 py-12">
      <div className="w-full max-w-xl space-y-6 text-center">
        <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">Pikdo</h1>
        <p className="text-muted-foreground">
          数据采集与分析工作台，正在准备中。
        </p>
        <section aria-label="登录状态" aria-live="polite" className="space-y-3">
          {currentUser.isPending ? (
            <p role="status">正在确认登录状态…</p>
          ) : currentUser.isError ? (
            <>
              <p role="alert">暂时无法获取登录状态，请稍后重试。</p>
              <Button
                variant="outline"
                onClick={() => void currentUser.refetch()}
              >
                重试
              </Button>
            </>
          ) : (
            <p>{currentUser.data ? '已登录' : '未登录'}</p>
          )}
        </section>
      </div>
    </main>
  )
}
