export type { CsrfToken as CsrfResponse } from '@/lib/api-client'

export interface CurrentUser {
  userId: string
  status: 'ACTIVE' | 'DISABLED'
}

export type AuthMode = 'register' | 'login'

export interface IssuedCode {
  challengeId: string
  expiresAt: string
  retryAfterSeconds: number
}

export interface VerificationRequest {
  challengeId: string
  email: string
  code: string
}
