import type { AxiosAdapter } from 'axios'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  ApiError,
  createApiClient,
  getBrowserApiClient,
} from '@/lib/api-client'
import { getCurrentUser } from './api'

vi.mock('@/lib/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api-client')>()),
  getBrowserApiClient: vi.fn(),
}))

beforeEach(() => vi.clearAllMocks())

it('reads the existing userId/status response and forwards cancellation', async () => {
  const user = { userId: 'test-user', status: 'ACTIVE' }
  const signal = new AbortController().signal
  const adapter: AxiosAdapter = async (config) => {
    expect(config.url).toBe('/v1/auth/me')
    expect(config.signal).toBe(signal)
    return {
      config,
      data: user,
      status: 200,
      statusText: '',
      headers: new AxiosHeaders(),
    }
  }
  vi.mocked(getBrowserApiClient).mockReturnValue(createApiClient({ adapter }))
  expect(await getCurrentUser(signal)).toEqual(user)
})

it.each([401, 503, 500])(
  'only interprets /me HTTP 401 as anonymous (%s)',
  async (status) => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('failed', 'ERR_BAD_RESPONSE', config, undefined, {
        config,
        data: { code: 'SERVER_CODE', message: '服务响应' },
        status,
        statusText: '',
        headers: new AxiosHeaders(),
      })
    }
    vi.mocked(getBrowserApiClient).mockReturnValue(createApiClient({ adapter }))
    if (status === 401) expect(await getCurrentUser()).toBeNull()
    else
      await expect(getCurrentUser()).rejects.toMatchObject({
        kind: 'http',
        status,
      })
  },
)

it.each(['ECONNABORTED', 'ERR_NETWORK'])(
  'does not classify %s as anonymous',
  async (code) => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('failed', code, config)
    }
    vi.mocked(getBrowserApiClient).mockReturnValue(createApiClient({ adapter }))
    await expect(getCurrentUser()).rejects.toBeInstanceOf(ApiError)
  },
)
