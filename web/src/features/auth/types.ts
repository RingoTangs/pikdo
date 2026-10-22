export type { CsrfToken as CsrfResponse } from '@/lib/api-client'

export interface CurrentUser {
  userId: string
  status: 'ACTIVE' | 'DISABLED'
}
