// @vitest-environment jsdom

import type { AuthMode, IssuedCode } from '../types'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/lib/api-client'
import { getRouter } from '@/router'
import {
  clearCsrfToken,
  completeEmailVerification,
  getCurrentUser,
  refreshCsrfToken,
  sendEmailCode,
} from '../api'

vi.mock('@/routes/__root', async () => {
  const { createRootRoute } = await import('@tanstack/react-router')
  return { Route: createRootRoute() }
})
vi.mock('../api', () => ({
  clearCsrfToken: vi.fn(),
  completeEmailVerification: vi.fn(),
  getCurrentUser: vi.fn(),
  refreshCsrfToken: vi.fn(),
  sendEmailCode: vi.fn(),
}))
const routers: ReturnType<typeof getRouter>[] = []
const id1 = '0198a7b2-1234-7000-8000-000000000001'
const id2 = '0198a7b2-1234-7000-8000-000000000002'
const email = 'Original@Example.com'

function issued(challengeId = id1, seconds = 300): IssuedCode {
  return {
    challengeId,
    expiresAt: new Date(Date.now() + seconds * 1000).toISOString(),
    retryAfterSeconds: 60,
  }
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  vi.mocked(sendEmailCode).mockImplementation(async () => issued())
  vi.mocked(completeEmailVerification).mockResolvedValue(undefined)
  vi.mocked(refreshCsrfToken).mockResolvedValue({
    headerName: 'X-CSRF-TOKEN',
    token: 'fresh-token',
  })
  vi.mocked(getCurrentUser).mockResolvedValue({
    userId: 'current-user',
    status: 'ACTIVE',
  })
})
afterEach(() => {
  cleanup()
  routers.forEach((router) => router.options.context.queryClient.clear())
  routers.length = 0
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.resetAllMocks()
})

async function show(mode: AuthMode = 'register') {
  const router = getRouter()
  routers.push(router)
  router.update({
    context: router.options.context,
    history: createMemoryHistory({ initialEntries: [`/${mode}`] }),
    scrollRestoration: false,
  })
  await router.load()
  const view = render(<RouterProvider router={router} />)
  return { router, view, queryClient: router.options.context.queryClient }
}

async function requestCode() {
  fireEvent.change(screen.getByLabelText('邮箱地址'), {
    target: { value: ` ${email} ` },
  })
  fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
  await screen.findByLabelText('六位验证码')
}
function enterCode(code = '000123') {
  fireEvent.change(screen.getByLabelText('六位验证码'), {
    target: { value: code },
  })
}

it.each(['register', 'login'] as const)(
  'completes %s and synchronizes CSRF, current user and router in order',
  async (mode) => {
    const { queryClient, router } = await show(mode)
    queryClient.setQueryData(['auth', 'me'], {
      userId: 'old-user',
      status: 'ACTIVE',
    })
    const cancel = vi.spyOn(queryClient, 'cancelQueries')
    const remove = vi.spyOn(queryClient, 'removeQueries')
    const invalidate = vi.spyOn(router, 'invalidate')
    const navigate = vi.spyOn(router, 'navigate')
    await requestCode()
    expect(sendEmailCode).toHaveBeenCalledWith(mode, email)
    expect(screen.getByText(/请求已受理/)).toBeInTheDocument()
    enterCode()
    fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
    expect(await screen.findByText('已登录')).toBeInTheDocument()
    expect(completeEmailVerification).toHaveBeenCalledExactlyOnceWith(mode, {
      challengeId: id1,
      email,
      code: '000123',
    })
    expect(vi.mocked(clearCsrfToken).mock.invocationCallOrder[0]).toBeLessThan(
      cancel.mock.invocationCallOrder[0]!,
    )
    expect(cancel.mock.invocationCallOrder[0]).toBeLessThan(
      remove.mock.invocationCallOrder[0]!,
    )
    expect(remove.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(refreshCsrfToken).mock.invocationCallOrder[0]!,
    )
    expect(
      vi.mocked(refreshCsrfToken).mock.invocationCallOrder[0],
    ).toBeLessThan(vi.mocked(getCurrentUser).mock.invocationCallOrder[0]!)
    expect(vi.mocked(getCurrentUser).mock.invocationCallOrder[0]).toBeLessThan(
      invalidate.mock.invocationCallOrder[0]!,
    )
    expect(navigate).toHaveBeenCalledWith({ to: '/', replace: true })
    await waitFor(() =>
      expect(queryClient.getMutationCache().getAll()).toHaveLength(0),
    )
  },
)

