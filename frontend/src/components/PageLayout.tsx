import type { PropsWithChildren } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/authState'
import { Button } from './Button'

export function PageLayout({ children }: PropsWithChildren) {
  const { user, logout, logoutState } = useAuth()
  const navigate = useNavigate()

  async function signOut() {
    try {
      await logout()
      navigate('/login', { replace: true })
    } catch { /* Render the context error below. */ }
  }

  return (
    <div className="flex min-h-screen flex-col bg-canvas text-ink">
      <header className="border-b border-border bg-surface/95 backdrop-blur">
        <div className="mx-auto flex min-h-18 w-full max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6 lg:px-8">
          <Link className="flex items-center gap-3 text-ink no-underline" to="/">
            <span className="grid size-10 place-items-center rounded-xl bg-brand text-sm font-black tracking-tight text-white shadow-sm" aria-hidden="true">
              AR
            </span>
            <span>
              <span className="block text-sm font-bold leading-tight">Appointment Recovery</span>
              <span className="block text-xs font-medium text-muted">Clinic scheduling</span>
            </span>
          </Link>

          {user && (
            <div className="flex items-center gap-3 sm:gap-4">
              <div className="hidden text-right sm:block">
                <span className="block text-sm font-semibold text-ink">Account #{user.userId}</span>
                <span className="block text-xs font-semibold tracking-wide text-muted">{user.role}</span>
              </div>
              <Button
                type="button"
                variant="secondary"
                loading={logoutState.loading}
                loadingText="Signing out..."
                onClick={signOut}
              >
                Sign out
              </Button>
            </div>
          )}
        </div>
        {logoutState.error && (
          <p className="mx-auto w-full max-w-6xl px-4 pb-3 text-sm font-medium text-danger sm:px-6 lg:px-8" role="alert">
            {logoutState.error.message}
          </p>
        )}
      </header>

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6 sm:py-10 lg:px-8">
        {children}
      </main>
    </div>
  )
}
