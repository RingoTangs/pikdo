import type { CurrentUser } from './types'
import { ApiError, getBrowserApiClient } from '@/lib/api-client'

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
