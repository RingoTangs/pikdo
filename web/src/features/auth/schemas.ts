import { z } from 'zod'

export const emailSchema = z
  .string()
  .trim()
  .max(254, '邮箱不能超过 254 个字符')
  .pipe(z.email('请输入有效的邮箱地址'))
export const codeSchema = z.string().regex(/^\d{6}$/, '请输入六位数字验证码')
export const issuedCodeSchema = z.object({
  challengeId: z.uuid(),
  expiresAt: z.iso.datetime({ offset: true }),
  retryAfterSeconds: z.number().int().nonnegative(),
})
