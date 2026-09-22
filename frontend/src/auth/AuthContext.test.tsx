import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthContext'
import { useAuth } from './authState'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function AuthProbe() {
  const { user } = useAuth()
  return <p>{user ? `${user.userId}:${user.role}` : 'unauthenticated'}</p>
}

describe('AuthProvider restoration', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('restores the current user and role from /api/auth/me on mount', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ userId: 73, role: 'RECEPTIONIST' }),
    )
    vi.stubGlobal('fetch', fetchMock)

    render(<AuthProvider><AuthProbe /></AuthProvider>)

    expect(await screen.findByText('73:RECEPTIONIST')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/me', expect.objectContaining({
      method: 'GET',
      credentials: 'include',
    }))
  })

  it('treats a 401 from /api/auth/me as a normal unauthenticated state', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ message: 'Unauthorized' }, 401),
    )
    vi.stubGlobal('fetch', fetchMock)

    render(<AuthProvider><AuthProbe /></AuthProvider>)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(screen.getByText('unauthenticated')).toBeInTheDocument()
  })
})
