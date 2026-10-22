import { createFileRoute } from '@tanstack/react-router'
import { EmailAuthenticationPage } from '@/features/auth/components/EmailAuthenticationPage'

export const Route = createFileRoute('/login')({
  head: () => ({ meta: [{ title: '邮箱登录 · Pikdo' }] }),
  component: () => <EmailAuthenticationPage key="login" mode="login" />,
})
