import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient, getBrowserApiClient } from './api-client'

function response(
  config: InternalAxiosRequestConfig,
  data: unknown,
  status = 200,
) {
  return { config, data, status, statusText: '', headers: new AxiosHeaders() }
}

describe('aPI client', () => {
  it('uses JSON and same-origin credentials, normalizes 204 and deduplicates concurrent CSRF acquisition', async () => {
    const seen: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async (config) => {
      seen.push(config)
      return response(
        config,
        config.url === '/v1/auth/csrf'
          ? { headerName: 'X-CSRF-TOKEN', token: 'test-secret' }
          : '',
        config.url === '/v1/auth/csrf' ? 200 : 204,
      )
    }
    const client = createApiClient({ adapter })
    const results = await Promise.all([
      client.http.post('/test', { value: 1 }),
      client.http.post('/test', { value: 2 }),
    ])
    expect(results.map((result) => result.data)).toEqual([undefined, undefined])
    expect(
      seen.filter((config) => config.url === '/v1/auth/csrf'),
    ).toHaveLength(1)
    for (const config of seen.filter((config) => config.url === '/test')) {
      expect(config.baseURL).toBe('/api')
      expect(config.withCredentials).toBe(true)
      expect(config.headers.get('X-CSRF-TOKEN')).toBe('test-secret')
      expect(config.headers.get('Content-Type')).toBe('application/json')
      expect(JSON.parse(config.data)).toHaveProperty('value')
    }
    expect(seen[0]?.headers.has('X-CSRF-TOKEN')).toBe(false)
  })

  it('returns typed successful JSON without fetching CSRF for safe methods', async () => {
    const adapter = vi.fn<AxiosAdapter>(async (config) =>
      response(config, { value: 42 }),
    )
    const client = createApiClient({ adapter })
    expect((await client.http.get<{ value: number }>('/test')).data.value).toBe(
      42,
    )
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it.each([401, 403, 429, 503])(
    'preserves HTTP %s without exposing Axios config or replaying writes',
    async (status) => {
      const adapter: AxiosAdapter = async (config) => {
        if (config.url === '/v1/auth/csrf')
          return response(config, {
            headerName: 'X-CSRF-TOKEN',
            token: 'private-token',
          })
        throw new AxiosError(
          'private-request-detail',
          'ERR_BAD_RESPONSE',
          config,
          undefined,
          response(config, { code: 'TEST_ERROR', message: '公开错误' }, status),
        )
      }
      const client = createApiClient({ adapter })
      const error = await client.http
        .post('/test', { code: 'private-code' })
        .catch((error) => error)
      expect(error).toBeInstanceOf(ApiError)
      expect(error).toMatchObject({
        kind: 'http',
        status,
        code: 'TEST_ERROR',
        message: '公开错误',
      })
      expect(error).not.toHaveProperty('config')
      expect(error).not.toHaveProperty('cause')
      expect(JSON.stringify(error)).not.toMatch(
        /private-token|private-code|private-request-detail/,
      )
      expect(await client.getCsrfToken()).toEqual({
        headerName: 'X-CSRF-TOKEN',
        token: 'private-token',
      })
    },
  )

  it.each([
    ['ECONNABORTED', 'timeout'],
    ['ETIMEDOUT', 'timeout'],
    ['ERR_NETWORK', 'network'],
    ['ERR_CANCELED', 'canceled'],
  ])('converts %s to a safe %s error', async (code, kind) => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('private-detail', code, config)
    }
    const error = await createApiClient({ adapter })
      .http.get('/test')
      .catch((error) => error)
    expect(error).toMatchObject({ kind, status: undefined })
    expect(String(error)).not.toContain('private-detail')
  })

  it('uses a generic message for non-JSON server errors', async () => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError(
        'private-detail',
        'ERR_BAD_RESPONSE',
        config,
        undefined,
        response(config, '<html>private-detail</html>', 503),
      )
    }
    const error = await createApiClient({ adapter })
      .http.get('/test')
      .catch((error) => error)
    expect(error).toMatchObject({
      kind: 'http',
      status: 503,
      message: '服务暂不可用，请稍后重试',
    })
  })

  it('does not recurse or send the write when acquiring CSRF fails', async () => {
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      throw new AxiosError(
        'failed',
        'ERR_BAD_RESPONSE',
        config,
        undefined,
        response(config, {}, 503),
      )
    })
    await expect(
      createApiClient({ adapter }).http.post('/test'),
    ).rejects.toMatchObject({ status: 503 })
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(adapter.mock.calls[0]?.[0].url).toBe('/v1/auth/csrf')
  })

  it('refreshes explicitly and isolates CSRF caches between clients', async () => {
    let count = 0
    const adapter: AxiosAdapter = async (config) =>
      response(config, {
        headerName: 'X-CSRF-TOKEN',
        token: `token-${++count}`,
      })
    const a = createApiClient({ adapter })
    const b = createApiClient({ adapter })
    expect((await a.getCsrfToken()).token).toBe('token-1')
    expect((await b.getCsrfToken()).token).toBe('token-2')
    expect((await a.refreshCsrfToken()).token).toBe('token-3')
    expect((await b.getCsrfToken()).token).toBe('token-2')
    a.clearCsrfToken()
    expect((await a.getCsrfToken()).token).toBe('token-4')
  })

  it('cannot restore an old token when acquisition finishes after clearing', async () => {
    let release: (() => void) | undefined
    let count = 0
    const adapter: AxiosAdapter = async (config) => {
      count++
      if (count === 1) {
        await new Promise<void>((resolve) => {
          release = resolve
        })
        return response(config, {
          headerName: 'X-CSRF-TOKEN',
          token: 'old-token',
        })
      }
      return response(config, {
        headerName: 'X-CSRF-TOKEN',
        token: 'new-token',
      })
    }
    const client = createApiClient({ adapter })
    const old = client.getCsrfToken()
    await vi.waitFor(() => expect(release).toBeDefined())
    client.clearCsrfToken()
    expect((await client.getCsrfToken()).token).toBe('new-token')
    release?.()
    expect((await old).token).toBe('new-token')
    expect((await client.getCsrfToken()).token).toBe('new-token')
  })

  it.each([
    {},
    { headerName: 'bad\r\nheader', token: 'secret' },
    { headerName: 'X-CSRF-TOKEN', token: '' },
  ])('rejects malformed CSRF responses', async (data) => {
    const adapter: AxiosAdapter = async (config) => response(config, data)
    await expect(
      createApiClient({ adapter }).getCsrfToken(),
    ).rejects.toMatchObject({ kind: 'unexpected' })
  })

  it.each([
    'https://other.example/api/write',
    '//other.example/api/write',
    '/../outside',
  ])('never sends tokens or requests to %s', async (url) => {
    const adapter = vi.fn<AxiosAdapter>()
    await expect(
      createApiClient({ adapter }).http.post(url),
    ).rejects.toBeInstanceOf(ApiError)
    expect(adapter).not.toHaveBeenCalled()
  })

  it('refuses to create the browser singleton on the server', () => {
    expect(getBrowserApiClient).toThrow(ApiError)
  })
})
