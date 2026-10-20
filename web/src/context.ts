import { QueryClient } from '@tanstack/react-query'

export function getContext() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        // 30 秒内复用新鲜数据，减少 SSR 水合后的重复请求。
        // 各功能模块可通过 queryOptions() 覆盖。
        staleTime: 30_000,
      },
    },
  })

  return {
    queryClient,
  }
}
