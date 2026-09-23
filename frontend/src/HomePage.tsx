import { useNavigate } from 'react-router-dom'
import { useAuth } from './auth/authState'

export function HomePage() {
  const { user, logout, logoutState } = useAuth()
  const navigate = useNavigate()

  async function signOut() {
    try {
      await logout()
      navigate('/login', { replace: true })
    } catch { /* Render context error below. */ }
  }

  // This shared home page only proves session restoration, route protection, and logout.
  // Role-specific dashboards and workflow screens are added in later stages.
  return (
    <main className="auth-shell">
      <section className="auth-card confirmation" aria-labelledby="home-heading">
        <div className="brand-mark" aria-hidden="true">AR</div>
        <p className="eyebrow">Appointment Recovery System</p>
        <h1 id="home-heading">Welcome back</h1>
        <p className="success-message">
          Logged in as {user?.userId}, role {user?.role}
        </p>
        <button className="primary-button" type="button" onClick={signOut} disabled={logoutState.loading}>
          {logoutState.loading ? 'Signing out...' : 'Sign out'}
        </button>
        {logoutState.error && <p className="error-message" role="alert">{logoutState.error.message}</p>}
      </section>
    </main>
  )
}
