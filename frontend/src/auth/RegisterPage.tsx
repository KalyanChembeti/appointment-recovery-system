import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from './authState'

export function RegisterPage() {
  const { register, registerState } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    try {
      await register(email, password)
      navigate('/', { replace: true })
    } catch { /* Render context error below. */ }
  }

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="auth-heading">
        <div className="brand-mark" aria-hidden="true">AR</div>
        <p className="eyebrow">Appointment Recovery System</p>
        <h1 id="auth-heading">Create account</h1>
        <p className="supporting-copy">Register for appointment recovery access.</p>
        <form onSubmit={submit}>
          <label htmlFor="email">Email address</label>
          <input id="email" name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
          <button className="primary-button" type="submit" disabled={registerState.loading}>
            {registerState.loading ? 'Creating account...' : 'Create account'}
          </button>
        </form>
        {registerState.error && <p className="error-message" role="alert">{registerState.error.message}</p>}
        <p className="auth-navigation">Already registered? <Link to="/login">Sign in</Link></p>
      </section>
    </main>
  )
}
