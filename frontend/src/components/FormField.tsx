import { useId, type InputHTMLAttributes } from 'react'

type FormFieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string
}

export function FormField({
  className = '',
  error,
  id,
  label,
  ...inputProps
}: FormFieldProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const errorId = `${inputId}-error`

  return (
    <div className="space-y-2">
      <label className="block text-sm font-semibold text-ink" htmlFor={inputId}>
        {label}
      </label>
      <input
        {...inputProps}
        id={inputId}
        className={`block min-h-12 w-full rounded-xl border bg-surface px-3.5 py-2.5 text-base text-ink shadow-xs outline-none transition placeholder:text-muted/70 focus:border-brand focus:ring-4 focus:ring-brand/15 ${error ? 'border-danger' : 'border-border'} ${className}`}
        aria-describedby={error ? errorId : undefined}
        aria-invalid={error ? true : undefined}
      />
      {error && (
        <p id={errorId} className="text-sm font-medium text-danger" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
