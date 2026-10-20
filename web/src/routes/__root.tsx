import type { QueryClient } from '@tanstack/react-query'
import { getThemeScript, ThemeProvider } from '@ringotangs/react-theme'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import {
  createRootRouteWithContext,
  HeadContent,
  Scripts,
} from '@tanstack/react-router'
import { TanStackRouterDevtools } from '@tanstack/react-router-devtools'
import appCss from '@/styles/index.css?url'

const themeScript = getThemeScript()

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()(
  {
    head: () => ({
      meta: [
        {
          charSet: 'utf-8',
        },
        {
          name: 'viewport',
          content: 'width=device-width, initial-scale=1',
        },
        {
          title: 'TanStack Start Starter',
        },
      ],
      links: [
        {
          rel: 'stylesheet',
          href: appCss,
        },
      ],
    }),
    shellComponent: RootDocument,
  },
)

function RootDocument({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        {/* eslint-disable-next-line react-dom/no-dangerously-set-innerhtml -- Static bootstrap script with safely serialized configuration. */}
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
        <HeadContent />
      </head>
      <body>
        <ThemeProvider>
          {children}
          <ReactQueryDevtools />
          <TanStackRouterDevtools />
        </ThemeProvider>
        <Scripts />
      </body>
    </html>
  )
}
