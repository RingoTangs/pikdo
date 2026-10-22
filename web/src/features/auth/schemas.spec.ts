import { expect, it } from 'vitest'
import { codeSchema, emailSchema } from './schemas'

it('trims outer spaces while preserving email spelling', () => {
  expect(emailSchema.parse('  Original+Tag@Example.com  ')).toBe(
    'Original+Tag@Example.com',
  )
})

it.each([
  '',
  'not-an-email',
  'a@',
  'a b@example.com',
  `${'a'.repeat(255)}@example.com`,
])('rejects invalid email %s', (email) => {
  expect(emailSchema.safeParse(email).success).toBe(false)
})

it('preserves six ASCII digits including leading zeroes', () => {
  expect(codeSchema.parse('000123')).toBe('000123')
})

it.each(['12345', '1234567', '１２３４５６', 'abc123', '123 56'])(
  'rejects invalid verification code %s',
  (code) => {
    expect(codeSchema.safeParse(code).success).toBe(false)
  },
)
