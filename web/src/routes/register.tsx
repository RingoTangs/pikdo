import { createFileRoute } from '@tanstack/react-router'
import { EmailAuthenticationPage } from '@/features/auth/components/EmailAuthenticationPage'

export const Route = createFileRoute('/register')({
  head: () => ({ meta: [{ title: '邮箱注册 · Pikdo' }] }),
  component: () => <EmailAuthenticationPage key="register" mode="register" />,
})
