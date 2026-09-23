import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { UserRole } from '../api/types'
import { AuthContext, type AuthContextValue, type AuthenticatedUser } from '../auth/authState'
import { ProtectedRoute } from './ProtectedRoute'

const IDLE_STATE = { loading: false, error: null }

function LoginDestination() {
  const location = useLocation()
  const state = location.state as { from?: { pathname?: string } } | null
  return <p>Login destination from {state?.from?.pathname ?? 'unknown'}</p>
}

function renderProtectedRoute(
  user: AuthenticatedUser | null,
  isInitializing: boolean,
  allowedRoles?: readonly UserRole[],
) {
  const value: AuthContextValue = {
    user,
    isInitializing,
    loginState: IDLE_STATE,
    registerState: IDLE_STATE,
    logoutState: IDLE_STATE,
    login: vi.fn(async () => undefined),
    register: vi.fn(async () => undefined),
    logout: vi.fn(async () => undefined),
  }

  render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={['/appointments']}>
        <Routes>
          <Route path="/login" element={<LoginDestination />} />
          <Route path="/not-authorized" element={<p>Not authorized destination</p>} />
          <Route
            path="/appointments"
            element={(
              <ProtectedRoute allowedRoles={allowedRoles}>
                <p>Protected content</p>
              </ProtectedRoute>
            )}
          />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

describe('ProtectedRoute', () => {
  it('renders only a loading state while authentication is initializing', () => {
    renderProtectedRoute(null, true)

    expect(screen.getByRole('status')).toHaveTextContent('Loading...')
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument()
    expect(screen.queryByText(/Login destination/)).not.toBeInTheDocument()
  })

  it('redirects an unauthenticated user to login and preserves the attempted path', () => {
    renderProtectedRoute(null, false)

    expect(screen.getByText('Login destination from /appointments')).toBeInTheDocument()
  })

  it('renders protected children when the authenticated role is allowed', () => {
    renderProtectedRoute({ userId: 17, role: 'PATIENT' }, false, ['PATIENT'])

    expect(screen.getByText('Protected content')).toBeInTheDocument()
  })

  it('redirects an authenticated user with a disallowed role', () => {
    renderProtectedRoute({ userId: 18, role: 'PROVIDER' }, false, ['PATIENT'])

    expect(screen.getByText('Not authorized destination')).toBeInTheDocument()
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument()
  })
})
