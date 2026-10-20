// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Button } from './Button'

afterEach(cleanup)

describe('通用按钮', () => {
  it('默认使用 button 类型，并允许显式设置 submit', () => {
    render(
      <>
        <Button>Default</Button>
        <Button type="submit">Submit</Button>
      </>,
    )

    expect(screen.getByRole('button', { name: 'Default' })).toHaveAttribute(
      'type',
      'button',
    )
    expect(screen.getByRole('button', { name: 'Submit' })).toHaveAttribute(
      'type',
      'submit',
    )
  })

  it('透传原生事件和禁用状态', async () => {
    const onClick = vi.fn()
    const user = userEvent.setup()
    render(
      <>
        <Button onClick={onClick}>Enabled</Button>
        <Button disabled onClick={onClick}>
          Disabled
        </Button>
      </>,
    )

    await user.click(screen.getByRole('button', { name: 'Enabled' }))
    await user.click(screen.getByRole('button', { name: 'Disabled' }))

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Disabled' })).toBeDisabled()
  })

  it('合并自定义样式并透传 ref', () => {
    let buttonRef: HTMLButtonElement | null = null
    render(
      <Button
        ref={(element) => {
          buttonRef = element
        }}
        variant="outline"
        className="custom-class"
      >
        Styled
      </Button>,
    )

    const button = screen.getByRole('button', { name: 'Styled' })
    expect(button).toHaveClass('custom-class', 'border-border')
    expect(buttonRef).toBe(button)
  })
})
