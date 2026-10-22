import { Button } from '@/shadcn/components/ui/button'
import { Input } from '@/shadcn/components/ui/input'

interface EmailFormProps {
  email: string
  error?: string
  busy: boolean
  onChange: (email: string) => void
  onSubmit: () => void
}

export function EmailForm({
  email,
  error,
  busy,
  onChange,
  onSubmit,
}: EmailFormProps) {
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit()
      }}
      className="space-y-5"
    >
      <div className="space-y-2">
        <label htmlFor="email" className="text-sm font-medium">
          邮箱地址
        </label>
        <Input
          id="email"
          name="email"
          type="email"
          autoComplete="email"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="you@example.com"
          value={email}
          disabled={busy}
          aria-invalid={!!error}
          aria-describedby={error ? 'email-error' : undefined}
          onChange={(event) => onChange(event.target.value)}
        />
        {error && (
          <p id="email-error" role="alert" className="text-destructive text-sm">
            {error}
          </p>
        )}
      </div>
      <Button type="submit" size="lg" disabled={busy} className="h-11 w-full">
        {busy ? '正在请求…' : '获取验证码'}
      </Button>
    </form>
  )
}
