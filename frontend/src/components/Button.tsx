import type { ButtonHTMLAttributes, ReactNode } from 'react'

type ButtonVariant = 'primary' | 'secondary' | 'danger'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant
  loading?: boolean
  loadingText?: ReactNode
}

const variantClasses: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-white shadow-sm hover:bg-accent-strong focus-visible:ring-brand/30',
  secondary: 'border border-border bg-surface text-brand hover:bg-brand-soft focus-visible:ring-brand/25',
  danger: 'bg-danger text-white shadow-sm hover:bg-danger-strong focus-visible:ring-danger/25',
}

export function Button({
  children,
  className = '',
  disabled,
  loading = false,
  loadingText,
  variant = 'primary',
  ...buttonProps
}: ButtonProps) {
  return (
    <button
      {...buttonProps}
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-4 disabled:cursor-not-allowed disabled:opacity-60 ${variantClasses[variant]} ${className}`}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
    >
      {loading && (
        <span
          className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent"
          aria-hidden="true"
        />
      )}
      <span>{loading ? (loadingText ?? children) : children}</span>
    </button>
  )
}
