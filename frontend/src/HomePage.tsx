import { Link } from 'react-router-dom'
import { Card } from './components/Card'
import { PageLayout } from './components/PageLayout'
import { useAuth } from './auth/authState'

export function HomePage() {
  const { user } = useAuth()

  return (
    <PageLayout>
      <div className="mx-auto max-w-3xl py-6 sm:py-10">
        <p className="mb-3 text-xs font-bold uppercase tracking-[0.16em] text-brand">Your workspace</p>
        <h1 className="text-4xl font-bold tracking-tight text-ink sm:text-5xl">Welcome back</h1>
        <p className="mt-4 max-w-2xl text-lg leading-8 text-muted">
          Your secure appointment recovery workspace is ready.
        </p>

        {user?.role === 'PATIENT' && (
          <div className="mt-7 flex flex-wrap gap-3">
            <Link
              className="inline-flex min-h-11 items-center justify-center rounded-xl bg-accent px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-accent-strong focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/30"
              to="/book"
            >
              Book an appointment
            </Link>
            <Link
              className="inline-flex min-h-11 items-center justify-center rounded-xl border border-border bg-surface px-4 py-2.5 text-sm font-semibold text-brand transition-colors hover:bg-brand-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/25"
              to="/appointments"
            >
              My appointments
            </Link>
            <Link
              className="inline-flex min-h-11 items-center justify-center rounded-xl border border-border bg-surface px-4 py-2.5 text-sm font-semibold text-brand transition-colors hover:bg-brand-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/25"
              to="/offers"
            >
              My offers
            </Link>
          </div>
        )}

        {(user?.role === 'RECEPTIONIST' || user?.role === 'ADMIN') && (
          <div className="mt-7 flex flex-wrap gap-3">
            <Link
              className="inline-flex min-h-11 items-center justify-center rounded-xl bg-accent px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-accent-strong focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/30"
              to="/book-for-patient"
            >
              Book for a patient
            </Link>
            {user.role === 'ADMIN' && (
              <Link
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-border bg-surface px-4 py-2.5 text-sm font-semibold text-brand transition-colors hover:bg-brand-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand/25"
                to="/provider-blocking"
              >
                Block provider time
              </Link>
            )}
          </div>
        )}

        <Card className="mt-8">
          <div className="flex items-start gap-4">
            <span className="grid size-11 shrink-0 place-items-center rounded-full bg-brand-soft text-brand" aria-hidden="true">
              <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="m5 12 4 4L19 6" />
              </svg>
            </span>
            <div>
              <h2 className="text-lg font-bold text-ink">Session confirmed</h2>
              <p className="mt-1 leading-6 text-muted">
                Logged in as {user?.userId}, role {user?.role}
              </p>
            </div>
          </div>
        </Card>
      </div>
    </PageLayout>
  )
}
