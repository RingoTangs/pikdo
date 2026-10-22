import type { ProxyOptions } from 'vite'

export function createApiProxy(
  target = 'http://localhost:8080',
): Record<string, ProxyOptions> {
  const parsed = new URL(target)
  if (
    !['http:', 'https:'].includes(parsed.protocol) ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== '/' ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error(
      'API_PROXY_TARGET must be an HTTP(S) origin without credentials or a path',
    )
  }
  return {
    '/api': {
      target: parsed.origin,
      // Java 按实际 Host / Origin / 协议校验；保留浏览器源，不伪造转发头。
      changeOrigin: false,
      xfwd: false,
    },
  }
}
