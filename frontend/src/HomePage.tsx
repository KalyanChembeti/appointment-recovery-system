import { Card } from './components/Card'
import { PageLayout } from './components/PageLayout'
import { useAuth } from './auth/authState'

export function HomePage() {
  const { user } = useAuth()

  // This shared home page only proves session restoration, route protection, and logout.
  // Role-specific dashboards and workflow screens are added in later stages.
  return (
    <PageLayout>
      <div className="mx-auto max-w-3xl py-6 sm:py-10">
        <p className="mb-3 text-xs font-bold uppercase tracking-[0.16em] text-brand">Your workspace</p>
        <h1 className="text-4xl font-bold tracking-tight text-ink sm:text-5xl">Welcome back</h1>
        <p className="mt-4 max-w-2xl text-lg leading-8 text-muted">
          Your secure appointment recovery workspace is ready. Scheduling tools will appear here in the next stage.
        </p>

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
