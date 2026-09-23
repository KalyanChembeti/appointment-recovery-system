import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('LoginPage', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/login')
    document.cookie = 'XSRF-TOKEN=login-test-token; Path=/'
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('updates the auth context state after a successful login', async () => {
    let identityCalls = 0
    const fetchMock = vi.fn().mockImplementation((path: string) => {
      if (path === '/api/auth/me') {
        identityCalls += 1
        return Promise.resolve(identityCalls === 1
          ? jsonResponse({ message: 'Unauthorized' }, 401)
          : jsonResponse({ userId: 42, role: 'PATIENT' }))
      }
      return Promise.resolve(jsonResponse({ userId: 42, authenticated: true }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Email address'), 'patient@example.com')
    await user.type(screen.getByLabelText('Password'), 'ValidPass1!')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Logged in as 42, role PATIENT')).toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/auth/me',
      '/api/auth/login',
      '/api/auth/me',
    ])
  })

  it('renders the normalized API message after a failed login', async () => {
    const fetchMock = vi.fn().mockImplementation((path: string) => Promise.resolve(
      path === '/api/auth/me'
        ? jsonResponse({ message: 'Unauthorized' }, 401)
        : jsonResponse({ message: 'Invalid email or password' }, 401),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Email address'), 'patient@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
  })
})