it('validates email and six digits and preserves leading zeroes', async () => {
  await show()
  fireEvent.change(screen.getByLabelText('邮箱地址'), {
    target: { value: 'not-email' },
  })
  fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '请输入有效的邮箱地址',
  )
  expect(sendEmailCode).not.toHaveBeenCalled()
  await requestCode()
  enterCode('a１２3!0001239')
  expect(screen.getByLabelText('六位验证码')).toHaveValue('300012')
  enterCode('12345')
  fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
  expect(screen.getByRole('alert')).toHaveTextContent('请输入六位数字验证码')
  expect(completeEmailVerification).not.toHaveBeenCalled()
})

it('uses service deadlines for resend and replaces the challenge and code', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
  await show()
  await requestCode()
  enterCode()
  expect(
    screen.getByRole('button', { name: '60 秒后可重新发送' }),
  ).toBeDisabled()
  vi.mocked(sendEmailCode).mockImplementationOnce(async () => issued(id2))
  act(() => vi.advanceTimersByTime(61_000))
  fireEvent.click(screen.getByRole('button', { name: '重新发送验证码' }))
  await waitFor(() => expect(sendEmailCode).toHaveBeenCalledTimes(2))
  await waitFor(() =>
    expect(screen.getByLabelText('六位验证码')).toHaveValue(''),
  )
  enterCode()
  fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
  await screen.findByText('已登录')
  expect(completeEmailVerification).toHaveBeenCalledWith('register', {
    challengeId: id2,
    email,
    code: '000123',
  })
})

it('keeps the original challenge when resending fails', async () => {
  vi.mocked(sendEmailCode).mockResolvedValueOnce({
    ...issued(),
    retryAfterSeconds: 0,
  })
  await show()
  await requestCode()
  vi.mocked(sendEmailCode).mockRejectedValueOnce(
    new ApiError('http', '服务暂不可用', 503),
  )
  fireEvent.click(screen.getByRole('button', { name: '重新发送验证码' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用')
  expect(sendEmailCode).toHaveBeenCalledTimes(2)
  enterCode()
  fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
  await screen.findByText('已登录')
  expect(completeEmailVerification).toHaveBeenCalledWith('register', {
    challengeId: id1,
    email,
    code: '000123',
  })
})

it('marks expired codes, disallows verification, and clears challenge on modifying email', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
  vi.mocked(sendEmailCode).mockResolvedValueOnce(issued(id1, 2))
  await show()
  await requestCode()
  act(() => vi.advanceTimersByTime(3000))
  expect(screen.getByText('验证码已过期，请重新获取。')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '验证并继续' })).toBeDisabled()
  expect(completeEmailVerification).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '修改邮箱 / 返回上一步' }))
  expect(screen.getByLabelText('邮箱地址')).toHaveValue(email)
  fireEvent.change(screen.getByLabelText('邮箱地址'), {
    target: { value: 'other@example.com' },
  })
  fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
  await screen.findByLabelText('六位验证码')
  expect(screen.getByLabelText('六位验证码')).toHaveValue('')
  expect(sendEmailCode).toHaveBeenLastCalledWith(
    'register',
    'other@example.com',
  )
})

it.each([400, 401, 403, 409, 429, 503])(
  'shows safe HTTP %s errors without automatic retries',
  async (status) => {
    vi.mocked(completeEmailVerification).mockRejectedValue(
      new ApiError('http', '服务器安全消息', status, 'TEST_ERROR'),
    )
    await show()
    await requestCode()
    enterCode()
    fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('服务器安全消息')
    expect(completeEmailVerification).toHaveBeenCalledTimes(1)
    expect(sendEmailCode).toHaveBeenCalledTimes(1)
    if (status === 403) {
      fireEvent.click(screen.getByRole('button', { name: '刷新请求校验信息' }))
      expect(
        await screen.findByText('请求校验信息已更新，请手动重试。'),
      ).toBeInTheDocument()
      expect(refreshCsrfToken).toHaveBeenCalledTimes(1)
      expect(completeEmailVerification).toHaveBeenCalledTimes(1)
    }
  },
)

