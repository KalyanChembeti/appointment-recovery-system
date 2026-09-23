import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('RegisterPage', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/register')
    document.cookie = 'XSRF-TOKEN=register-test-token; Path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('navigates to the authenticated home after successful registration', async () => {
    let identityCalls = 0
    const fetchMock = vi.fn().mockImplementation((path: string) => {
      if (path === '/api/auth/me') {
        identityCalls += 1
        return Promise.resolve(identityCalls === 1
          ? jsonResponse({ message: 'Unauthorized' }, 401)
          : jsonResponse({ userId: 51, role: 'PATIENT' }))
      }
      return Promise.resolve(jsonResponse({
        userId: 51,
        email: 'new-patient@example.com',
        role: 'PATIENT',
      }, 201))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<App />)

    await user.type(await screen.findByLabelText('Email address'), 'new-patient@example.com')
    await user.type(screen.getByLabelText('Password'), 'ValidPass1!')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('Logged in as 51, role PATIENT')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/auth/me',
      '/api/auth/register',
      '/api/auth/me',
    ])
  })

  it('displays the normalized API message after failed registration', async () => {
    const fetchMock = vi.fn().mockImplementation((path: string) => Promise.resolve(
      path === '/api/auth/me'
        ? jsonResponse({ message: 'Unauthorized' }, 401)
        : jsonResponse({ message: 'An account already uses this email' }, 409),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<App />)

    await user.type(await screen.findByLabelText('Email address'), 'existing@example.com')
    await user.type(screen.getByLabelText('Password'), 'ValidPass1!')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'An account already uses this email',
    )
    await waitFor(() => expect(window.location.pathname).toBe('/register'))
  })
})
