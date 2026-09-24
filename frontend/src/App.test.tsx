import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('authenticated routing', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=routing-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('redirects an already authenticated visitor from login to home', async () => {
    window.history.pushState({}, '', '/login')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ userId: 81, role: 'RECEPTIONIST' }),
    ))

    render(<App />)

    expect(await screen.findByText('Logged in as 81, role RECEPTIONIST')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })

  it('logs out from home and navigates back to login', async () => {
    window.history.pushState({}, '', '/')
    const fetchMock = vi.fn().mockImplementation((path: string) => Promise.resolve(
      path === '/api/auth/me'
        ? jsonResponse({ userId: 82, role: 'PATIENT' })
        : jsonResponse({ status: 'logged out' }),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/login')
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/auth/me',
      '/api/auth/logout',
    ])
  })

  it('blocks the booking route for an authenticated non-patient role', async () => {
    window.history.pushState({}, '', '/book')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ userId: 83, role: 'PROVIDER' }),
    ))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Not authorized' }))
      .toBeInTheDocument()
    expect(window.location.pathname).toBe('/not-authorized')
    expect(screen.queryByRole('heading', { name: 'Book an appointment' }))
      .not.toBeInTheDocument()
  })

  it('blocks the appointments route for an authenticated non-patient role', async () => {
    window.history.pushState({}, '', '/appointments')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ userId: 84, role: 'RECEPTIONIST' }),
    ))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Not authorized' }))
      .toBeInTheDocument()
    expect(window.location.pathname).toBe('/not-authorized')
    expect(screen.queryByRole('heading', { name: 'My appointments' }))
      .not.toBeInTheDocument()
  })

  it('blocks the offers route for an authenticated non-patient role', async () => {
    window.history.pushState({}, '', '/offers')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ userId: 85, role: 'ADMIN' }),
    ))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Not authorized' }))
      .toBeInTheDocument()
    expect(window.location.pathname).toBe('/not-authorized')
    expect(screen.queryByRole('heading', { name: 'My offers' }))
      .not.toBeInTheDocument()
  })
})
