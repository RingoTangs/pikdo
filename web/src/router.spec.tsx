// @vitest-environment jsdom

import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { getRouter } from './router'

// Exercise the actual router defaults and page routes without the SSR document shell.
vi.mock('@/routes/__root', async () => {
  const { createRootRoute } = await import('@tanstack/react-router')
  return { Route: createRootRoute() }
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

it('客户端导航使用全局 Pending，完成后显示结果，离开后再次进入仍显示 Pending', async () => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  const router = getRouter()
  router.update({
    context: router.options.context,
    history: createMemoryHistory({ initialEntries: ['/'] }),
    scrollRestoration: false,
  })
  await router.load()
  render(<RouterProvider router={router} />)
  expect(
    screen.getByRole('heading', { name: 'Welcome to TanStack Start' }),
  ).toBeInTheDocument()

  vi.useFakeTimers()
  try {
    await act(async () => {
      fireEvent.click(
        screen.getByRole('link', { name: '查看全局 Pending 示例 →' }),
      )
    })
    await act(async () => vi.advanceTimersByTimeAsync(149))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Welcome to TanStack Start' }),
    ).toBeInTheDocument()

    await act(async () => vi.advanceTimersByTimeAsync(1))
    expect(screen.getByRole('status')).toHaveTextContent('Loading...')
    expect(
      screen.queryByRole('heading', { name: '加载完成' }),
    ).not.toBeInTheDocument()

    await act(async () => vi.advanceTimersByTimeAsync(2_000))
    expect(
      screen.getByRole('heading', { name: '加载完成' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    await act(async () => {
      fireEvent.click(screen.getByRole('link', { name: '← 返回首页' }))
    })
    expect(
      screen.getByRole('heading', { name: 'Welcome to TanStack Start' }),
    ).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(
        screen.getByRole('link', { name: '查看全局 Pending 示例 →' }),
      )
    })
    await act(async () => vi.advanceTimersByTimeAsync(150))
    expect(screen.getByRole('status')).toHaveTextContent('Loading...')
    await act(async () => vi.advanceTimersByTimeAsync(2_000))
    expect(
      screen.getByRole('heading', { name: '加载完成' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  } finally {
    router.options.context.queryClient.clear()
  }
})
