import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { FormField } from '../components/FormField'
import { PageLayout } from '../components/PageLayout'
import { useAuth } from './authState'

export function LoginPage() {
  const { login, loginState } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    try {
      await login(email, password)
      navigate('/', { replace: true })
    } catch { /* Render context error below. */ }
  }

  return (
    <PageLayout>
      <div className="mx-auto flex min-h-[calc(100svh-10rem)] max-w-md items-center py-6">
        <Card className="w-full">
          <div className="mb-8">
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">Secure access</p>
            <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">Sign in</h1>
            <p className="mt-3 leading-6 text-muted">Sign in to manage appointment recovery from one calm, secure workspace.</p>
          </div>

          <form className="space-y-5" onSubmit={submit}>
            <FormField
              id="email"
              name="email"
              type="email"
              label="Email address"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              error={loginState.error?.errors?.email}
              required
            />
            <FormField
              id="password"
              name="password"
              type="password"
              label="Password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              error={loginState.error?.errors?.password}
              required
            />
            <Button
              className="w-full"
              type="submit"
              loading={loginState.loading}
              loadingText="Signing in..."
            >
              Sign in
            </Button>
          </form>

          {loginState.error && !loginState.error.errors && (
            <p className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
              {loginState.error.message}
            </p>
          )}

          <p className="mt-6 text-center text-sm text-muted">
            Need an account?{' '}
            <Link className="font-semibold text-brand underline decoration-brand/30 underline-offset-4 hover:text-brand-deep" to="/register">
              Register
            </Link>
          </p>
        </Card>
      </div>
    </PageLayout>
  )
}
