import { QueryClientProvider } from '@tanstack/react-query'
import { renderToString } from 'react-dom/server'
import { expect, it, vi } from 'vitest'
import { getContext } from '@/context'
import { getCurrentUser } from './api'
import { useCurrentUser } from './queries'

vi.mock('./api', () => ({ getCurrentUser: vi.fn() }))

function Probe() {
  const query = useCurrentUser()
  return <span>{query.isPending ? 'pending' : 'loaded'}</span>
}

it('does not fetch authentication or touch browser APIs during SSR', () => {
  const context = getContext()
  const html = renderToString(
    <QueryClientProvider client={context.queryClient}>
      <Probe />
    </QueryClientProvider>,
  )
  expect(html).toContain('pending')
  expect(getCurrentUser).not.toHaveBeenCalled()
  expect(context.queryClient.getQueryData(['auth', 'me'])).toBeUndefined()
  context.queryClient.clear()
})

it('creates a fresh QueryClient for each SSR context', () => {
  const a = getContext()
  const b = getContext()
  a.queryClient.setQueryData(['auth', 'me'], {
    userId: 'user-a',
    status: 'ACTIVE',
  })
  expect(b.queryClient).not.toBe(a.queryClient)
  expect(b.queryClient.getQueryData(['auth', 'me'])).toBeUndefined()
  a.queryClient.clear()
  b.queryClient.clear()
})
