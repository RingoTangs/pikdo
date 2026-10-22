import type { QueryClient } from '@tanstack/react-query'
import type { AuthMode, VerificationRequest } from './types'
import { useMutation } from '@tanstack/react-query'
import { ApiError } from '@/lib/api-client'
import {
  clearCsrfToken,
  completeEmailVerification,
  refreshCsrfToken,
  sendEmailCode,
} from './api'
import { currentUserOptions } from './queries'

export function useSendCodeMutation(mode: AuthMode) {
  return useMutation({
    mutationFn: (email: string) => sendEmailCode(mode, email),
    retry: false,
    gcTime: 0,
  })
}

export function useVerifyCodeMutation(
  mode: AuthMode,
  getRequest: () => VerificationRequest,
) {
  // 验证材料从页面即时读取，不进入 Mutation variables 或用户 Query 缓存。
  return useMutation({
    mutationFn: () => completeEmailVerification(mode, getRequest()),
    retry: false,
    gcTime: 0,
  })
}

export async function synchronizeAuthentication(queryClient: QueryClient) {
  clearCsrfToken()
  await queryClient.cancelQueries({ queryKey: ['auth', 'me'], exact: true })
  queryClient.removeQueries({ queryKey: ['auth', 'me'], exact: true })
  await refreshCsrfToken()
  const user = await queryClient.fetchQuery({
    ...currentUserOptions(),
    staleTime: 0,
  })
  if (!user)
    throw new ApiError('http', '暂未确认登录状态，请重试同步或重新登录', 401)
  return user
}
