import type { HTMLAttributes } from 'react'

export function Card({ className = '', ...cardProps }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      {...cardProps}
      className={`rounded-2xl border border-border bg-surface p-6 shadow-card sm:p-8 ${className}`}
    />
  )
}