it.each(['network', 'timeout'] as const)(
  'allows manual retry after %s without resending automatically',
  async (kind) => {
    vi.mocked(sendEmailCode).mockRejectedValueOnce(
      new ApiError(kind, '请求暂时失败'),
    )
    await show()
    fireEvent.change(screen.getByLabelText('邮箱地址'), {
      target: { value: email },
    })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('请求暂时失败')
    expect(sendEmailCode).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    await screen.findByLabelText('六位验证码')
    expect(sendEmailCode).toHaveBeenCalledTimes(2)
  },
)

it('prevents concurrent sends and submissions, including direct form events', async () => {
  let releaseSend: ((value: IssuedCode) => void) | undefined
  let releaseVerify: (() => void) | undefined
  vi.mocked(sendEmailCode).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        releaseSend = resolve
      }),
  )
  vi.mocked(completeEmailVerification).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        releaseVerify = resolve
      }),
  )
  const { queryClient } = await show()
  fireEvent.change(screen.getByLabelText('邮箱地址'), {
    target: { value: email },
  })
  const form = screen.getByLabelText('邮箱地址').closest('form')!
  fireEvent.submit(form)
  fireEvent.submit(form)
  await waitFor(() => expect(sendEmailCode).toHaveBeenCalledTimes(1))
  await act(async () => releaseSend?.(issued()))
  await screen.findByLabelText('六位验证码')
  enterCode()
  const verification = screen.getByLabelText('六位验证码').closest('form')!
  fireEvent.submit(verification)
  fireEvent.submit(verification)
  await waitFor(() =>
    expect(completeEmailVerification).toHaveBeenCalledTimes(1),
  )
  expect(
    screen.getByRole('button', { name: '修改邮箱 / 返回上一步' }),
  ).toBeDisabled()
  expect(
    queryClient
      .getMutationCache()
      .getAll()
      .some((mutation) =>
        JSON.stringify(mutation.state.variables)?.includes('000123'),
      ),
  ).toBe(false)
  await act(async () => releaseVerify?.())
  await screen.findByText('已登录')
})

it('only retries state synchronization after a completed verification', async () => {
  vi.mocked(refreshCsrfToken).mockRejectedValueOnce(
    new ApiError('network', '同步失败'),
  )
  await show()
  await requestCode()
  enterCode()
  fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '验证已完成，请重试状态同步',
  )
  expect(screen.queryByLabelText('六位验证码')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '重试状态同步' }))
  await screen.findByText('已登录')
  expect(completeEmailVerification).toHaveBeenCalledTimes(1)
  expect(sendEmailCode).toHaveBeenCalledTimes(1)
  expect(refreshCsrfToken).toHaveBeenCalledTimes(2)
})

it.each([null, new ApiError('http', '服务不可用', 503)])(
  'does not claim authenticated when current-user synchronization fails (%s)',
  async (result) => {
    if (result instanceof ApiError)
      vi.mocked(getCurrentUser).mockRejectedValueOnce(result)
    else vi.mocked(getCurrentUser).mockResolvedValueOnce(result)
    await show()
    await requestCode()
    enterCode()
    fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
    await screen.findByRole('alert')
    expect(screen.queryByText('已登录')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试状态同步' })).toBeEnabled()
    expect(completeEmailVerification).toHaveBeenCalledTimes(1)
  },
)

it('ignores late verification results after switching routes', async () => {
  let release: (() => void) | undefined
  vi.mocked(completeEmailVerification).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        release = resolve
      }),
  )
  const { router } = await show()
  await requestCode()
  enterCode()
  fireEvent.click(screen.getByRole('button', { name: '验证并继续' }))
  await waitFor(() => expect(release).toBeDefined())
  await act(async () => {
    await router.navigate({ to: '/login' })
  })
  await act(async () => release?.())
  expect(
    screen.getByRole('heading', { name: '欢迎回到 Pikdo' }),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('邮箱地址')).toHaveValue('')
  expect(refreshCsrfToken).not.toHaveBeenCalled()
})
