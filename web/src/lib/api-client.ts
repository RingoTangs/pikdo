import type { AxiosRequestConfig } from 'axios'
import axios from 'axios'

export type ApiErrorKind =
  'http' | 'network' | 'timeout' | 'canceled' | 'unexpected'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status?: number
  readonly code?: string

  constructor(
    kind: ApiErrorKind,
    message: string,
    status?: number,
    code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
    this.code = code
  }
}

export interface CsrfToken {
  headerName: string
  token: string
}

function normalizeError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (!axios.isAxiosError(error)) {
    return new ApiError('unexpected', '请求失败，请稍后重试')
  }
  if (axios.isCancel(error) || error.code === 'ERR_CANCELED')
    return new ApiError('canceled', '请求已取消')
  if (error.response) {
    const { status, data } = error.response
    if (
      data &&
      typeof data === 'object' &&
      typeof data.code === 'string' &&
      typeof data.message === 'string'
    )
      return new ApiError('http', data.message, status, data.code)
    return new ApiError(
      'http',
      status >= 500 ? '服务暂不可用，请稍后重试' : '请求失败，请稍后重试',
      status,
    )
  }
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
    return new ApiError('timeout', '请求超时，请稍后重试')
  }
  return new ApiError('network', '无法连接服务，请稍后重试')
}

export function createApiClient(
  options: Pick<AxiosRequestConfig, 'adapter' | 'timeout'> = {},
) {
  const http = axios.create({
    baseURL: '/api',
    timeout: 10_000,
    withCredentials: true,
    headers: { Accept: 'application/json' },
    ...options,
  })
  let csrf: CsrfToken | undefined
  let pending: Promise<CsrfToken> | undefined
  let generation = 0

  function clearCsrfToken() {
    generation++
    csrf = undefined
    pending = undefined
  }

  function getCsrfToken(): Promise<CsrfToken> {
    if (csrf) return Promise.resolve(csrf)
    if (pending) return pending
    const current = generation
    const request = http
      .get<CsrfToken>('/v1/auth/csrf')
      .then(({ data }) => {
        if (
          !data ||
          typeof data.headerName !== 'string' ||
          typeof data.token !== 'string' ||
          !/^[\w!#$%&'*+.^`|~-]+$/.test(data.headerName) ||
          !data.token ||
          /[\r\n]/.test(data.token)
        ) {
          throw new ApiError('unexpected', '无法获取请求校验信息，请稍后重试')
        }
        // 清理期间完成的旧请求不能恢复旧 Token，也不能用于后续写请求。
        if (current !== generation) return getCsrfToken()
        csrf = { headerName: data.headerName, token: data.token }
        return csrf
      })
      .finally(() => {
        if (pending === request) pending = undefined
      })
    pending = request
    return request
  }

  async function refreshCsrfToken() {
    clearCsrfToken()
    return getCsrfToken()
  }

  http.interceptors.request.use(async (config) => {
    const url = config.url ?? ''
    const resolved = new URL(`/api${url}`, 'http://api.internal')
    if (
      config.baseURL !== '/api' ||
      !url.startsWith('/') ||
      url.startsWith('//') ||
      resolved.origin !== 'http://api.internal' ||
      !resolved.pathname.startsWith('/api/')
    ) {
      throw new ApiError('unexpected', '仅支持同源 API 请求')
    }
    if (['post', 'put', 'patch', 'delete'].includes(config.method ?? 'get')) {
      const token = await getCsrfToken()
      config.headers.set(token.headerName, token.token)
    }
    return config
  })
  http.interceptors.response.use(
    (response) => {
      if (response.status === 204) response.data = undefined
      return response
    },
    (error: unknown) => Promise.reject(normalizeError(error)),
  )

  return { http, getCsrfToken, refreshCsrfToken, clearCsrfToken }
}

let browserClient: ReturnType<typeof createApiClient> | undefined

export function getBrowserApiClient() {
  if (typeof window === 'undefined') {
    throw new ApiError('unexpected', '认证 API 仅在浏览器加载')
  }
  browserClient ??= createApiClient()
  return browserClient
}
