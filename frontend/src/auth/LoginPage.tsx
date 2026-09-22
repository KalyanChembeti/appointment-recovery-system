import { useState, type FormEvent } from 'react'
import { useAuth } from './authState'

export function LoginPage() {
  const { user, login, logout, loginState, logoutState } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    try { await login(email, password) } catch { /* Render context error below. */ }
  }

  async function signOut() {
    try { await logout() } catch { /* Render context error below. */ }
  }

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="auth-heading">
        <div className="brand-mark" aria-hidden="true">AR</div>
        <p className="eyebrow">Appointment Recovery System</p>
        {user ? (
          <div className="confirmation">
            <h1 id="auth-heading">Welcome back</h1>
            <p className="success-message">
              Logged in as {user.userId}, role {user.role}
            </p>
            <button className="primary-button" type="button" onClick={signOut} disabled={logoutState.loading}>
              {logoutState.loading ? 'Signing out…' : 'Sign out'}
            </button>
            {logoutState.error && <p className="error-message" role="alert">{logoutState.error.message}</p>}
          </div>
        ) : (
          <>
            <h1 id="auth-heading">Sign in</h1>
            <p className="supporting-copy">Access your appointment recovery workspace.</p>
            <form onSubmit={submit}>
              <label htmlFor="email">Email address</label>
              <input id="email" name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
              <label htmlFor="password">Password</label>
              <input id="password" name="password" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
              <button className="primary-button" type="submit" disabled={loginState.loading}>
                {loginState.loading ? 'Signing in…' : 'Sign in'}
              </button>
            </form>
            {loginState.error && <p className="error-message" role="alert">{loginState.error.message}</p>}
          </>
        )}
      </section>
    </main>
  )
}
