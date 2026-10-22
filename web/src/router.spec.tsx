// @vitest-environment jsdom

import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getCurrentUser } from '@/features/auth/api'
import { ApiError } from '@/lib/api-client'
import { getRouter } from './router'

vi.mock('@/routes/__root', async () => {
  const { createRootRoute } = await import('@tanstack/react-router')
  return { Route: createRootRoute() }
})
vi.mock('@/features/auth/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/auth/api')>()),
  getCurrentUser: vi.fn(),
}))

const routers: ReturnType<typeof getRouter>[] = []

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  routers.forEach((router) => router.options.context.queryClient.clear())
  routers.length = 0
  vi.restoreAllMocks()
  vi.resetAllMocks()
})

async function show(path = '/') {
  const router = getRouter()
  routers.push(router)
  router.update({
    context: router.options.context,
    history: createMemoryHistory({ initialEntries: [path] }),
    scrollRestoration: false,
  })
  await router.load()
  render(<RouterProvider router={router} />)
  return router
}

it('shows Pikdo with loading and anonymous states, without starter content', async () => {
  let finish: ((value: null) => void) | undefined
  vi.mocked(getCurrentUser).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await show()
  expect(screen.getByRole('heading', { name: 'Pikdo' })).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('正在确认登录状态')
  finish?.(null)
  expect(await screen.findByText('未登录')).toBeInTheDocument()
  expect(screen.queryByText('计数器')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '登录' })).toHaveAttribute(
    'href',
    '/login',
  )
  expect(screen.getByRole('link', { name: '注册' })).toHaveAttribute(
    'href',
    '/register',
  )
})

it('shows authenticated state', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue({
    userId: 'user',
    status: 'ACTIVE',
  })
  await show()
  expect(await screen.findByText('已登录')).toBeInTheDocument()
})

it('shows failure instead of anonymous and supports retry', async () => {
  vi.mocked(getCurrentUser)
    .mockRejectedValueOnce(new ApiError('http', '服务不可用', 503))
    .mockResolvedValue(null)
  await show()
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '暂时无法获取登录状态',
  )
  expect(screen.queryByText('未登录')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '重试' }))
  expect(await screen.findByText('未登录')).toBeInTheDocument()
  expect(getCurrentUser).toHaveBeenCalledTimes(2)
})

it.each(['/pending', '/streaming'])(
  'renders 404 for removed %s demo',
  async (path) => {
    await show(path)
    expect(
      await screen.findByRole('heading', { name: '404' }),
    ).toBeInTheDocument()
    expect(getCurrentUser).not.toHaveBeenCalled()
  },
)
