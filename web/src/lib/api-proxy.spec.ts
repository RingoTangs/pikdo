import type { IncomingHttpHeaders } from 'node:http'
import type { AddressInfo } from 'node:net'
import { createServer as createHttpServer } from 'node:http'
import axios from 'axios'
import { createServer } from 'vite'
import { afterEach, expect, it, vi } from 'vitest'
import { createApiProxy } from './api-proxy'

const close: (() => Promise<void>)[] = []
afterEach(async () => {
  for (const cleanup of close.reverse()) await cleanup()
  close.length = 0
  vi.unstubAllEnvs()
})

it('preserves paths, browser Host/Origin and both Cookie directions over HTTP', async () => {
  const received: { url?: string; headers: IncomingHttpHeaders }[] = []
  const upstream = createHttpServer((request, response) => {
    received.push({ url: request.url, headers: request.headers })
    response.setHeader('Content-Type', 'application/json')
    response.setHeader(
      'Set-Cookie',
      'PIKDOSESSION=test-session; Path=/; HttpOnly; SameSite=Lax',
    )
    response.end(JSON.stringify({ ok: true }))
  })
  await new Promise<void>((resolve) => upstream.listen(0, '127.0.0.1', resolve))
  close.push(
    () =>
      new Promise<void>((resolve, reject) =>
        upstream.close((error) => (error ? reject(error) : resolve())),
      ),
  )
  const target = `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`
  vi.stubEnv('API_PROXY_TARGET', target)
  const vite = await createServer({
    logLevel: 'silent',
    appType: 'custom',
    server: {
      host: '127.0.0.1',
      port: 0,
      watch: null,
    },
  })
  close.push(() => vite.close())
  await vite.listen(0)
  const origin = `http://127.0.0.1:${(vite.httpServer?.address() as AddressInfo).port}`
  const response = await axios.post(
    `${origin}/api/v1/auth/test?value=1`,
    {},
    {
      proxy: false,
      headers: {
        Origin: origin,
        Cookie: 'PIKDOSESSION=test-session',
        'X-CSRF-TOKEN': 'test-token',
      },
    },
  )
  expect(response.data).toEqual({ ok: true })
  expect(response.headers['set-cookie']).toEqual([
    'PIKDOSESSION=test-session; Path=/; HttpOnly; SameSite=Lax',
  ])
  expect(received).toHaveLength(1)
  expect(received[0]?.url).toBe('/api/v1/auth/test?value=1')
  expect(received[0]?.headers.host).toBe(new URL(origin).host)
  expect(received[0]?.headers.origin).toBe(origin)
  expect(received[0]?.headers.cookie).toBe('PIKDOSESSION=test-session')
  expect(received[0]?.headers['x-csrf-token']).toBe('test-token')
  expect(received[0]?.headers['x-forwarded-proto']).toBeUndefined()
})

it.each([
  'file:///tmp/backend',
  'http://user:password@localhost:8080',
  'http://localhost:8080/api',
])('rejects invalid proxy origin %s', (target) => {
  expect(() => createApiProxy(target)).toThrow()
})
