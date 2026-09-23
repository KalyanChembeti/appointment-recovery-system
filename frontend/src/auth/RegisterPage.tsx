import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { FormField } from '../components/FormField'
import { PageLayout } from '../components/PageLayout'
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
    <PageLayout>
      <div className="mx-auto flex min-h-[calc(100svh-10rem)] max-w-md items-center py-6">
        <Card className="w-full">
          <div className="mb-8">
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.16em] text-brand">Patient access</p>
            <h1 className="text-3xl font-bold tracking-tight text-ink sm:text-4xl">Create your account</h1>
            <p className="mt-3 leading-6 text-muted">Join the recovery waitlist experience with a secure patient account.</p>
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
              error={registerState.error?.errors?.email}
              required
            />
            <FormField
              id="password"
              name="password"
              type="password"
              label="Password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              error={registerState.error?.errors?.password}
              required
            />
            <Button
              className="w-full"
              type="submit"
              loading={registerState.loading}
              loadingText="Creating account..."
            >
              Create account
            </Button>
          </form>

          {registerState.error && !registerState.error.errors && (
            <p className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-sm font-medium text-danger" role="alert">
              {registerState.error.message}
            </p>
          )}

          <p className="mt-6 text-center text-sm text-muted">
            Already registered?{' '}
            <Link className="font-semibold text-brand underline decoration-brand/30 underline-offset-4 hover:text-brand-deep" to="/login">
              Sign in
            </Link>
          </p>
        </Card>
      </div>
    </PageLayout>
  )
}
