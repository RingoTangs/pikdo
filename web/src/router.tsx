import { createRouter as createTanStackRouter } from '@tanstack/react-router'
import { setupRouterSsrQueryIntegration } from '@tanstack/react-router-ssr-query'
import { GeneralError } from '@/components/GeneralError'
import { NotFound } from '@/components/NotFound'
import { RouterPending } from '@/components/RouterPending'
import { getContext } from './context'
import { routeTree } from './routeTree.gen'

export function getRouter() {
  const context = getContext()

  const router = createTanStackRouter({
    routeTree,
    context,
    scrollRestoration: true,
    defaultPreload: 'intent',
    defaultPreloadStaleTime: 0,
    defaultPendingComponent: RouterPending,
    defaultPendingMs: 150,
    defaultPendingMinMs: 300,
    defaultNotFoundComponent: () => <NotFound />,
    defaultErrorComponent: () => <GeneralError />,
  })

  setupRouterSsrQueryIntegration({ router, queryClient: context.queryClient })

  return router
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof getRouter>
  }
}
