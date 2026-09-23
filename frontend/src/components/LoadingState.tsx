type LoadingStateProps = {
  label?: string
}

export function LoadingState({ label = 'Loading...' }: LoadingStateProps) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas px-4" role="status" aria-live="polite">
      <div className="flex items-center gap-3 rounded-xl border border-border bg-surface px-5 py-3 text-sm font-semibold text-muted shadow-card">
        <span className="size-5 animate-spin rounded-full border-2 border-brand-soft border-r-brand" aria-hidden="true" />
        <span>{label}</span>
      </div>
    </div>
  )
}
