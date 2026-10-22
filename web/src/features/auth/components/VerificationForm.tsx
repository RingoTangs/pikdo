import { Button } from '@/shadcn/components/ui/button'
import { Input } from '@/shadcn/components/ui/input'

interface VerificationFormProps {
  email: string
  code: string
  error?: string
  busy: boolean
  expired: boolean
  expiresIn: number
  resendIn: number
  onChange: (code: string) => void
  onSubmit: () => void
  onResend: () => void
  onBack: () => void
}

export function VerificationForm(props: VerificationFormProps) {
  const {
    email,
    code,
    error,
    busy,
    expired,
    expiresIn,
    resendIn,
    onChange,
    onSubmit,
    onResend,
    onBack,
  } = props
  return (
    <div className="space-y-5">
      <div className="bg-muted text-muted-foreground space-y-2 rounded-lg p-4 text-sm">
        <p className="text-foreground font-medium break-all">{email}</p>
        <p>
          请求已受理。若符合条件，邮件将发送到此邮箱，请检查收件箱和垃圾邮件。
        </p>
      </div>
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit()
        }}
        className="space-y-5"
      >
        <div className="space-y-2">
          <label htmlFor="code" className="text-sm font-medium">
            六位验证码
          </label>
          <Input
            id="code"
            name="code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            pattern="[0-9]{6}"
            placeholder="000000"
            value={code}
            disabled={busy || expired}
            aria-invalid={!!error}
            aria-describedby="code-help code-error"
            className="text-center text-xl tracking-[0.35em]"
            onChange={(event) =>
              onChange(event.target.value.replace(/\D/g, '').slice(0, 6))
            }
          />
          <p
            id="code-help"
            role="status"
            className="text-muted-foreground text-sm"
          >
            {expired
              ? '验证码已过期，请重新获取。'
              : `验证码将在 ${expiresIn} 秒后过期`}
          </p>
          {error && (
            <p
              id="code-error"
              role="alert"
              className="text-destructive text-sm"
            >
              {error}
            </p>
          )}
        </div>
        <Button
          type="submit"
          size="lg"
          className="h-11 w-full"
          disabled={busy || expired}
        >
          {busy ? '正在处理…' : '验证并继续'}
        </Button>
      </form>
      <div className="flex flex-wrap justify-between gap-3">
        <Button
          type="button"
          variant="outline"
          disabled={busy || resendIn > 0}
          onClick={onResend}
        >
          {resendIn > 0 ? `${resendIn} 秒后可重新发送` : '重新发送验证码'}
        </Button>
        <Button type="button" variant="ghost" disabled={busy} onClick={onBack}>
          修改邮箱 / 返回上一步
        </Button>
      </div>
    </div>
  )
}
