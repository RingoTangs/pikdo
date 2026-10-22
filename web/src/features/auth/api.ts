import type {
  AuthMode,
  CurrentUser,
  IssuedCode,
  VerificationRequest,
} from './types'
import { ApiError, getBrowserApiClient } from '@/lib/api-client'
import { issuedCodeSchema } from './schemas'

export function getCsrfToken() {
  return getBrowserApiClient().getCsrfToken()
}

export function refreshCsrfToken() {
  return getBrowserApiClient().refreshCsrfToken()
}

export function clearCsrfToken() {
  getBrowserApiClient().clearCsrfToken()
}

export async function getCurrentUser(
  signal?: AbortSignal,
): Promise<CurrentUser | null> {
  try {
    const response = await getBrowserApiClient().http.get<CurrentUser>(
      '/v1/auth/me',
      { signal },
    )
    return response.data
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

const endpoints = {
  register: {
    send: '/v1/auth/registration-codes',
    complete: '/v1/auth/registrations',
  },
  login: { send: '/v1/auth/login-codes', complete: '/v1/auth/login' },
} as const

export async function sendEmailCode(
  mode: AuthMode,
  email: string,
): Promise<IssuedCode> {
  const response = await getBrowserApiClient().http.post<unknown>(
    endpoints[mode].send,
    { email },
  )
  const parsed = issuedCodeSchema.safeParse(response.data)
  if (!parsed.success)
    throw new ApiError('unexpected', '验证码请求响应异常，请稍后重试')
  return parsed.data
}

export async function completeEmailVerification(
  mode: AuthMode,
  body: VerificationRequest,
): Promise<void> {
  await getBrowserApiClient().http.post<void>(endpoints[mode].complete, body)
}
