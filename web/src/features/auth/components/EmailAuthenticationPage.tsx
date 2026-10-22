import type { AuthMode, IssuedCode, VerificationRequest } from '../types'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useRouter } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/lib/api-client'
import { Button } from '@/shadcn/components/ui/button'
import { refreshCsrfToken } from '../api'
import {
  synchronizeAuthentication,
  useSendCodeMutation,
  useVerifyCodeMutation,
} from '../mutations'
import { codeSchema, emailSchema } from '../schemas'
import { EmailForm } from './EmailForm'
import { VerificationForm } from './VerificationForm'

const hints: Record<number, string> = {
  400: '请检查输入内容后重试。',
  401: '请核对验证码，或重新获取验证码。',
  403: '请求被拒绝。可刷新请求校验信息，再手动提交；若仍失败，请重新获取验证码。',
  409: '当前认证状态存在冲突，请在首页检查登录状态。',
  429: '请求较频繁，请稍后再试。',
  503: '服务暂时不可用，请稍后手动重试。',
}

export function EmailAuthenticationPage({ mode }: { mode: AuthMode }) {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [challenge, setChallenge] = useState<
    (IssuedCode & { resendAt: number }) | undefined
  >()
  const [now, setNow] = useState(Date.now)
  const [fieldError, setFieldError] = useState<string>()
  const [error, setError] = useState<ApiError>()
  const [notice, setNotice] = useState('')
  const [verified, setVerified] = useState(false)
  const [busy, setBusy] = useState(false)
  const lockedRef = useRef(false)
  const activeRef = useRef(true)
  const submissionRef = useRef<VerificationRequest | undefined>(undefined)
  const send = useSendCodeMutation(mode)
  const verify = useVerifyCodeMutation(mode, () => {
    if (!submissionRef.current)
      throw new ApiError('unexpected', '验证请求已取消')
    return submissionRef.current
  })
  const resetSend = send.reset
  const resetVerify = verify.reset

  useEffect(() => {
    activeRef.current = true
    return () => {
      activeRef.current = false
      submissionRef.current = undefined
      resetSend()
      resetVerify()
    }
  }, [resetSend, resetVerify])

  useEffect(() => {
    if (!challenge) return
    const interval = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(interval)
  }, [challenge])

  async function run(work: () => Promise<void>) {
    if (lockedRef.current || !activeRef.current) return
    lockedRef.current = true
    setBusy(true)
    setError(undefined)
    setNotice('')
    try {
      await work()
    } catch (failure) {
      if (activeRef.current)
        setError(
          failure instanceof ApiError
            ? failure
            : new ApiError('unexpected', '处理失败，请稍后手动重试'),
        )
    } finally {
      submissionRef.current = undefined
      resetSend()
      resetVerify()
      lockedRef.current = false
      if (activeRef.current) setBusy(false)
    }
  }

  async function synchronize() {
    await synchronizeAuthentication(queryClient)
    if (!activeRef.current) return
    await router.invalidate()
    if (activeRef.current) await router.navigate({ to: '/', replace: true })
  }

  function requestCode() {
    if (
      lockedRef.current ||
      verified ||
      (challenge && Date.now() < challenge.resendAt)
    )
      return
    const parsed = emailSchema.safeParse(email)
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message)
      return
    }
    setFieldError(undefined)
    void run(async () => {
      const issued = await send.mutateAsync(parsed.data)
      if (!activeRef.current) return
      const receivedAt = Date.now()
      setEmail(parsed.data)
      setCode('')
      setNow(receivedAt)
      setChallenge({
        ...issued,
        resendAt: receivedAt + issued.retryAfterSeconds * 1000,
      })
    })
  }

  function complete() {
    if (lockedRef.current || !challenge || verified) return
    if (Date.now() >= Date.parse(challenge.expiresAt)) {
      setNow(Date.now())
      return
    }
    const parsed = codeSchema.safeParse(code)
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message)
      return
    }
    setFieldError(undefined)
    const request = {
      email,
      challengeId: challenge.challengeId,
      code: parsed.data,
    }
    void run(async () => {
      submissionRef.current = request
      await verify.mutateAsync()
      submissionRef.current = undefined
      if (!activeRef.current) return
      setVerified(true)
      setChallenge(undefined)
      setCode('')
      await synchronize()
    })
  }

  function back() {
    if (lockedRef.current) return
    setChallenge(undefined)
    setCode('')
    setError(undefined)
    setFieldError(undefined)
    setNotice('')
    resetSend()
    resetVerify()
  }

  const expiresIn = challenge
    ? Math.max(0, Math.ceil((Date.parse(challenge.expiresAt) - now) / 1000))
    : 0
  const resendIn = challenge
    ? Math.max(0, Math.ceil((challenge.resendAt - now) / 1000))
    : 0

  return (
    <main className="bg-background text-foreground flex min-h-svh items-center justify-center px-4 py-10 sm:px-6">
      <section
        className="bg-card text-card-foreground border-border w-full max-w-md space-y-7 rounded-2xl border p-6 shadow-sm sm:p-8"
        aria-labelledby="auth-heading"
      >
        <header className="space-y-2">
          <Link
            to="/"
            className="text-muted-foreground text-sm font-semibold tracking-wider"
          >
            Pikdo
          </Link>
          <p className="text-primary pt-4 text-xs font-medium tracking-widest">
            {verified
              ? '状态同步'
              : challenge
                ? '第二步 · 验证邮箱'
                : '第一步 · 输入邮箱'}
          </p>
          <h1
            id="auth-heading"
            className="text-2xl font-semibold tracking-tight"
          >
            {mode === 'register' ? '创建你的 Pikdo 账户' : '欢迎回到 Pikdo'}
          </h1>
          <p className="text-muted-foreground text-sm">
            {mode === 'register'
              ? '使用邮箱验证码注册，无需设置密码。'
              : '使用邮箱验证码登录，无需输入密码。'}
          </p>
        </header>
        {error && (
          <div
            role="alert"
            className="bg-destructive/10 text-destructive space-y-2 rounded-lg p-3 text-sm"
          >
            <p>{error.message}</p>
            <p>
              {verified
                ? '验证已完成，请重试状态同步；无需再次提交验证码。'
                : error.status
                  ? hints[error.status]
                  : '请求结果未确认，可手动重试或返回首页检查登录状态。'}
            </p>
            {error.status === 403 && !verified && (
              <Button
                variant="outline"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    await refreshCsrfToken()
                    if (activeRef.current)
                      setNotice('请求校验信息已更新，请手动重试。')
                  })
                }
              >
                刷新请求校验信息
              </Button>
            )}
          </div>
        )}
        {notice && (
          <p role="status" className="text-muted-foreground text-sm">
            {notice}
          </p>
        )}
        {verified ? (
          <div className="space-y-4">
            <p role="status">
              {busy
                ? '验证已完成，正在同步登录状态…'
                : '验证已完成，登录状态尚未确认。'}
            </p>
            <Button
              className="h-11 w-full"
              disabled={busy}
              onClick={() => void run(synchronize)}
            >
              重试状态同步
            </Button>
          </div>
        ) : challenge ? (
          <VerificationForm
            email={email}
            code={code}
            error={fieldError}
            busy={busy}
            expired={expiresIn === 0}
            expiresIn={expiresIn}
            resendIn={resendIn}
            onChange={(value) => {
              setCode(value)
              setFieldError(undefined)
              setError(undefined)
            }}
            onSubmit={complete}
            onResend={requestCode}
            onBack={back}
          />
        ) : (
          <EmailForm
            email={email}
            error={fieldError}
            busy={busy}
            onChange={(value) => {
              setEmail(value)
              setFieldError(undefined)
              setError(undefined)
            }}
            onSubmit={requestCode}
          />
        )}
        <footer className="text-muted-foreground border-border border-t pt-5 text-center text-sm">
          {mode === 'register' ? (
            <>
              已有账户？
              <Link
                to="/login"
                className="text-primary ml-1 underline underline-offset-4"
              >
                前往登录
              </Link>
            </>
          ) : (
            <>
              还没有账户？
              <Link
                to="/register"
                className="text-primary ml-1 underline underline-offset-4"
              >
                注册账户
              </Link>
            </>
          )}
        </footer>
      </section>
    </main>
  )
}
