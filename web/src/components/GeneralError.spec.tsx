// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { GeneralError } from './GeneralError'

vi.mock('@tanstack/react-router', () => ({
  useNavigate: vi.fn().mockReturnValue(vi.fn()),
  useRouter: vi.fn().mockReturnValue({ history: { go: vi.fn() } }),
}))

afterEach(cleanup)

it('将 HTML 属性和事件透传到外层元素，保留自定义样式和 minimal 行为', async () => {
  const onClick = vi.fn()
  const user = userEvent.setup()
  render(
    <GeneralError
      minimal
      id="error-panel"
      role="region"
      aria-label="页面错误"
      className="custom-class"
      onClick={onClick}
    />,
  )

  const panel = screen.getByRole('region', { name: '页面错误' })
  expect(panel).toHaveAttribute('id', 'error-panel')
  expect(panel).toHaveClass('h-svh', 'w-full', 'custom-class')
  expect(screen.queryByRole('heading', { name: '500' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()

  await user.click(panel)
  expect(onClick).toHaveBeenCalledTimes(1)
})
