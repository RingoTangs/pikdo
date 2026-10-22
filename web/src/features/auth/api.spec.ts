import type { AxiosAdapter } from 'axios'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  ApiError,
  createApiClient,
  getBrowserApiClient,
} from '@/lib/api-client'
import { completeEmailVerification, getCurrentUser, sendEmailCode } from './api'

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

it.each([
  ['register', '/v1/auth/registration-codes', '/v1/auth/registrations'],
  ['login', '/v1/auth/login-codes', '/v1/auth/login'],
] as const)(
  'uses the existing %s endpoints and payloads with automatic CSRF',
  async (mode, sendPath, completePath) => {
    const issued = {
      challengeId: '0198a7b2-1234-7000-8000-000000000001',
      expiresAt: '2026-10-22T00:05:00Z',
      retryAfterSeconds: 60,
    }
    const seen: { path: string | undefined; body: unknown }[] = []
    const adapter: AxiosAdapter = async (config) => {
      if (config.url === '/v1/auth/csrf')
        return {
          config,
          data: { headerName: 'X-CSRF-TOKEN', token: 'test-token' },
          status: 200,
          statusText: '',
          headers: new AxiosHeaders(),
        }
      expect(config.headers.get('X-CSRF-TOKEN')).toBe('test-token')
      expect(config.method).toBe('post')
      seen.push({ path: config.url, body: JSON.parse(config.data) })
      return {
        config,
        data: config.url === sendPath ? issued : '',
        status: config.url === sendPath ? 202 : 204,
        statusText: '',
        headers: new AxiosHeaders(),
      }
    }
    vi.mocked(getBrowserApiClient).mockReturnValue(createApiClient({ adapter }))
    expect(await sendEmailCode(mode, 'Original@Example.com')).toEqual(issued)
    const verification = {
      challengeId: issued.challengeId,
      email: 'Original@Example.com',
      code: '000123',
    }
    expect(await completeEmailVerification(mode, verification)).toBeUndefined()
    expect(seen).toEqual([
      { path: sendPath, body: { email: 'Original@Example.com' } },
      { path: completePath, body: verification },
    ])
  },
)

it('does not accept malformed challenge metadata', async () => {
  const adapter: AxiosAdapter = async (config) => ({
    config,
    data:
      config.url === '/v1/auth/csrf'
        ? { headerName: 'X-CSRF-TOKEN', token: 'test' }
        : {
            challengeId: 'invalid',
            expiresAt: 'invalid',
            retryAfterSeconds: -1,
          },
    status: 200,
    statusText: '',
    headers: new AxiosHeaders(),
  })
  vi.mocked(getBrowserApiClient).mockReturnValue(createApiClient({ adapter }))
  await expect(
    sendEmailCode('register', 'a@example.com'),
  ).rejects.toMatchObject({ kind: 'unexpected' })
})
